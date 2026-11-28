package dk.jarvisbot.app

import ai.onnxruntime.OrtEnvironment
import dk.jarvisbot.core.AudioMath
import dk.jarvisbot.core.SileroVad
import dk.jarvisbot.core.Wav
import dk.jarvisbot.core.WakeWordEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs fresh wake-word + VAD instances over a stored WAV (independent of the live microphone pipeline).
 * Answers: "does the engine recognise *this* audio as Hey Jarvis, and with what score?"
 */
object OfflineAnalyzer {
    fun analyze(app: JarvisApp, rec: DiagStore.RecordingRow): String {
        val (pcm, sr) = runCatching { DiagnosticFolder.readRecording(rec) }.getOrElse { return "Audio not available: ${it.message}" }
        if (sr != 16000) return "Unsupported sample rate $sr (expected 16000)"
        val t0 = System.currentTimeMillis()
        fun asset(n: String) = app.assets.open("models/$n").use { it.readBytes() }
        val env = OrtEnvironment.getEnvironment()
        // Like openWakeWord's predict_clip: pad with 1 s of silence before and after.
        val pad = ShortArray(16000)
        val audio = pad + pcm + pad
        val scores = ArrayList<Float>()
        val vads = ArrayList<Float>()
        val levels = ArrayList<Double>()
        WakeWordEngine(env, asset("melspectrogram.onnx"), asset("embedding_model.onnx"), asset("hey_jarvis_v0.1.onnx")).use { w ->
            SileroVad(env, asset("silero_vad.onnx")).use { v ->
                var i = 0
                while (i + 1280 <= audio.size) {
                    val c = audio.copyOfRange(i, i + 1280)
                    scores.add(w.process(c))
                    vads.add(maxOf(v.process(c, 0, 640), v.process(c, 640, 640)))
                    levels.add(AudioMath.rmsDbfs(c))
                    i += 1280
                }
            }
        }
        val thr = app.prefs.threshold
        val maxIdx = scores.indices.maxByOrNull { scores[it] } ?: -1
        val maxScore = if (maxIdx >= 0) scores[maxIdx] else 0f
        val above = scores.count { it >= thr }
        val speech = vads.count { it >= app.prefs.vadThreshold }
        val tMaxMs = maxIdx * 80L - 1000L // relative to the start of the recording (padding removed)
        val ms = System.currentTimeMillis() - t0
        val verdict = if (maxScore >= thr) "WOULD TRIGGER at threshold %.2f".format(thr) else "would NOT trigger at threshold %.2f".format(thr)
        val txt = "Recording: ${rec.file}\nDuration: ${rec.durationMs} ms\n" +
            "Max wake score: %.3f at %.2f s\nChunks ≥ threshold: %d\nSpeech chunks (VAD): %d of %d\nMax level: %.1f dBFS\n\n%s\n(analysis took %d ms)"
                .format(maxScore, tMaxMs / 1000.0, above, speech, scores.size, levels.maxOrNull() ?: -120.0, verdict, ms)
        app.store.log(
            "WAKE_OFFLINE_ANALYSIS", "max %.3f, %s".format(maxScore, verdict),
            JSONObject().put("maxScore", Math.round(maxScore * 1000) / 1000.0).put("maxAtMs", tMaxMs).put("threshold", thr.toDouble())
                .put("chunksAboveThreshold", above).put("speechChunks", speech).put("chunks", scores.size)
                .put("note", "traces include 1 s of zero padding before and after")
                .put("wakeScores", JSONArray(scores.map { Math.round(it * 1000) / 1000.0 }))
                .put("vad", JSONArray(vads.map { Math.round(it * 1000) / 1000.0 })),
            rec.interactionId, rec.id
        )
        return txt
    }
}
