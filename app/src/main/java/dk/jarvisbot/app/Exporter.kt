package dk.jarvisbot.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds the diagnostics ZIP. Nothing is transferred anywhere; the user picks the destination. */
object Exporter {

    fun fileName(): String = "jarvis-diagnostics-" +
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".zip"

    /** Writes the ZIP; returns a short summary. */
    fun write(ctx: Context, out: OutputStream, includeAudio: Boolean = true): String {
        val app = JarvisApp.instance
        val store = app.store
        var audioFiles = 0
        var events = 0
        ZipOutputStream(out.buffered()).use { zip ->
            fun text(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }

            text("README.txt", readme())
            text("device.json", Diagnostics.deviceJson(ctx).toString(2))
            text("settings.json", app.prefs.toJson().toString(2))
            text("exit_reasons.json", Diagnostics.exitReasonsJson(ctx).toString(2))
            text("storage.json", JSONObject().put("audioBytes", store.audioBytes()).put("dbBytes", store.dbFileBytes())
                .put("byKind", JSONObject().also { o -> store.countsByKind().forEach { (k, v) -> o.put(k, JSONObject().put("count", v.first).put("bytes", v.second)) } })
                .put("storageLimitMb", app.prefs.storageLimitMb).toString(2))

            // recordings
            val recs = store.recordings(null, Int.MAX_VALUE)
            text("recordings.json", JSONArray().also { a -> recs.forEach { a.put(it.toJson()) } }.toString(1))
            text("recordings.csv", csv(
                listOf("id", "ts_start_iso", "ts_start", "ts_end", "kind", "category", "file", "bytes", "duration_ms", "interaction_id", "max_wake_score", "label", "note"),
                recs.map { listOf(it.id, DiagStore.iso(it.tsStart), it.tsStart, it.tsEnd, it.kind, it.category, "audio/${it.file}", it.bytes, it.durationMs, it.interactionId, it.maxScore, it.label, it.note) }
            ))

            // recognitions / commands / corrections
            val rcgs = store.recognitions(Int.MAX_VALUE)
            text("recognitions.json", JSONArray().also { a -> rcgs.forEach { a.put(it.toJson()) } }.toString(1))
            val rcgHeader = listOf("id", "ts_start_iso", "recording_id", "interaction_id", "source", "language", "engine", "engine_component", "on_device",
                "status", "error_code", "error_name", "latency_ms", "audio_binding", "bytes_consumed", "bytes_total", "text", "command_text",
                "unverified_text", "corrected_text", "alternatives", "confidences", "label")
            fun rcgRow(r: DiagStore.RecognitionRow) = listOf(r.id, DiagStore.iso(r.tsStart), r.recordingId, r.interactionId, r.source, r.language,
                r.engine, r.engineComponent, r.onDevice, r.status, r.errorCode, r.errorName, r.latencyMs, r.audioBinding, r.bytesConsumed,
                r.bytesTotal, r.text, r.commandText, r.unverifiedText, r.correctedText, r.alternatives, r.confidences, r.label)
            text("recognitions.csv", csv(rcgHeader, rcgs.map { rcgRow(it) }))
            text("recognition_errors.csv", csv(rcgHeader, rcgs.filter { it.status != "OK" }.map { rcgRow(it) }))
            text("corrections.csv", csv(listOf("recognition_id", "recording_id", "language", "recognized_text", "command_text", "corrected_text"),
                rcgs.filter { it.correctedText != null }.map { listOf(it.id, it.recordingId, it.language, it.text, it.commandText, it.correctedText) }))
            val cmdRecs = recs.filter { it.kind == "COMMAND" }
            text("commands.csv", csv(
                listOf("interaction_id", "command_recording_id", "recorded_at", "audio_file", "duration_ms", "end_reason", "wake_score",
                    "recording_label", "recognition_id", "language", "engine", "status", "latency_ms", "audio_binding", "recognized_text",
                    "command_text", "corrected_text", "recognition_label"),
                cmdRecs.flatMap { c ->
                    val rs = rcgs.filter { it.recordingId == c.id }
                    if (rs.isEmpty()) listOf(listOf(c.interactionId, c.id, DiagStore.iso(c.tsStart), "audio/${c.file}", c.durationMs, c.category, c.maxScore, c.label))
                    else rs.map { r -> listOf(c.interactionId, c.id, DiagStore.iso(c.tsStart), "audio/${c.file}", c.durationMs, c.category, c.maxScore, c.label,
                        r.id, r.language, r.engine, r.status, r.latencyMs, r.audioBinding, r.text, r.commandText, r.correctedText, r.label) }
                }
            ))

            // events: full JSONL + CSV + topic subsets
            zip.putNextEntry(ZipEntry("events.jsonl"))
            store.rawQuery("SELECT id, ts, type, msg, data, interaction_id, recording_id, proc FROM events ORDER BY id").use { c ->
                while (c.moveToNext()) {
                    val o = JSONObject().put("id", c.getLong(0)).put("ts", c.getLong(1)).put("iso", DiagStore.iso(c.getLong(1)))
                        .put("type", c.getString(2)).put("msg", c.getString(3))
                        .put("data", c.getString(4)?.let { runCatching { JSONObject(it) }.getOrNull() ?: it })
                        .put("interaction_id", c.getString(5)).put("recording_id", c.getString(6)).put("process", c.getString(7))
                    zip.write((o.toString() + "\n").toByteArray(Charsets.UTF_8))
                    events++
                }
            }
            zip.closeEntry()
            val subsets = linkedMapOf(
                "events.csv" to "1=1",
                "mic_test_events.csv" to "type LIKE 'MICTEST%' OR type LIKE 'FGS%' OR type LIKE 'SERVICE%' OR type LIKE 'HEARTBEAT%' OR type LIKE 'MIC_CLIENT%' OR type LIKE 'RECORDING_CLIENTS%' OR type LIKE 'AUDIO_MODE%' OR type LIKE 'DEVICE_%' OR type LIKE 'PROCESS%' OR type LIKE 'SUSPECTED%' OR type LIKE 'CRASH%' OR type LIKE 'WAKELOCK%' OR type LIKE 'PERMISSION%' OR type LIKE 'STORAGE%'",
                "wake_events.csv" to "type LIKE 'WAKE%' OR type LIKE 'USER_MARKED%' OR type LIKE 'ACK_BEEP%' OR type LIKE 'SPEECH_REGION%' OR type LIKE 'COMMAND_%'",
                "microphone_events.csv" to "type LIKE 'MIC_%' OR type LIKE 'AUDIO_%' OR type='LEVEL' OR type LIKE 'RECORDING_CLIENTS%' OR type LIKE 'RAW_%' OR type LIKE 'MANUAL_%'",
                "service_events.csv" to "type LIKE 'SERVICE%' OR type LIKE 'FGS%' OR type LIKE 'HEARTBEAT%' OR type LIKE 'PROCESS%' OR type LIKE 'SUSPECTED%' OR type LIKE 'CRASH%' OR type LIKE 'JARVIS_%' OR type LIKE 'WAKELOCK%' OR type LIKE 'DEVICE_%' OR type LIKE 'TASK_%' OR type LIKE 'MEMORY%' OR type LIKE 'SERVICE_TRIM%'",
                "recognition_events.csv" to "type LIKE 'RECOGNITION%'",
                "errors.csv" to "type LIKE '%ERROR%' OR type LIKE '%FAIL%' OR type LIKE '%SILENCE%' OR type LIKE '%SUSPECT%' OR type LIKE '%GAP%' OR type LIKE '%STALL%' OR type LIKE '%CRASH%' OR type LIKE '%EXIT%' OR type LIKE '%INTERRUPT%' OR type LIKE '%DENIED%'",
            )
            for ((name, where) in subsets) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("id,ts,iso,type,msg,interaction_id,recording_id,process,data\n".toByteArray())
                store.rawQuery("SELECT id, ts, type, msg, data, interaction_id, recording_id, proc FROM events WHERE $where ORDER BY id").use { c ->
                    while (c.moveToNext()) {
                        val line = listOf(c.getLong(0), c.getLong(1), DiagStore.iso(c.getLong(1)), c.getString(2), c.getString(3),
                            c.getString(5), c.getString(6), c.getString(7), c.getString(4)).joinToString(",") { esc(it) } + "\n"
                        zip.write(line.toByteArray(Charsets.UTF_8))
                    }
                }
                zip.closeEntry()
            }

            if (includeAudio) {
                for (r in recs) {
                    val f = store.fileFor(r)
                    if (!f.exists()) continue
                    zip.putNextEntry(ZipEntry("audio/${r.file}").apply { method = ZipEntry.STORED; size = f.length(); compressedSize = f.length(); crc = crc32(f) })
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    audioFiles++
                }
            }
        }
        val summary = "Exported $audioFiles audio files, $events events"
        store.log("EXPORT_CREATED", summary)
        return summary
    }

    private fun crc32(f: File): Long {
        val crc = java.util.zip.CRC32()
        f.inputStream().use { s -> val b = ByteArray(65536); while (true) { val n = s.read(b); if (n < 0) break; crc.update(b, 0, n) } }
        return crc.value
    }

    private fun esc(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun csv(header: List<String>, rows: List<List<Any?>>): String {
        val sb = StringBuilder(header.joinToString(",")).append('\n')
        for (r in rows) sb.append(r.joinToString(",") { esc(it) }).append('\n')
        return sb.toString()
    }

    private fun readme() = """
        Jarvis Prototype 001 – diagnostics export
        ==========================================
        Created: ${DiagStore.iso(System.currentTimeMillis())}
        App: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}, git ${BuildConfig.GIT_SHA}, built ${BuildConfig.BUILD_TIME})

        MICROPHONE TEST (kind MICTEST): every sample Android delivered between Start and Stop, unmodified, in 60 s WAV parts
        (standard MIC source, normally 44.1 kHz). recordings.csv: category AUDIO / ALL_ZEROS / EMPTY, meta has per-file
        rms/peak/zero counts; interaction_id = test session id. mic_test_events.csv: MICTEST_SESSION_START/STOP (with a verdict),
        MICTEST_MIC_OPENED / MIC_OPEN_FAILED (every configuration tried), MICTEST_STATUS every 5 s (samples arriving?,
        % of expected samples, level, all-zero?), MICTEST_NO_AUDIO, MICTEST_ONLY_ZEROS, MICTEST_READ_ERROR, MICTEST_MIC_RESTART,
        MICTEST_INTERRUPTED, plus service/heartbeat/screen events.

        All timestamps: epoch milliseconds (ts) plus ISO-8601 local time (iso).
        interaction_id links one wake activation -> WAKE clip -> COMMAND recording -> recognition(s).
        recording_id links events/recognitions to a WAV in audio/.

        Files
        - device.json            Android version, manufacturer/model, app version/build, engines + model SHA-256, recognizer services,
                                 permissions, power/battery state at export time.
        - settings.json          All app settings (thresholds, timeouts, audio source, recognizer mode, ...).
        - exit_reasons.json      Android's own record of why previous app processes died (Android 11+).
        - recordings.json/.csv   Every stored WAV with kind/category/label and per-80ms traces (wake score, VAD, level) in meta.
                                 kinds: MICTEST (microphone test, see above), WAKE (audio before activation), COMMAND (pre-roll + command), SEGMENT (diagnostic-mode
                                 speech/candidate regions: TRIGGERED / CANDIDATE / SPEECH / OTHER), MANUAL, RAW, MISSED_MARK.
        - recognitions.json/.csv Every recognition attempt. audio_binding:
                                   FULL / PARTIAL = recognizer read this many bytes of the stored audio (verified association)
                                   NOT_READ       = recognizer did not read the stored audio -> transcript kept only in unverified_text
                                   LIVE_MIC_NO_STORED_AUDIO = manual live-microphone test, no stored audio
        - commands.csv           One row per command recording (+ its recognitions, corrections and labels).
        - corrections.csv        Manually corrected transcripts.
        - recognition_errors.csv Recognition attempts that did not return a verified result.
        - events.jsonl/.csv      Complete technical event log. Subsets: wake_events, microphone_events, service_events,
                                 recognition_events, errors.
        - audio/                 16 kHz mono 16-bit PCM WAV files.

        Useful event types
        LEVEL (periodic mic level/VAD/wake stats), HEARTBEAT, WAKE_CANDIDATE, WAKE_DETECTED, WAKE_SUPPRESSED, SPEECH_REGION,
        COMMAND_RECORDING_STARTED/STOPPED, RECOGNITION_*, MIC_STARTED/INTERRUPTED/READ_ERROR, MIC_DIGITAL_SILENCE_*,
        MIC_CLIENT_SILENCED, RECORDING_CLIENTS_CHANGED, AUDIO_GAP, AUDIO_STALL, AUDIO_MODE_CHANGED, SERVICE_*, FGS_*,
        SUSPECTED_UNCLEAN_TERMINATION, PROCESS_EXIT_REASON, PROCESS_START, CRASH, DEVICE_SCREEN_ON/OFF.
        Values that Android does not provide are written as "unavailable".
    """.trimIndent()
}

/** Minimal read-only provider so the share sheet can access exported ZIPs in cache/exports. */
class ExportProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "dk.jarvisbot.app.exports"
        fun uriFor(f: File): Uri = Uri.parse("content://$AUTHORITY/${f.name}")
    }

    private fun fileFor(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.contains("..")) return null
        val f = File(File(context!!.cacheDir, "exports"), name)
        return if (f.exists()) f else null
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/zip"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        fileFor(uri)?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        val values: Array<Any?> = cols.map<String, Any?> {
            when (it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null }
        }.toTypedArray()
        c.addRow(values)
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
