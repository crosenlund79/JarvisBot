package dk.jarvisbot.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer

/**
 * Kotlin port of the openWakeWord (v0.5.1) streaming inference pipeline:
 *
 *   16 kHz int16 audio, 80 ms chunks (1280 samples)
 *     -> melspectrogram.onnx  (last 1760 samples -> 8 mel frames x 32 bins, transformed x/10 + 2)
 *     -> embedding_model.onnx (last 76 mel frames -> one 96-dim embedding per chunk)
 *     -> hey_jarvis_v0.1.onnx (last 16 embeddings -> score 0..1)
 *
 * The buffering intentionally mirrors openwakeword/utils.py AudioFeatures._streaming_features and
 * openwakeword/model.py Model.predict so that scores match the reference implementation.
 */
class WakeWordEngine(
    private val env: OrtEnvironment,
    melModel: ByteArray,
    embeddingModel: ByteArray,
    wakeModel: ByteArray,
    threads: Int = 1,
) : AutoCloseable {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK = 1280
        const val MEL_CONTEXT = 480
        const val MEL_BINS = 32
        const val EMB_WINDOW = 76
        const val EMB_STEP = 8
        const val EMB_DIM = 96
        private const val MAX_FEATURES = 120
        private const val WARMUP_PREDICTIONS = 5
    }

    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setInterOpNumThreads(1)
    }
    private val melSession = env.createSession(melModel, options)
    private val embSession = env.createSession(embeddingModel, options)
    private val wakeSession = env.createSession(wakeModel, options)
    private val melInput = melSession.inputNames.first()
    private val embInput = embSession.inputNames.first()
    private val wakeInput = wakeSession.inputNames.first()

    /** Number of embedding frames the wake model expects (16 for hey_jarvis_v0.1). */
    val wakeFrames: Int = run {
        val info = wakeSession.inputInfo[wakeInput]?.info as? TensorInfo
        val s = info?.shape
        if (s != null && s.size == 3 && s[1] > 0) s[1].toInt() else 16
    }

    val modelDescription: String =
        "mel=${melInput} emb=${embInput} wake=${wakeInput} wakeFrames=$wakeFrames"

    private var history: ShortArray? = null
    private val melFrames = ArrayDeque<FloatArray>()
    private val features = ArrayDeque<FloatArray>()
    private var predictions = 0L

    var chunksProcessed = 0L
        private set
    var lastProcessNanos = 0L
        private set

    init {
        reset()
    }

    /** Reset all streaming state (as on construction of a new openWakeWord Model). */
    fun reset() {
        history = null
        melFrames.clear()
        repeat(EMB_WINDOW) { melFrames.addLast(FloatArray(MEL_BINS) { 1f }) }
        features.clear()
        // The reference implementation seeds the feature buffer with embeddings of 4 s of random noise.
        val rnd = java.util.Random(1234)
        val noise = FloatArray(SAMPLE_RATE * 4) { (rnd.nextInt(2000) - 1000).toFloat() }
        val spec = melspectrogram(noise)
        var i = 0
        while (i + EMB_WINDOW <= spec.size) {
            features.addLast(embed(spec, i))
            i += EMB_STEP
        }
        while (features.size > MAX_FEATURES) features.removeFirst()
        predictions = 0
    }

    /**
     * Process exactly one 80 ms chunk (1280 samples) and return the wake-word score (0..1).
     */
    fun process(chunk: ShortArray): Float {
        require(chunk.size == CHUNK) { "chunk must be $CHUNK samples" }
        val t0 = System.nanoTime()
        val prev = history
        val input: FloatArray
        if (prev == null) {
            input = FloatArray(CHUNK) { chunk[it].toFloat() }
        } else {
            input = FloatArray(MEL_CONTEXT + CHUNK)
            for (j in 0 until MEL_CONTEXT) input[j] = prev[j].toFloat()
            for (j in 0 until CHUNK) input[MEL_CONTEXT + j] = chunk[j].toFloat()
        }
        val h = ShortArray(MEL_CONTEXT)
        System.arraycopy(chunk, CHUNK - MEL_CONTEXT, h, 0, MEL_CONTEXT)
        history = h

        for (frame in melspectrogram(input)) melFrames.addLast(frame)
        while (melFrames.size > EMB_WINDOW + 16) melFrames.removeFirst()

        val window = ArrayList<FloatArray>(EMB_WINDOW)
        val start = melFrames.size - EMB_WINDOW
        for (k in 0 until EMB_WINDOW) window.add(melFrames[start + k])
        features.addLast(embed(window, 0))
        while (features.size > MAX_FEATURES) features.removeFirst()

        var score = predict()
        if (predictions < WARMUP_PREDICTIONS) score = 0f
        predictions++
        chunksProcessed++
        lastProcessNanos = System.nanoTime() - t0
        return score
    }

    private fun predict(): Float {
        val n = wakeFrames
        val buf = FloatBuffer.allocate(n * EMB_DIM)
        val start = features.size - n
        for (k in 0 until n) buf.put(features[start + k])
        buf.rewind()
        OnnxTensor.createTensor(env, buf, longArrayOf(1, n.toLong(), EMB_DIM.toLong())).use { t ->
            wakeSession.run(mapOf(wakeInput to t)).use { r ->
                val out = (r[0] as OnnxTensor).floatBuffer
                return out.get(0)
            }
        }
    }

    /** Runs the mel model; returns frames x 32 with the openWakeWord transform (x/10 + 2) applied. */
    private fun melspectrogram(samples: FloatArray): List<FloatArray> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { t ->
            melSession.run(mapOf(melInput to t)).use { r ->
                val out = (r[0] as OnnxTensor).floatBuffer
                val frames = out.remaining() / MEL_BINS
                val res = ArrayList<FloatArray>(frames)
                for (f in 0 until frames) {
                    val a = FloatArray(MEL_BINS)
                    for (b in 0 until MEL_BINS) a[b] = out.get(f * MEL_BINS + b) / 10f + 2f
                    res.add(a)
                }
                return res
            }
        }
    }

    private fun embed(spec: List<FloatArray>, start: Int): FloatArray {
        val buf = FloatBuffer.allocate(EMB_WINDOW * MEL_BINS)
        for (k in 0 until EMB_WINDOW) buf.put(spec[start + k])
        buf.rewind()
        OnnxTensor.createTensor(env, buf, longArrayOf(1, EMB_WINDOW.toLong(), MEL_BINS.toLong(), 1)).use { t ->
            embSession.run(mapOf(embInput to t)).use { r ->
                val out = (r[0] as OnnxTensor).floatBuffer
                val e = FloatArray(EMB_DIM)
                out.get(e)
                return e
            }
        }
    }

    override fun close() {
        runCatching { melSession.close() }
        runCatching { embSession.close() }
        runCatching { wakeSession.close() }
        runCatching { options.close() }
    }
}
