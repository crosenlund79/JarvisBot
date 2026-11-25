package dk.jarvisbot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class SpeechEnhancerTest {
    private val testdata = File(System.getProperty("jarvis.testdata") ?: "")

    private fun siSdr(ref: DoubleArray, est: DoubleArray): Double {
        var dot = 0.0; var rr = 0.0
        for (i in ref.indices) { dot += ref[i] * est[i]; rr += ref[i] * ref[i] }
        val a = dot / rr
        var s = 0.0; var e = 0.0
        for (i in ref.indices) { val t = a * ref[i]; s += t * t; val d = est[i] - t; e += d * d }
        return 10 * log10(s / e)
    }

    /** Clean speech at about −26 dBFS RMS, with 1 s of exact silence before and after (noise-only reference). */
    private fun cleanSpeech(sr: Int): DoubleArray {
        val f = File(testdata, "pos_cont_us.wav")
        val core: DoubleArray = if (testdata.isDirectory && f.exists()) {
            val (pcm, rate) = Wav.read(f)
            val src = DoubleArray(pcm.size) { pcm[it] / 32768.0 }
            if (rate == sr) src else DoubleArray((src.size.toLong() * sr / rate).toInt()) { i ->
                val p = i.toDouble() * rate / sr; val k = p.toInt(); val fr = p - k
                src[minOf(k, src.size - 1)] * (1 - fr) + src[minOf(k + 1, src.size - 1)] * fr
            }
        } else DoubleArray(sr * 3) { i ->
            val t = i.toDouble() / sr
            val env = if ((t * 3).toInt() % 2 == 0) sin(PI * (t * 3 % 1.0)) else 0.0
            env * 0.3 * (sin(2 * PI * 180 * t) + 0.5 * sin(2 * PI * 360 * t) + 0.3 * sin(2 * PI * 2500 * t))
        }
        val rms = sqrt(core.sumOf { it * it } / core.size)
        val g = 0.05 / rms // −26 dBFS
        return DoubleArray(sr) + DoubleArray(core.size) { core[it] * g } + DoubleArray(sr)
    }

    private fun noisy(clean: DoubleArray, sr: Int): DoubleArray {
        val rnd = Random(7)
        return DoubleArray(clean.size) { i ->
            val t = i.toDouble() / sr
            clean[i] + 0.022 * rnd.nextGaussian() + 0.01 * sin(2 * PI * 50 * t) + 0.02 * sin(2 * PI * 25 * t)
        }
    }

    private fun pcm(x: DoubleArray) = ShortArray(x.size) { (x[it] * 32767).toInt().coerceIn(-32768, 32767).toShort() }
    private fun dbl(s: ShortArray) = DoubleArray(s.size) { s[it] / 32768.0 }
    private fun highPassed(x: DoubleArray, sr: Int): DoubleArray {
        val f = FloatArray(x.size) { x[it].toFloat() }
        SpeechEnhancer.highPass(f, sr, 80.0)
        return DoubleArray(f.size) { f[it].toDouble() }
    }

    /** Ratio (dB) of mean power in speech-active 20 ms frames to mean power in speech-free frames. */
    private fun speechToNoiseDb(clean: DoubleArray, sig: DoubleArray, sr: Int): Double {
        val fl = sr / 50
        var sp = 0.0; var spn = 0; var np = 0.0; var nn = 0
        var i = 0
        while (i + fl <= clean.size) {
            val ce = (i until i + fl).sumOf { clean[it] * clean[it] } / fl
            val se = (i until i + fl).sumOf { sig[it] * sig[it] } / fl
            if (ce > 1e-4) { sp += se; spn++ } else if (ce == 0.0) { np += se; nn++ }
            i += fl
        }
        return 10 * log10((sp / spn) / (np / nn))
    }

    private fun check(sr: Int) {
        val clean = cleanSpeech(sr)
        val mix = noisy(clean, sr)
        val input = pcm(mix)

        // The 80 Hz high-pass is intentional and shifts the phase of low harmonics, which SI-SDR would count as error.
        // So the noise-suppression stage is judged against the high-passed clean signal, starting from the
        // high-passed noisy input.
        val hpClean = highPassed(clean, sr)
        val ns = SpeechEnhancer.process(input, sr, full = false)
        val nsOut = dbl(ns.samples)
        val sdrIn = siSdr(hpClean, highPassed(dbl(input), sr))
        val sdrNs = siSdr(hpClean, nsOut)

        val full = SpeechEnhancer.enhance(input, sr)
        val out = dbl(full.samples)
        val snrIn = speechToNoiseDb(clean, mix, sr)
        val snrOut = speechToNoiseDb(clean, out, sr)
        val peak = full.samples.maxOf { abs(it.toInt()) }
        println("sr=$sr  denoiser SI-SDR %.1f -> %.1f dB | full chain speech/noise %.1f -> %.1f dB, peak %d, out %s dBFS rms, %s ms"
            .format(sdrIn, sdrNs, snrIn, snrOut, peak, full.info["outputRmsDb"], full.info["processingMs"]))
        assertEquals(input.size, full.samples.size)
        assertTrue("denoiser should improve SI-SDR", sdrNs > sdrIn + 2.0)
        assertTrue("full chain should improve speech/noise ratio", snrOut > snrIn + 5.0)
        assertTrue("no clipping", peak < 32767)
        if (testdata.isDirectory) {
            Wav.write(File(testdata, "enh_${sr}_noisy.wav"), input, sr)
            Wav.write(File(testdata, "enh_${sr}_enhanced.wav"), full.samples, sr)
        }
    }

    @Test fun improves16k() = check(16000)
    @Test fun improves44k() = check(44100)

    @Test fun handlesSilenceAndShortInput() {
        assertEquals(0, SpeechEnhancer.enhance(ShortArray(0), 16000).samples.size)
        val zeros = SpeechEnhancer.enhance(ShortArray(16000), 16000)
        assertTrue(zeros.samples.all { it.toInt() == 0 })
        assertEquals(100, SpeechEnhancer.enhance(ShortArray(100) { (it * 50).toShort() }, 44100).samples.size)
    }
}
