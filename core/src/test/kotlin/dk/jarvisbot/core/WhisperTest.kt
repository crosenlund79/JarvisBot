package dk.jarvisbot.core

import ai.onnxruntime.OrtEnvironment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    private fun tone(f: Double, sr: Int, n: Int) = FloatArray(n) { (0.5 * sin(2 * PI * f * it / sr)).toFloat() }
    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size) = sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))

    @Test fun passesSpeechBandAndRemovesAliases() {
        for (sr in listOf(44100, 48000, 22050, 8000)) {
            val inBand = Resampler.resample(tone(1000.0, sr, sr), sr, 16000)
            assertEquals(16000, inBand.size)
            assertEquals("1 kHz level after $sr -> 16 kHz", 0.5 / sqrt(2.0), rms(inBand, 2000, 14000), 0.01)
            if (sr > 16000) {
                // 10 kHz cannot be represented at 16 kHz; it must not fold back as a 6 kHz alias
                val alias = Resampler.resample(tone(10000.0, sr, sr), sr, 16000)
                assertTrue("10 kHz suppressed after $sr -> 16 kHz: ${rms(alias, 2000, 14000)}", rms(alias, 2000, 14000) < 0.5 / sqrt(2.0) / 100)
            }
        }
    }
}

/** Needs the Whisper model in app/src/main/assets/models/whisper (tools/whisper/fetch_model.sh); skipped otherwise. */
class WhisperTest {
    private val models = File(System.getProperty("jarvis.models") ?: "")
    private val dir = File(models, "whisper")
    private val testdata = File(System.getProperty("jarvis.testdata") ?: "")

    private fun map(f: File): ByteBuffer = java.io.RandomAccessFile(f, "r").use { it.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, f.length()) }

    private fun model(): WhisperModel {
        assumeTrue("Whisper model not present", File(dir, "small-decoder.jarvis.onnx").exists())
        val mel = WhisperMel(WhisperMel.loadFilters(File(dir, "mel_filters_80.bin").readBytes()))
        return WhisperModel(OrtEnvironment.getEnvironment(), map(File(dir, "small-encoder.int8.onnx")), map(File(dir, "small-decoder.jarvis.onnx")),
            File(dir, "small-tokens.txt").readLines(), 4, mel)
    }

    @Test fun silenceGivesNoChunks() {
        model().use { w ->
            SileroVad(OrtEnvironment.getEnvironment(), File(models, "silero_vad.onnx").readBytes()).use { vad ->
                val t = SpeechTranscriber(w, vad).transcribe(ShortArray(44100 * 2), 44100, listOf("da", "en"))
                assertEquals(0, t.chunks.size)
                assertTrue(!t.hasText)
            }
        }
    }

    @Test fun matchesPythonReference() {
        val clips = testdata.listFiles { f -> f.name.startsWith("whisper_") && f.name.endsWith(".wav") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no whisper test clips", clips.isNotEmpty())
        val mel = WhisperMel(WhisperMel.loadFilters(File(dir, "mel_filters_80.bin").readBytes()))
        model().use { w ->
            for (wav in clips) {
                val ref = JSONObject(File(wav.path.removeSuffix(".wav") + ".json").readText())
                val (pcm, sr) = Wav.read(wav)
                assertEquals(16000, sr)
                val audio = FloatArray(pcm.size) { pcm[it] / 32768f }
                // features
                val frames = ref.getInt("frames")
                val k = mel.compute(audio, frames)
                val refMel = File(wav.path.removeSuffix(".wav") + ".mel").readBytes().let { b ->
                    FloatArray(b.size / 4).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
                }
                assertEquals(refMel.size, k.size)
                val melDiff = k.indices.maxOf { abs(k[it] - refMel[it]) }
                // tokens
                val r = w.transcribe(audio, listOf("da", "en"))
                val refTokens = ref.getJSONArray("tokens").let { a -> List(a.length()) { a.getInt(it) } }
                println("%-22s mel max diff %.5f | %s %s | %d tokens, avg log-prob %.3f (ref %.3f), no-speech %.3f | enc %d ms, dec %d ms | %s".format(
                    wav.name, melDiff, r.language, r.languageProbs.mapValues { "%.3f".format(it.value) }, r.tokens, r.avgLogProb,
                    ref.getDouble("avgLogProb"), r.noSpeechProb, r.encoderMs, r.decoderMs, r.text))
                // Same pipeline fed with the reference features: must reproduce the reference tokens exactly.
                val rf = w.transcribeFeatures(refMel, frames, pcm.size / 16000.0, listOf("da", "en"))
                assertEquals("tokens from reference features", refTokens, rf.tokenIds)
                assertTrue("mel features differ by $melDiff", melDiff < 2e-3)
                assertEquals(ref.getString("language"), r.language)
                // From our own features, tiny float differences (1e-5) can flip a near-tie late in greedy decoding,
                // so require the same start and a similar result rather than identical text.
                val prefix = refTokens.zip(r.tokenIds).takeWhile { it.first == it.second }.size
                println("  identical first $prefix of ${refTokens.size} tokens")
                assertTrue("diverged after $prefix tokens", prefix >= minOf(10, refTokens.size))
                assertEquals(refTokens.size.toDouble(), r.tokens.toDouble(), refTokens.size * 0.25)
            }
        }
    }
}
