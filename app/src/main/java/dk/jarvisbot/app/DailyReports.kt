package dk.jarvisbot.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Per-day diagnostic files, written to the diagnostic folder (<day>/...) and into ZIP exports:
 * - Daily_Diagnostics.json: device/app info, recording settings, storage, the day's listening sessions, interruptions,
 *   process exit reasons, recognition and transcription counts, automatic cleanups.
 * - Daily_Events.csv: the complete event log of that day.
 * - Daily_Recognitions.csv: every speech-recognition attempt of that day, including errors.
 * Days are local calendar days of the original timestamps.
 */
object DailyReports {
    const val DIAGNOSTICS = "Daily_Diagnostics.json"
    const val EVENTS = "Daily_Events.csv"
    const val RECOGNITIONS = "Daily_Recognitions.csv"

    private val dayFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    private val app get() = JarvisApp.instance

    private val LISTENING = setOf(
        "MICTEST_START_PRESSED", "MICTEST_SESSION_START", "MICTEST_SESSION_STOP", "MICTEST_STOP_PRESSED", "MICTEST_RAW_STARTED",
        "MICTEST_INTERRUPTED", "JARVIS_ENABLED", "JARVIS_DISABLED", "SERVICE_CREATED", "SERVICE_STOPPING", "SERVICE_DESTROYED",
    )
    private val INTERRUPTION_PREFIXES = listOf(
        "SERVICE_DESTROYED", "SERVICE_RESTARTED", "SERVICE_TRIM", "FGS_START_REQUEST_FAILED", "PROCESS_", "CRASH", "TASK_REMOVED",
        "MEMORY_", "SUSPECTED", "MICTEST_INTERRUPTED", "MICTEST_NO_AUDIO", "MICTEST_ONLY_ZEROS", "MICTEST_MIC_RESTART",
        "MICTEST_READ_ERROR", "MICTEST_CAPTURE_EXCEPTION", "MICTEST_FAILED", "MIC_DIGITAL_SILENCE", "MIC_PAUSED", "MIC_RESUMED",
        "MIC_READ", "MIC_INIT_FAILED", "MIC_START_FAILED", "AUDIO_STALL", "RECORDING_CLIENTS_CHANGED", "RECORDING_SAVE_FAILED",
        "TRANSCRIPTION_ENGINE_FAILED", "TRANSCRIPTION_ERROR", "DIAG_FOLDER_FAILED",
    )

    fun dayOf(ts: Long): String = dayFmt.get()!!.format(Date(ts))

    /** [start, end) of a local calendar day in epoch ms (correct across daylight-saving changes). */
    fun range(day: String): Pair<Long, Long> {
        val c = Calendar.getInstance()
        c.time = dayFmt.get()!!.parse(day)!!
        val start = c.timeInMillis
        c.add(Calendar.DAY_OF_MONTH, 1)
        return start to c.timeInMillis
    }

    private fun csv(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun writeEvents(day: String, os: OutputStream) {
        val (from, to) = range(day)
        os.write("time,type,message,recording_id,interaction_id,process,data\n".toByteArray())
        val sb = StringBuilder()
        app.store.forEachEventBetween(from, to) { e ->
            sb.setLength(0)
            sb.append(csv(DiagStore.iso(e.ts))).append(',').append(csv(e.type)).append(',').append(csv(e.msg)).append(',')
                .append(csv(e.recordingId)).append(',').append(csv(e.interactionId)).append(',').append(csv(e.proc)).append(',')
                .append(csv(e.data)).append('\n')
            os.write(sb.toString().toByteArray(Charsets.UTF_8))
        }
    }

    fun writeRecognitions(day: String, os: OutputStream) {
        val (from, to) = range(day)
        val header = listOf("time", "recording_id", "interaction_id", "source", "language", "engine", "engine_component", "on_device",
            "status", "error_code", "error_name", "latency_ms", "audio_binding", "text", "command_text", "unverified_text",
            "corrected_text", "alternatives", "confidences", "label")
        os.write((header.joinToString(",") + "\n").toByteArray())
        for (r in app.store.recognitionsBetween(from, to)) {
            val row = listOf(DiagStore.iso(r.tsStart), r.recordingId, r.interactionId, r.source, r.language, r.engine, r.engineComponent,
                r.onDevice, r.status, r.errorCode, r.errorName, r.latencyMs, r.audioBinding, r.text, r.commandText, r.unverifiedText,
                r.correctedText, r.alternatives, r.confidences, r.label)
            os.write((row.joinToString(",") { csv(it) } + "\n").toByteArray(Charsets.UTF_8))
        }
    }

    private fun eventJson(e: DiagStore.EventRow) = JSONObject().put("time", DiagStore.iso(e.ts)).put("type", e.type).put("message", e.msg)
        .put("data", e.data?.let { runCatching { JSONObject(it) }.getOrNull() ?: it })

    fun diagnosticsJson(ctx: Context, day: String): JSONObject {
        val store = app.store
        val (from, to) = range(day)
        val sessions = JSONArray()
        val interruptions = JSONArray()
        val cleanups = JSONArray()
        val typeCounts = sortedMapOf<String, Int>()
        var events = 0
        store.forEachEventBetween(from, to) { e ->
            events++
            typeCounts[e.type] = (typeCounts[e.type] ?: 0) + 1
            when {
                e.type in LISTENING -> sessions.put(eventJson(e))
                e.type.startsWith("STORAGE_") -> cleanups.put(eventJson(e))
                INTERRUPTION_PREFIXES.any { e.type.startsWith(it) } -> if (interruptions.length() < 2000) interruptions.put(eventJson(e))
            }
        }
        val recs = store.recordingsBetween(from, to)
        val byKind = JSONObject()
        recs.groupBy { it.kind + (it.category?.let { c -> "/$c" } ?: "") }.toSortedMap()
            .forEach { (k, v) -> byKind.put(k, JSONObject().put("count", v.size).put("durationMs", v.sumOf { it.durationMs }).put("bytes", v.sumOf { it.bytes })) }
        val delivered = store.deliveredBases().values.toHashSet()
        val rcg = store.recognitionsBetween(from, to)
        val tc = store.transcriptCounts(day)
        val exits = JSONArray()
        val all = Diagnostics.exitReasonsJson(ctx)
        for (i in 0 until all.length()) {
            val o = all.optJSONObject(i) ?: continue
            if (o.optLong("timestamp") in from until to) exits.put(o)
        }
        return JSONObject()
            .put("schema", "jarvis-daily-diagnostics-v1")
            .put("date", day)
            .put("timeZone", TimeZone.getDefault().id)
            .put("updated", DiagStore.iso(System.currentTimeMillis()))
            .put("note", "Refreshed periodically and when listening stops; the complete event log of the day is in $EVENTS.")
            .put("app", DiagnosticFolder.appInfo())
            .put("device", Diagnostics.deviceJson(ctx))
            .put("recordingSettings", recordingSettings())
            .put("allSettings", app.prefs.toJson())
            .put("storage", StorageManager.usageJson())
            .put("recordings", JSONObject().put("count", recs.size).put("byKind", byKind)
                .put("inDiagnosticFolder", recs.count { it.id in delivered })
                .put("phoneCopies", recs.count { store.fileFor(it).exists() }))
            .put("transcription", JSONObject().put("mode", app.prefs.transcriptionMode).put("engine", Transcription.ENGINE)
                .put("transcribed", tc[0]).put("withSpeech", tc[1]).put("withoutSpeech", tc[2]).put("errors", tc[3])
                .put("file", Transcription.fileName(day)))
            .put("recognitions", JSONObject().put("total", rcg.size).put("ok", rcg.count { it.status == "OK" })
                .put("notOk", rcg.count { it.status != "OK" })
                .put("byStatus", JSONObject().also { o -> rcg.groupBy { it.status ?: "?" }.forEach { (k, v) -> o.put(k, v.size) } })
                .put("file", RECOGNITIONS))
            .put("events", JSONObject().put("count", events).put("byType", JSONObject(typeCounts as Map<*, *>)).put("file", EVENTS))
            .put("listeningSessions", sessions)
            .put("interruptions", interruptions)
            .put("processExitReasons", exits)
            .put("automaticCleanup", cleanups)
    }

    fun recordingSettings(): JSONObject {
        val p = app.prefs
        return JSONObject()
            .put("microphone", "AudioRecord, MediaRecorder.AudioSource.MIC, 44.1 kHz mono 16-bit (48/16 kHz fallback), no app-level processing")
            .put("sensitivity", p.micSensitivity).put("thresholdDbfs", MicTest.thresholdDb(p.micSensitivity))
            .put("preRollMs", MicTest.PRE_ROLL_MS).put("hangoverMs", MicTest.HANGOVER_MS).put("hysteresisDb", MicTest.HYSTERESIS_DB)
            .put("maxPartSeconds", 60)
            .put("wakeWord", JSONObject().put("enabled", p.enabled).put("threshold", p.threshold.toDouble()).put("diagnosticMode", p.diagnosticMode)
                .put("audioSource", p.audioSource).put("language", p.language).put("recognizerMode", p.recognizerMode))
            .put("transcriptionMode", p.transcriptionMode)
            .put("storageLimitMb", p.storageLimitTotalMb)
            .put("diagnosticFolder", DiagnosticFolder.label())
    }
}
