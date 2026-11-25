package dk.jarvisbot.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Base64
import java.util.zip.Deflater
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Whisper log-mel spectrogram, identical to openai-whisper's log_mel_spectrogram (n_fft 400, hop 160, periodic Hann,
 * centred frames with reflect padding, 80 Slaney mel bands from whisper/assets/mel_filters.npz, log10, 8 dB dynamic
 * range clamp, (x + 4) / 4). Output layout: [mel][frame].
 */
class WhisperMel(private val filters: FloatArray) {
    companion object {
        const val N_FFT = 400
        const val HOP = 160
        const val N_MELS = 80
        const val BINS = N_FFT / 2 + 1

        /** 80 x 201 float32 little-endian (mel_80 from openai-whisper's mel_filters.npz). */
        fun loadFilters(bytes: ByteArray): FloatArray {
            require(bytes.size == N_MELS * BINS * 4) { "mel filter file has ${bytes.size} bytes, expected ${N_MELS * BINS * 4}" }
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(N_MELS * BINS).also { fb.get(it) }
        }
    }

    private val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2 * PI * it / N_FFT)).toFloat() }
    private val cosT = FloatArray(BINS * N_FFT)
    private val sinT = FloatArray(BINS * N_FFT)

    init {
        require(filters.size == N_MELS * BINS)
        for (k in 0 until BINS) for (n in 0 until N_FFT) {
            val a = 2 * PI * ((k.toLong() * n) % N_FFT) / N_FFT
            cosT[k * N_FFT + n] = cos(a).toFloat()
            sinT[k * N_FFT + n] = sin(a).toFloat()
        }
    }

    /** Features of [audio] (16 kHz, -1..1) zero-padded or cut to [nFrames] * 160 samples. */
    fun compute(audio: FloatArray, nFrames: Int): FloatArray {
        val n = nFrames * HOP
        val len = min(audio.size, n)
        fun sample(i0: Int): Float {
            var i = i0
            if (i < 0) i = -i
            if (i >= n) i = 2 * (n - 1) - i
            return if (i in 0 until len) audio[i] else 0f
        }
        val logMel = FloatArray(N_MELS * nFrames)
        val frame = FloatArray(N_FFT)
        val power = FloatArray(BINS)
        var maxLog = Float.NEGATIVE_INFINITY
        for (f in 0 until nFrames) {
            val start = f * HOP - N_FFT / 2
            var nonZero = false
            for (j in 0 until N_FFT) {
                val v = sample(start + j) * window[j]
                frame[j] = v
                if (v != 0f) nonZero = true
            }
            if (nonZero) {
                for (k in 0 until BINS) {
                    var re = 0f
                    var im = 0f
                    val o = k * N_FFT
                    for (j in 0 until N_FFT) {
                        val v = frame[j]
                        re += v * cosT[o + j]
                        im += v * sinT[o + j]
                    }
                    power[k] = re * re + im * im
                }
            } else power.fill(0f)
            for (m in 0 until N_MELS) {
                var s = 0f
                val o = m * BINS
                for (k in 0 until BINS) s += filters[o + k] * power[k]
                val l = log10(max(s, 1e-10f))
                logMel[m * nFrames + f] = l
                if (l > maxLog) maxLog = l
            }
        }
        val floor = maxLog - 8f
        for (i in logMel.indices) logMel[i] = (max(logMel[i], floor) + 4f) / 4f
        return logMel
    }
}

/**
 * Offline Whisper speech recognition with ONNX Runtime, for the sherpa-onnx export (encoder: mel -> cross-attention
 * k/v; decoder with self-attention cache). Greedy decoding without timestamps, like whisper's default first pass.
 * The decoder used by Jarvis has a variable cache length and an int8 token-embedding table (see tools/).
 */
class WhisperModel(
    private val env: OrtEnvironment, encoder: ByteBuffer, decoder: ByteBuffer, tokens: List<String>, threads: Int,
    private val mel: WhisperMel,
) : AutoCloseable {

    class Result(
        val text: String,
        val tokens: Int,
        /** Chosen language among the allowed ones, with probabilities renormalised over those. */
        val language: String,
        val languageProbs: Map<String, Double>,
        /** Most likely language among all Whisper languages, and its probability. */
        val topLanguage: String,
        val topLanguageProb: Double,
        val noSpeechProb: Double,
        val avgLogProb: Double,
        val compressionRatio: Double,
        /** EOT (normal end), MAX_TOKENS or REPETITION. */
        val stop: String,
        val frames: Int,
        val encoderMs: Long,
        val decoderMs: Long,
        val tokenIds: List<Int> = emptyList(),
    )

    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setInterOpNumThreads(1)
        // Worker threads sleep instead of spinning between operations: less CPU / battery while waiting.
        addConfigEntry("session.intra_op.allow_spinning", "0")
        // Lets the int8 token table (DequantizeLinear on a constant) be folded to float once at load time.
        addConfigEntry("session.disable_quant_qdq", "1")
    }
    private val enc: OrtSession = env.createSession(encoder, options)
    private val dec: OrtSession = env.createSession(decoder, options)

    private val meta: Map<String, String> = enc.metadata.customMetadata
    private fun int(k: String) = meta[k]?.toInt() ?: throw IllegalStateException("model metadata '$k' missing")
    val modelType: String = meta["model_type"] ?: "whisper"
    private val nLayer = int("n_text_layer")
    private val nState = int("n_text_state")
    private val nVocab = int("n_vocab")
    private val maxCtx = int("n_text_ctx")
    private val sot = int("sot")
    private val eot = int("eot")
    private val blank = int("blank_id")
    private val noSpeech = int("no_speech")
    private val noTimestamps = int("no_timestamps")
    private val transcribe = int("transcribe")
    private val langTokens: Map<String, Int> = meta["all_language_codes"]!!.split(",").zip(meta["all_language_tokens"]!!.split(",").map { it.trim().toInt() }).toMap()
    private val suppress = BooleanArray(nVocab).also { s ->
        for (i in eot + 1 until nVocab) s[i] = true // all special tokens (sot, languages, tasks, timestamps)
        meta["non_speech_tokens"]?.split(",")?.forEach { t -> t.trim().toIntOrNull()?.let { if (it in 0 until nVocab) s[it] = true } }
    }
    private val vocab: Array<ByteArray?> = arrayOfNulls<ByteArray>(nVocab).also { v ->
        val dec64 = Base64.getDecoder()
        for (line in tokens) {
            val p = line.trim().split(' ')
            if (p.size == 2) p[1].toIntOrNull()?.let { if (it in v.indices) v[it] = runCatching { dec64.decode(p[0]) }.getOrDefault(ByteArray(0)) }
        }
    }

    fun supportsLanguage(code: String) = code in langTokens

    /**
     * Transcribes up to ~20 s of 16 kHz audio (longer input is cut at 30 s). [languages]: allowed languages (e.g. da, en);
     * the most likely of them is used.
     */
    fun transcribe(audio: FloatArray, languages: List<String>): Result {
        val t0 = System.nanoTime()
        val nFrames = min(3000, audio.size / WhisperMel.HOP + 1000) // at least 10 s of trailing silence, like sherpa-onnx
        return transcribeFeatures(mel.compute(audio, nFrames), nFrames, audio.size / 16000.0, languages, t0)
    }

    internal fun transcribeFeatures(features: FloatArray, nFrames: Int, seconds: Double, languages: List<String>, t0: Long = System.nanoTime()): Result {
        val langs = languages.filter { it in langTokens }.ifEmpty { listOf("en") }
        // Cache long enough for fast Danish speech (~8 tokens/s) plus margin; shorter caches decode faster.
        val ctx = min(min(maxCtx, 224), 40 + 12 * kotlin.math.ceil(seconds).toInt())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, WhisperMel.N_MELS.toLong(), nFrames.toLong())).use { melT ->
            enc.run(mapOf("mel" to melT)).use { encOut ->
                val t1 = System.nanoTime()
                val crossK = encOut[0] as OnnxTensor
                val crossV = encOut[1] as OnnxTensor
                // 1) language + no-speech probability from the logits after <|startoftranscript|>
                val first = Decoder(crossK, crossV, 1).use { it.step(longArrayOf(sot.toLong())) }
                // The exported decoder accepts several tokens in one run only at position 0, so the prompt starts afresh.
                val dec = Decoder(crossK, crossV, ctx)
                try {
                    val noSpeechProb = softmaxProb(first, noSpeech)
                    var bestAll = ""; var bestAllLogit = Float.NEGATIVE_INFINITY
                    var lmax = Float.NEGATIVE_INFINITY
                    for ((_, tok) in langTokens) lmax = max(lmax, first[tok])
                    var lsum = 0.0
                    for ((code, tok) in langTokens) {
                        lsum += exp((first[tok] - lmax).toDouble())
                        if (first[tok] > bestAllLogit) { bestAllLogit = first[tok]; bestAll = code }
                    }
                    val topProb = exp((bestAllLogit - lmax).toDouble()) / lsum
                    val amax = langs.maxOf { first[langTokens.getValue(it)] }
                    val aw = langs.associateWith { exp((first[langTokens.getValue(it)] - amax).toDouble()) }
                    val asum = aw.values.sum()
                    val langProbs = aw.mapValues { it.value / asum }
                    val lang = langProbs.maxByOrNull { it.value }!!.key

                    // 2) prompt <|startoftranscript|><|lang|><|transcribe|><|notimestamps|>, then greedy decoding
                    var logits = dec.step(longArrayOf(sot.toLong(), langTokens.getValue(lang).toLong(), transcribe.toLong(), noTimestamps.toLong()))
                    val out = ArrayList<Int>()
                    var sumLogProb = 0.0
                    var stop = "MAX_TOKENS"
                    val maxTokens = ctx - dec.offset - 1
                    while (out.size < maxTokens) {
                        val firstStep = out.isEmpty()
                        var best = -1
                        var bestV = Float.NEGATIVE_INFINITY
                        var m = Float.NEGATIVE_INFINITY
                        for (i in 0 until nVocab) {
                            if (suppressed(i, firstStep)) continue
                            val v = logits[i]
                            if (v > m) m = v
                            if (v > bestV) { bestV = v; best = i }
                        }
                        var s = 0.0
                        for (i in 0 until nVocab) if (!suppressed(i, firstStep)) s += exp((logits[i] - m).toDouble())
                        sumLogProb += (bestV - m) - ln(s)
                        if (best == eot) { stop = "EOT"; break }
                        out.add(best)
                        if (repeating(out)) { stop = "REPETITION"; break }
                        if (out.size >= maxTokens) break
                        logits = dec.step(longArrayOf(best.toLong()))
                    }
                    val t2 = System.nanoTime()
                    val bytes = java.io.ByteArrayOutputStream()
                    for (t in out) if (t < eot) vocab[t]?.let { bytes.write(it) }
                    val textBytes = bytes.toByteArray()
                    val text = String(textBytes, Charsets.UTF_8).trim()
                    return Result(
                        text, out.size, lang, langProbs, bestAll, topProb, noSpeechProb, sumLogProb / (out.size + 1),
                        compressionRatio(textBytes), stop, nFrames, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, out,
                    )
                } finally {
                    dec.close()
                }
            }
        }
    }

    private fun suppressed(i: Int, firstStep: Boolean) = suppress[i] || (firstStep && (i == eot || i == blank))

    /** The same 1..15-token phrase six times in a row at the end: a decoding loop, not speech. */
    private fun repeating(t: List<Int>): Boolean {
        for (l in 1..15) {
            if (t.size < 6 * l) return false
            var same = true
            loop@ for (r in 1 until 6) for (j in 0 until l) {
                if (t[t.size - 1 - j] != t[t.size - 1 - j - r * l]) { same = false; break@loop }
            }
            if (same) return true
        }
        return false
    }

    private fun softmaxProb(logits: FloatArray, idx: Int): Double {
        var m = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > m) m = v
        var s = 0.0
        for (v in logits) s += exp((v - m).toDouble())
        return exp((logits[idx] - m).toDouble()) / s
    }

    private fun compressionRatio(b: ByteArray): Double {
        if (b.isEmpty()) return 0.0
        // Like whisper: len(text bytes) / len(zlib.compress(text bytes)); high values mean repetitive output.
        val d = Deflater()
        d.setInput(b); d.finish()
        val buf = ByteArray(b.size * 2 + 64)
        var n = 0
        while (!d.finished() && n < buf.size) n += d.deflate(buf, n, buf.size - n)
        d.end()
        return b.size.toDouble() / max(1, n)
    }

    /** Decoder state for one window: self-attention cache tensors are handed from one run to the next. */
    private inner class Decoder(private val crossK: OnnxTensor, private val crossV: OnnxTensor, private val ctx: Int) : AutoCloseable {
        var offset = 0
            private set
        private val shape = longArrayOf(nLayer.toLong(), 1, ctx.toLong(), nState.toLong())
        private var k: OnnxTensor = zeros()
        private var v: OnnxTensor = zeros()
        private var initial = true
        private var last: OrtSession.Result? = null

        private fun zeros(): OnnxTensor {
            val n = nLayer.toLong() * ctx * nState
            val fb = ByteBuffer.allocateDirect((n * 4).toInt()).order(ByteOrder.nativeOrder()).asFloatBuffer()
            return OnnxTensor.createTensor(env, fb, shape)
        }

        /** Runs [tokens] at the current offset; returns the logits after the last one. */
        fun step(tokens: LongArray): FloatArray {
            val res = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), longArrayOf(1, tokens.size.toLong())).use { tt ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(offset.toLong())), longArrayOf(1)).use { ot ->
                    dec.run(mapOf("tokens" to tt, "in_n_layer_self_k_cache" to k, "in_n_layer_self_v_cache" to v,
                        "n_layer_cross_k" to crossK, "n_layer_cross_v" to crossV, "offset" to ot))
                }
            }
            if (initial) { k.close(); v.close(); initial = false }
            last?.close()
            last = res
            k = res[1] as OnnxTensor
            v = res[2] as OnnxTensor
            offset += tokens.size
            val lt = res[0] as OnnxTensor
            val fb = lt.floatBuffer
            val row = FloatArray(nVocab)
            fb.position((tokens.size - 1) * nVocab)
            fb.get(row)
            return row
        }

        override fun close() {
            if (initial) { runCatching { k.close() }; runCatching { v.close() } }
            last?.close()
            last = null
        }
    }

    override fun close() {
        runCatching { enc.close() }
        runCatching { dec.close() }
        runCatching { options.close() }
    }
}
