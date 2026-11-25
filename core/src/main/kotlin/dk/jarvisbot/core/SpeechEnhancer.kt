package dk.jarvisbot.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Offline voice enhancement for exported recordings ("speech preset v1"). Pure Kotlin, no native code, no settings.
 *
 * Chain (deliberately gentle – intelligibility and natural sound over aggressive denoising):
 *  1. 80 Hz 2nd-order high-pass (rumble + DC removal).
 *  2. STFT Wiener noise suppression, as used in WebRTC-style suppressors: noise spectrum estimated from the quietest
 *     15 % of frames of the recording, decision-directed a-priori SNR, gain floor −12 dB (never removes more than
 *     12 dB, so quiet speech and consonants survive), light smoothing across frequency against "musical noise".
 *  3. +3 dB presence lift around 1.5–4.5 kHz (consonant clarity).
 *  4. Gentle 1.5:1 compression above −26 dBFS (10 ms attack, 200 ms release).
 *  5. Loudness normalisation of the active level to about −20 dBFS (max +24 dB, max −10 dB).
 *  6. Soft limiter: nothing exceeds −1 dBFS, no hard clipping.
 * Sample rate and length are unchanged. The input array is never modified.
 */
object SpeechEnhancer {
    const val METHOD = "jarvis-speech-v1"
    const val DESCRIPTION = "80 Hz high-pass; Wiener noise suppression (noise from quietest 15% of frames, decision-directed SNR, " +
        "gain floor -12 dB); +3 dB presence 1.5-4.5 kHz; 1.5:1 compression above -26 dBFS; normalisation to -20 dBFS active level " +
        "(-10..+24 dB); soft limiter at -1 dBFS"

    class Result(val samples: ShortArray, val info: LinkedHashMap<String, Any>)

    fun enhance(input: ShortArray, sampleRate: Int): Result = process(input, sampleRate, full = true)

    /** [full] = false runs only high-pass + noise suppression (used by tests to judge the denoiser on its own). */
    internal fun process(input: ShortArray, sampleRate: Int, full: Boolean): Result {
        val t0 = System.nanoTime()
        val info = LinkedHashMap<String, Any>()
        info["method"] = METHOD
        info["description"] = DESCRIPTION
        info["sampleRate"] = sampleRate
        val n = input.size
        val x = FloatArray(n) { input[it] / 32768f }
        info["inputRmsDb"] = r1(rmsDb(x))
        info["inputPeakDb"] = r1(peakDb(x))
        if (n == 0) {
            info["note"] = "empty input"
            return Result(ShortArray(0), info)
        }

        highPass(x, sampleRate, 80.0)
        val nsInfo = suppressNoise(x, sampleRate, presence = full)
        info.putAll(nsInfo)
        if (full) {
            compress(x, sampleRate)
            val gainDb = normalize(x, sampleRate)
            info["normalisationGainDb"] = r1(gainDb)
            softLimit(x)
        }

        val out = ShortArray(n) { (x[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        info["outputRmsDb"] = r1(rmsDb(x))
        info["outputPeakDb"] = r1(peakDb(x))
        info["processingMs"] = (System.nanoTime() - t0) / 1_000_000
        return Result(out, info)
    }

    // ------------------------------------------------------------------ 1. high-pass

    private fun highPass(x: FloatArray, fs: Int, f0: Double) {
        val w0 = 2 * PI * f0 / fs
        val alpha = sin(w0) / (2 * 0.7071)
        val c = cos(w0)
        val a0 = 1 + alpha
        val b0 = ((1 + c) / 2) / a0
        val b1 = (-(1 + c)) / a0
        val b2 = ((1 + c) / 2) / a0
        val a1 = (-2 * c) / a0
        val a2 = (1 - alpha) / a0
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val xi = x[i].toDouble()
            val y = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = xi; y2 = y1; y1 = y
            x[i] = y.toFloat()
        }
    }

    // ------------------------------------------------------------------ 2+3. STFT Wiener suppression + presence

    private fun suppressNoise(x: FloatArray, fs: Int, presence: Boolean): Map<String, Any> {
        val info = LinkedHashMap<String, Any>()
        var nfft = 256
        while (nfft < fs * 0.032) nfft *= 2 // ~32 ms or more: 512 @ 16 kHz, 2048 @ 44.1/48 kHz
        val hop = nfft / 2
        val bins = nfft / 2 + 1
        info["frameSize"] = nfft
        // sqrt-Hann analysis and synthesis windows: perfect reconstruction at 50 % overlap.
        val win = FloatArray(nfft) { sqrt(0.5 - 0.5 * cos(2 * PI * it / nfft)).toFloat() }
        val padded = FloatArray(x.size + 2 * nfft)
        System.arraycopy(x, 0, padded, nfft, x.size)
        val frames = (padded.size - nfft) / hop + 1
        if (frames < 8) {
            info["noiseSuppression"] = "skipped (recording too short)"
            return info
        }

        // Pass 1: power spectra of all frames.
        val power = Array(frames) { FloatArray(bins) }
        val frameEnergy = DoubleArray(frames)
        val re = DoubleArray(nfft)
        val im = DoubleArray(nfft)
        for (f in 0 until frames) {
            val off = f * hop
            for (i in 0 until nfft) { re[i] = (padded[off + i] * win[i]).toDouble(); im[i] = 0.0 }
            fft(re, im, false)
            var e = 0.0
            for (k in 0 until bins) {
                val p = re[k] * re[k] + im[k] * im[k]
                power[f][k] = p.toFloat()
                e += p
            }
            frameEnergy[f] = e
        }

        // Noise estimate: mean spectrum of the quietest 15 % of frames (ignoring the zero padding at the ends).
        val inner = (2 until frames - 2).toList()
        val quiet = inner.sortedBy { frameEnergy[it] }.take(max(4, inner.size * 15 / 100))
        val noise = DoubleArray(bins)
        for (f in quiet) for (k in 0 until bins) noise[k] += power[f][k].toDouble()
        for (k in 0 until bins) noise[k] = noise[k] / quiet.size + 1e-12
        val smoothNoise = DoubleArray(bins) { k ->
            val a = max(0, k - 1); val b = min(bins - 1, k + 1)
            (a..b).sumOf { noise[it] } / (b - a + 1)
        }
        val noiseRms = sqrt(smoothNoise.sum() / (nfft.toDouble() * nfft) * 2)
        info["noiseFloorDb"] = r1(20 * log10(max(noiseRms, 1e-9)))
        info["noiseFramesUsed"] = quiet.size

        // Presence lift (+3 dB raised-cosine bump between 1.5 and 4.5 kHz).
        val eq = DoubleArray(bins) { k ->
            val f = k.toDouble() * fs / nfft
            if (presence && f in 1500.0..4500.0) 1.0 + (10.0.pow(3.0 / 20) - 1.0) * 0.5 * (1 - cos(2 * PI * (f - 1500.0) / 3000.0)) else 1.0
        }

        // Pass 2: gains, synthesis, overlap-add.
        val alpha = 0.98
        val gMin = 10.0.pow(-12.0 / 20)
        val prevG = DoubleArray(bins) { 1.0 }
        val prevGamma = DoubleArray(bins) { 1.0 }
        val g = DoubleArray(bins)
        val out = FloatArray(padded.size)
        var gainSum = 0.0
        for (f in 0 until frames) {
            val off = f * hop
            for (i in 0 until nfft) { re[i] = (padded[off + i] * win[i]).toDouble(); im[i] = 0.0 }
            fft(re, im, false)
            for (k in 0 until bins) {
                val gamma = min(power[f][k] / smoothNoise[k], 1e4)
                val xi = alpha * prevG[k] * prevG[k] * prevGamma[k] + (1 - alpha) * max(gamma - 1.0, 0.0)
                g[k] = max(xi / (1.0 + xi), gMin)
                prevGamma[k] = gamma
            }
            for (k in 0 until bins) {
                val a = max(0, k - 1); val b = min(bins - 1, k + 1)
                val gs = (g[a] + 2 * g[k] + g[b]) / 4.0
                prevG[k] = g[k]
                gainSum += gs
                val total = gs * eq[k]
                re[k] *= total; im[k] *= total
                if (k in 1 until nfft / 2) { re[nfft - k] = re[k]; im[nfft - k] = -im[k] }
            }
            fft(re, im, true)
            for (i in 0 until nfft) out[off + i] += (re[i] * win[i]).toFloat()
        }
        System.arraycopy(out, nfft, x, 0, x.size)
        info["noiseSuppression"] = "applied"
        info["meanSuppressionGainDb"] = r1(20 * log10(max(gainSum / (frames.toDouble() * bins), 1e-9)))
        return info
    }

    private var twN = 0
    private var twCos = DoubleArray(0)
    private var twSin = DoubleArray(0)

    /** In-place iterative radix-2 complex FFT (inverse includes the 1/N scale). Twiddles cached per size. */
    @Synchronized
    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        if (twN != n) {
            twCos = DoubleArray(n / 2) { cos(2 * PI * it / n) }
            twSin = DoubleArray(n / 2) { sin(2 * PI * it / n) }
            twN = n
        }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val sign = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                for (k in 0 until half) {
                    val cr = twCos[k * step]; val ci = sign * twSin[k * step]
                    val a = i + k; val b = a + half
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    val ur = re[a]; val ui = im[a]
                    re[a] = ur + vr; im[a] = ui + vi
                    re[b] = ur - vr; im[b] = ui - vi
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    // ------------------------------------------------------------------ 4. compression

    private fun compress(x: FloatArray, fs: Int) {
        val thrDb = -26.0
        val ratio = 1.5
        val att = exp(-1.0 / (0.010 * fs))
        val rel = exp(-1.0 / (0.200 * fs))
        var env = 0.0
        var gain = 1.0
        for (i in x.indices) {
            val p = x[i].toDouble() * x[i]
            env = if (p > env) att * env + (1 - att) * p else rel * env + (1 - rel) * p
            if (i and 31 == 0) { // gain update every 32 samples (envelope is smooth anyway)
                val levelDb = 10 * log10(env + 1e-12)
                gain = if (levelDb > thrDb) 10.0.pow(((thrDb + (levelDb - thrDb) / ratio) - levelDb) / 20) else 1.0
            }
            if (gain != 1.0) x[i] = (x[i] * gain).toFloat()
        }
    }

    // ------------------------------------------------------------------ 5. normalisation

    /** Scales so the active level (95th percentile of 50 ms block RMS) reaches −20 dBFS. Returns the gain in dB. */
    private fun normalize(x: FloatArray, fs: Int): Double {
        val block = max(1, fs / 20)
        val levels = ArrayList<Double>()
        var i = 0
        while (i < x.size) {
            val end = min(x.size, i + block)
            var s = 0.0
            for (j in i until end) s += x[j].toDouble() * x[j]
            levels.add(sqrt(s / (end - i)))
            i = end
        }
        levels.sort()
        val active = levels[min(levels.size - 1, (levels.size * 0.95).toInt())]
        if (active <= 1e-7) return 0.0
        val gainDb = (-20.0 - 20 * log10(active)).coerceIn(-10.0, 24.0)
        val g = 10.0.pow(gainDb / 20).toFloat()
        for (k in x.indices) x[k] *= g
        return gainDb
    }

    // ------------------------------------------------------------------ 6. soft limiter

    private fun softLimit(x: FloatArray) {
        val ceiling = 10.0.pow(-1.0 / 20) // −1 dBFS
        val knee = 10.0.pow(-6.0 / 20)    // soft knee starts at −6 dBFS
        for (i in x.indices) {
            val a = abs(x[i].toDouble())
            if (a > knee) {
                val over = (a - knee) / (ceiling - knee)
                val limited = knee + (ceiling - knee) * tanh(over)
                x[i] = (if (x[i] < 0) -limited else limited).toFloat()
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun rmsDb(x: FloatArray): Double {
        if (x.isEmpty()) return -120.0
        var s = 0.0
        for (v in x) s += v.toDouble() * v
        val r = sqrt(s / x.size)
        return if (r <= 0) -120.0 else max(-120.0, 20 * log10(r))
    }

    private fun peakDb(x: FloatArray): Double {
        var p = 0.0
        for (v in x) p = max(p, abs(v.toDouble()))
        return if (p <= 0) -120.0 else max(-120.0, 20 * log10(p))
    }

    private fun r1(v: Double) = Math.round(v * 10.0) / 10.0
}
