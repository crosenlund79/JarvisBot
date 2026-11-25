package dk.jarvisbot.core

import ai.onnxruntime.OrtEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Local verification: compares the Kotlin pipeline with scores produced by the original Python
 * openWakeWord 0.5.1 implementation on the same WAV files. Set JARVIS_TESTDATA to a directory that
 * contains *.wav, ref_scores.json and ref_vad.json; otherwise the test is skipped.
 */
class ReferenceParityTest {
    private val models = File(System.getProperty("jarvis.models") ?: "")
    private val testdata = File(System.getProperty("jarvis.testdata") ?: "")

    private fun parseJson(f: File): Map<String, List<Double>> {
        // Minimal parser for {"name": [n, n, ...], ...}
        val txt = f.readText()
        val res = LinkedHashMap<String, List<Double>>()
        val re = Regex("\"([^\"]+)\"\\s*:\\s*\\[([^\\]]*)\\]")
        for (m in re.findAll(txt)) {
            res[m.groupValues[1]] = m.groupValues[2].split(",").filter { it.isNotBlank() }.map { it.trim().toDouble() }
        }
        return res
    }

    @Test
    fun wakeScoresMatchReference() {
        assumeTrue(testdata.isDirectory && File(testdata, "ref_scores.json").exists())
        val env = OrtEnvironment.getEnvironment()
        val ref = parseJson(File(testdata, "ref_scores.json"))
        for ((name, expected) in ref) {
            val (pcm, sr) = Wav.read(File(testdata, name))
            assertEquals(16000, sr)
            WakeWordEngine(
                env,
                File(models, "melspectrogram.onnx").readBytes(),
                File(models, "embedding_model.onnx").readBytes(),
                File(models, "hey_jarvis_v0.1.onnx").readBytes(),
            ).use { eng ->
                val got = ArrayList<Float>()
                var i = 0
                while (i + 1280 <= pcm.size) {
                    got.add(eng.process(pcm.copyOfRange(i, i + 1280))); i += 1280
                }
                assertEquals(expected.size, got.size)
                var maxDiff = 0.0
                // The first ~16 chunks depend on random warm-up noise (numpy vs java RNG), skip them.
                for (k in 20 until got.size) maxDiff = maxOf(maxDiff, Math.abs(got[k] - expected[k]))
                val maxGot = got.maxOrNull() ?: 0f
                val maxExp = expected.maxOrNull() ?: 0.0
                println("%-22s kotlinMax=%.4f refMax=%.4f maxAbsDiff(after warm-up)=%.5f avgMs=%.2f"
                    .format(name, maxGot, maxExp, maxDiff, eng.lastProcessNanos / 1e6))
                assertTrue("$name differs from reference by $maxDiff", maxDiff < 0.02)
                if (name.startsWith("pos")) assertTrue("$name should trigger", maxGot > 0.5f)
                if (name.startsWith("neg")) assertTrue("$name should not trigger", maxGot < 0.5f)
            }
        }
    }

    @Test
    fun vadMatchesReference() {
        assumeTrue(testdata.isDirectory && File(testdata, "ref_vad.json").exists())
        val env = OrtEnvironment.getEnvironment()
        val ref = parseJson(File(testdata, "ref_vad.json"))
        for ((name, expected) in ref) {
            val (pcm, _) = Wav.read(File(testdata, name))
            SileroVad(env, File(models, "silero_vad.onnx").readBytes()).use { vad ->
                var maxDiff = 0.0
                var k = 0
                var i = 0
                while (i + 640 <= pcm.size) {
                    val p = vad.process(pcm, i, 640)
                    maxDiff = maxOf(maxDiff, Math.abs(p - expected[k]))
                    k++; i += 640
                }
                println("%-22s VAD frames=%d maxAbsDiff=%.5f".format(name, k, maxDiff))
                assertTrue("$name VAD differs by $maxDiff", maxDiff < 0.01)
            }
        }
    }

    @Test
    fun wakePhraseStripping() {
        assertEquals("remind me to buy milk tomorrow", WakePhrase.strip("Hey Jarvis, remind me to buy milk tomorrow"))
        assertEquals("husk at købe mælk", WakePhrase.strip("hej Jarvis husk at købe mælk"))
        assertEquals("what time is it", WakePhrase.strip("Jarvis what time is it"))
        assertEquals("turn on the lights", WakePhrase.strip("turn on the lights"))
    }

    @Test
    fun ringBuffer() {
        val rb = PcmRingBuffer(5)
        rb.write(shortArrayOf(1, 2, 3))
        assertEquals(listOf<Short>(2, 3), rb.copyLast(2).toList())
        rb.write(shortArrayOf(4, 5, 6, 7))
        assertEquals(listOf<Short>(3, 4, 5, 6, 7), rb.copyLast(10).toList())
        assertEquals(7L, rb.totalWritten)
    }
}
