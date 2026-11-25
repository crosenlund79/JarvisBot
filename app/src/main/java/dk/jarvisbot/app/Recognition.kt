package dk.jarvisbot.app

import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.system.Os
import android.system.OsConstants
import dk.jarvisbot.core.Wav
import dk.jarvisbot.core.WakePhrase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Speech recognition through android.speech.SpeechRecognizer.
 *
 * Recorded mode (default, Android 13+): the exact saved WAV is handed to the recognizer via EXTRA_AUDIO_SOURCE
 * (raw PCM after the 44-byte header). Afterwards the shared file offset tells us how many bytes the recognizer
 * actually read. If it read nothing, the transcript is NOT associated with the recording (stored as unverified).
 *
 * Live mode (manual test only): the recognizer opens the microphone itself; the shared pipeline is paused and
 * the transcript has no stored audio.
 */
object Recognition {
    data class Job(val recordingId: String?, val interactionId: String?, val source: String, val language: String, val live: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Job>()
    private var busy = false
    var onUpdate: (() -> Unit)? = null

    fun enqueue(recordingId: String, interactionId: String?, source: String, language: String) =
        main.post { queue.addLast(Job(recordingId, interactionId, source, language, false)); next() }

    fun enqueueLive(language: String) = main.post { queue.addLast(Job(null, null, "MANUAL_LIVE", language, true)); next() }

    private fun next() {
        if (busy) return
        val job = queue.removeFirstOrNull() ?: return
        busy = true
        try {
            run(job)
        } catch (t: Throwable) {
            JarvisApp.instance.store.log("RECOGNITION_ERROR", "exception: $t",
                JSONObject().put("stack", t.stackTraceToString().take(4000)), job.interactionId, job.recordingId)
            busy = false
            if (job.live) ListenerService.instance?.resumeAfterLiveRecognition()
            main.post { next() }
        }
    }

    fun errorName(code: Int): String = when (code) {
        1 -> "ERROR_NETWORK_TIMEOUT"; 2 -> "ERROR_NETWORK"; 3 -> "ERROR_AUDIO"; 4 -> "ERROR_SERVER"; 5 -> "ERROR_CLIENT"
        6 -> "ERROR_SPEECH_TIMEOUT"; 7 -> "ERROR_NO_MATCH"; 8 -> "ERROR_RECOGNIZER_BUSY"; 9 -> "ERROR_INSUFFICIENT_PERMISSIONS"
        10 -> "ERROR_TOO_MANY_REQUESTS"; 11 -> "ERROR_SERVER_DISCONNECTED"; 12 -> "ERROR_LANGUAGE_NOT_SUPPORTED"
        13 -> "ERROR_LANGUAGE_UNAVAILABLE"; 14 -> "ERROR_CANNOT_CHECK_SUPPORT"; 15 -> "ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
        else -> "ERROR_$code"
    }

    private class Engine(val recognizer: SpeechRecognizer, val name: String, val component: String?, val onDevice: Int?)

    /** Creates the recognizer for the configured mode, or returns an error message. */
    private fun createEngine(mode: String): Pair<Engine?, String?> {
        val ctx = JarvisApp.instance
        return if (mode == "ON_DEVICE") {
            if (Build.VERSION.SDK_INT < 31) return null to "On-device recognizer API requires Android 12+. Switch recognizer mode to DEFAULT_SERVICE (may use network) in Settings to try the system recognizer."
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) return null to "No on-device speech recognizer available on this device. Switch recognizer mode to DEFAULT_SERVICE (may use network) in Settings to try the system recognizer."
            Engine(SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx), "android-on-device", Diagnostics.onDeviceServiceConfig() ?: Diagnostics.UNAVAILABLE, 1) to null
        } else {
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return null to "No speech recognition service installed."
            val comp = runCatching { Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service") }.getOrNull()
            Engine(SpeechRecognizer.createSpeechRecognizer(ctx), "android-default-service (may use network)", comp ?: Diagnostics.UNAVAILABLE, null) to null
        }
    }

    private fun baseIntent(language: String, mode: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, JarvisApp.instance.packageName)
        if (mode == "ON_DEVICE") putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    private fun run(job: Job) {
        val app = JarvisApp.instance
        val store = app.store
        val prefs = app.prefs
        val mode = prefs.recognizerMode
        val tStart = System.currentTimeMillis()
        val rec = job.recordingId?.let { store.recording(it) }
        val file: File? = rec?.let { store.fileFor(it) }
        val meta = JSONObject().put("recognizerMode", mode).put("sdkInt", Build.VERSION.SDK_INT).put("live", job.live)
        if (rec != null) meta.put("recordingKind", rec.kind).put("recordingEndTs", rec.tsEnd).put("recordingDurationMs", rec.durationMs)

        fun fail(status: String, msg: String, engine: String = "none", component: String? = null, onDevice: Int? = null) {
            val row = DiagStore.RecognitionRow(
                DiagStore.newId("rcg"), job.recordingId, job.interactionId, tStart, System.currentTimeMillis(), 0, job.source,
                engine, component, onDevice, job.language, status, null, msg, null, null, null, null, null,
                if (job.live) "LIVE_MIC_NO_STORED_AUDIO" else "NOT_ATTEMPTED", null, null, null, null, meta.toString()
            )
            store.runOnWriter { store.insertRecognition(row) }
            store.log("RECOGNITION_ERROR", "$status: $msg", meta, job.interactionId, job.recordingId)
            LiveStatus.recognitionStatus = "$status: $msg"
            LiveStatus.lastCommand = "✗ $status"
            busy = false
            if (job.live) ListenerService.instance?.resumeAfterLiveRecognition()
            onUpdate?.invoke()
            main.post { next() }
        }

        if (!job.live) {
            if (rec == null || file == null || !file.exists()) return fail("NO_AUDIO_FILE", "Recording ${job.recordingId} not found (deleted by storage limit?)")
            if (Build.VERSION.SDK_INT < 33) return fail("UNSUPPORTED_ANDROID_VERSION",
                "Recognizing recorded audio needs Android 13+ (RecognizerIntent.EXTRA_AUDIO_SOURCE). Use the live microphone test instead.")
        }
        val (engine, err) = createEngine(mode)
        if (engine == null) return fail(if (mode == "ON_DEVICE") "ON_DEVICE_UNAVAILABLE" else "RECOGNIZER_UNAVAILABLE", err ?: "unavailable")

        val intent = baseIntent(job.language, mode)
        var pfd: ParcelFileDescriptor? = null
        var totalBytes: Long? = null
        if (!job.live) {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            Os.lseek(pfd.fileDescriptor, Wav.HEADER_BYTES.toLong(), OsConstants.SEEK_SET)
            totalBytes = file!!.length() - Wav.HEADER_BYTES
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
        }
        val otherStartsBefore = LiveStatus.otherClientStarts
        val timeline = JSONArray()
        var done = false
        fun t() = System.currentTimeMillis() - tStart

        fun complete(status: String, errorCode: Int?, results: Bundle?) {
            if (done) return
            done = true
            main.removeCallbacksAndMessages(engine)
            val tEnd = System.currentTimeMillis()
            var consumed: Long? = null
            val binding: String
            if (job.live) {
                binding = "LIVE_MIC_NO_STORED_AUDIO"
            } else {
                consumed = runCatching { Os.lseek(pfd!!.fileDescriptor, 0, OsConstants.SEEK_CUR) - Wav.HEADER_BYTES }.getOrNull()
                binding = when {
                    consumed == null -> "UNKNOWN"
                    consumed <= 0 -> "NOT_READ"
                    consumed >= (totalBytes ?: Long.MAX_VALUE) -> "FULL"
                    else -> "PARTIAL"
                }
            }
            val otherMic = LiveStatus.otherClientStarts > otherStartsBefore
            meta.put("timeline", timeline).put("otherMicClientStartedDuringRecognition", otherMic)
            if (rec != null) meta.put("commandEndToResultMs", tEnd - rec.tsEnd)
            runCatching { pfd?.close() }
            runCatching { engine.recognizer.destroy() }

            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            val conf = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            val top = texts.firstOrNull()
            // Only transcripts proven to come from this audio (or from an explicit live test) are associated.
            val verified = binding == "FULL" || binding == "PARTIAL" || binding == "LIVE_MIC_NO_STORED_AUDIO"
            var finalStatus = status
            if (status == "OK" && texts.isEmpty()) finalStatus = "NO_RESULT"
            if (finalStatus == "OK" && !verified) finalStatus = "OK_AUDIO_NOT_VERIFIED"
            val cmdText = if (verified && top != null) (if (rec?.kind == "COMMAND") WakePhrase.strip(top) else top) else null
            val row = DiagStore.RecognitionRow(
                DiagStore.newId("rcg"), job.recordingId, job.interactionId, tStart, tEnd, tEnd - tStart, job.source,
                engine.name, engine.component, engine.onDevice, job.language, finalStatus, errorCode,
                errorCode?.let { errorName(it) }, if (verified) top else null, cmdText, if (!verified) top else null,
                JSONArray(texts).toString(), conf?.let { c -> JSONArray(c.map { it.toDouble() }).toString() },
                binding, consumed, totalBytes, null, null, meta.toString()
            )
            store.runOnWriter { store.insertRecognition(row) }
            val summary = when {
                finalStatus == "OK" -> "“${cmdText ?: top}”"
                finalStatus == "OK_AUDIO_NOT_VERIFIED" -> "⚠ result NOT from stored audio (binding $binding): “$top”"
                else -> "$finalStatus ${errorCode?.let { errorName(it) } ?: ""}"
            }
            store.log(
                if (finalStatus == "OK") "RECOGNITION_RESULT" else "RECOGNITION_ERROR",
                "$summary (${tEnd - tStart} ms, ${job.language}, ${engine.name}, binding $binding)",
                JSONObject().put("status", finalStatus).put("latencyMs", tEnd - tStart).put("binding", binding)
                    .put("bytesConsumed", consumed).put("bytesTotal", totalBytes).put("alternatives", JSONArray(texts))
                    .put("errorCode", errorCode).put("engine", engine.name).put("component", engine.component),
                job.interactionId, job.recordingId
            )
            LiveStatus.recognitionStatus = "done: $finalStatus"
            LiveStatus.lastCommand = "$summary [${job.language}, ${tEnd - tStart} ms]"
            busy = false
            if (job.live) ListenerService.instance?.resumeAfterLiveRecognition()
            onUpdate?.invoke()
            main.post { next() }
        }

        engine.recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { timeline.put("ready@${t()}") }
            override fun onBeginningOfSpeech() { timeline.put("beginSpeech@${t()}") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { timeline.put("endSpeech@${t()}") }
            override fun onError(error: Int) { timeline.put("error:${errorName(error)}@${t()}"); complete("ERROR", error, null) }
            override fun onResults(results: Bundle?) { timeline.put("results@${t()}"); complete("OK", null, results) }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) { timeline.put("event$eventType@${t()}") }
        })

        LiveStatus.recognitionStatus = "recognizing ${if (job.live) "LIVE mic" else job.recordingId} (${job.language}, ${engine.name})"
        store.log(
            "RECOGNITION_STARTED", "${engine.name} ${job.language} ${if (job.live) "live microphone" else "recorded audio $totalBytes bytes"}",
            JSONObject().put("engine", engine.name).put("component", engine.component).put("language", job.language)
                .put("mode", mode).put("source", job.source).put("live", job.live).put("bytes", totalBytes),
            job.interactionId, job.recordingId
        )
        onUpdate?.invoke()

        val start = Runnable {
            try {
                engine.recognizer.startListening(intent)
            } catch (t: Throwable) {
                timeline.put("startListening exception: $t")
                complete("EXCEPTION", null, null)
            }
        }
        if (job.live) {
            ListenerService.instance?.pauseForLiveRecognition()
            main.postAtTime(start, engine, android.os.SystemClock.uptimeMillis() + 400)
        } else {
            start.run()
        }
        main.postAtTime({
            if (!done) {
                timeline.put("timeout@${t()}")
                runCatching { engine.recognizer.cancel() }
                complete("TIMEOUT", null, null)
            }
        }, engine, android.os.SystemClock.uptimeMillis() + prefs.recognitionTimeoutMs)
    }

    /** Android 13+: asks the recognizer which languages are supported / installed on-device. */
    fun checkSupport(language: String, cb: (String) -> Unit) {
        val app = JarvisApp.instance
        if (Build.VERSION.SDK_INT < 33) { cb("checkRecognitionSupport requires Android 13+ (unavailable)"); return }
        val mode = app.prefs.recognizerMode
        val (engine, err) = createEngine(mode)
        if (engine == null) { cb("Cannot check: $err"); return }
        val intent = baseIntent(language, mode)
        try {
            engine.recognizer.checkRecognitionSupport(intent, app.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) {
                    val txt = "Engine: ${engine.name} (${engine.component})\n" +
                        "$language installed on-device: ${s.installedOnDeviceLanguages.any { it.equals(language, true) }}\n" +
                        "$language pending download: ${s.pendingOnDeviceLanguages.any { it.equals(language, true) }}\n" +
                        "$language supported on-device: ${s.supportedOnDeviceLanguages.any { it.equals(language, true) }}\n" +
                        "$language online: ${s.onlineLanguages.any { it.equals(language, true) }}\n" +
                        "installed=${s.installedOnDeviceLanguages}\npending=${s.pendingOnDeviceLanguages}\n" +
                        "supportedOnDevice=${s.supportedOnDeviceLanguages}\nonline=${s.onlineLanguages}"
                    app.store.log("RECOGNITION_SUPPORT", "$language via ${engine.name}", JSONObject()
                        .put("installed", JSONArray(s.installedOnDeviceLanguages)).put("pending", JSONArray(s.pendingOnDeviceLanguages))
                        .put("supportedOnDevice", JSONArray(s.supportedOnDeviceLanguages)).put("online", JSONArray(s.onlineLanguages))
                        .put("engine", engine.name).put("component", engine.component))
                    runCatching { engine.recognizer.destroy() }
                    cb(txt)
                }

                override fun onError(error: Int) {
                    app.store.log("RECOGNITION_SUPPORT_ERROR", errorName(error), JSONObject().put("engine", engine.name))
                    runCatching { engine.recognizer.destroy() }
                    cb("checkRecognitionSupport failed: ${errorName(error)} (engine ${engine.name})")
                }
            })
        } catch (t: Throwable) {
            runCatching { engine.recognizer.destroy() }
            cb("checkRecognitionSupport exception: $t")
        }
    }

    /** Android 13+: asks the on-device recognizer to download the model for [language]. */
    fun triggerDownload(language: String, cb: (String) -> Unit) {
        val app = JarvisApp.instance
        if (Build.VERSION.SDK_INT < 33) { cb("triggerModelDownload requires Android 13+ (unavailable)"); return }
        val (engine, err) = createEngine("ON_DEVICE")
        if (engine == null) { cb("Cannot request download: $err"); return }
        try {
            engine.recognizer.triggerModelDownload(baseIntent(language, "ON_DEVICE"))
            app.store.log("RECOGNITION_MODEL_DOWNLOAD_REQUESTED", language, JSONObject().put("engine", engine.name))
            cb("Download requested for $language. The system decides whether/when it downloads (may need Wi-Fi). Re-check support later.")
        } catch (t: Throwable) {
            cb("triggerModelDownload failed: $t")
        }
        main.postDelayed({ runCatching { engine.recognizer.destroy() } }, 5000)
    }
}
