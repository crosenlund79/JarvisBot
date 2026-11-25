package dk.jarvisbot.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Band-limited (windowed-sinc, Blackman) sample-rate conversion; used to feed 16 kHz models from 44.1/48 kHz recordings. */
object Resampler {
    fun toFloat(pcm: ShortArray): FloatArray = FloatArray(pcm.size) { pcm[it] / 32768f }

    fun to16k(pcm: ShortArray, sampleRate: Int): FloatArray = resample(toFloat(pcm), sampleRate, 16000)

    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        require(from > 0 && to > 0)
        if (from == to || x.isEmpty()) return x.copyOf()
        val g = gcd(from, to)
        val up = to / g
        val down = from / g
        val fc = 0.5 * min(1.0, to.toDouble() / from) * 0.92 // cut-off, cycles per input sample
        val half = ceil(6.0 / fc).toInt() // 12 zero crossings on each side
        val taps = 2 * half
        val nOut = (x.size.toLong() * up / down).toInt()
        val out = FloatArray(nOut)
        // One kernel per output phase when the ratio is a small fraction (44.1k->16k: 160 phases), else computed on the fly.
        val table: Array<FloatArray>? = if (up <= 2048) Array(up) { p -> FloatArray(taps) { j -> kernel(p.toDouble() / up - (j - half + 1), fc, half) } } else null
        val tmp = FloatArray(taps)
        for (n in 0 until nOut) {
            val pos = n.toLong() * down
            val i0 = (pos / up).toInt()
            val p = (pos % up).toInt()
            val h = table?.get(p) ?: tmp.also { for (j in 0 until taps) it[j] = kernel(p.toDouble() / up - (j - half + 1), fc, half) }
            var acc = 0.0
            val k0 = i0 - half + 1
            for (j in 0 until taps) {
                val k = k0 + j
                if (k >= 0 && k < x.size) acc += x[k] * h[j]
            }
            out[n] = acc.toFloat()
        }
        return out
    }

    private fun kernel(d: Double, fc: Double, half: Int): Float {
        if (abs(d) >= half) return 0f
        val a = 2 * PI * fc * d
        val sinc = if (abs(a) < 1e-9) 1.0 else sin(a) / a
        val w = 0.42 + 0.5 * cos(PI * d / half) + 0.08 * cos(2 * PI * d / half)
        return (2 * fc * sinc * w).toFloat()
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
