package dk.jarvisbot.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD (v4 ONNX interface: input, sr, h, c -> output, hn, cn), as bundled with openWakeWord 0.5.1.
 * Mirrors openwakeword/vad.py: audio scaled by 1/32767, 16 kHz, stateful LSTM.
 */
class SileroVad(private val env: OrtEnvironment, model: ByteArray) : AutoCloseable {
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
    }
    private val session = env.createSession(model, options)
    private var h = FloatArray(2 * 64)
    private var c = FloatArray(2 * 64)

    fun reset() {
        h = FloatArray(2 * 64)
        c = FloatArray(2 * 64)
    }

    /** Returns speech probability (0..1) for samples[offset until offset+length]. */
    fun process(samples: ShortArray, offset: Int, length: Int): Float {
        val x = FloatArray(length) { samples[offset + it] / 32767f }
        val shapeState = longArrayOf(2, 1, 64)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, length.toLong())).use { input ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16000L)), longArrayOf()).use { sr ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(h), shapeState).use { ht ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(c), shapeState).use { ct ->
                        session.run(mapOf("input" to input, "sr" to sr, "h" to ht, "c" to ct)).use { r ->
                            val prob = (r[0] as OnnxTensor).floatBuffer.get(0)
                            val hn = FloatArray(128); (r[1] as OnnxTensor).floatBuffer.get(hn)
                            val cn = FloatArray(128); (r[2] as OnnxTensor).floatBuffer.get(cn)
                            h = hn
                            c = cn
                            return prob
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        runCatching { session.close() }
        runCatching { options.close() }
    }
}
