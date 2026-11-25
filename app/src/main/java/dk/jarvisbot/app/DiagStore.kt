package dk.jarvisbot.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Local-only persistence: SQLite for events / recordings / recognitions, WAV files in filesDir/audio.
 * Lives in app-internal storage, so it survives app restarts and updates (removed only on uninstall).
 */
class DiagStore(private val ctx: Context) : SQLiteOpenHelper(ctx, "jarvis_diag.db", null, 1) {

    val audioDir: File = File(ctx.filesDir, "audio").apply { mkdirs() }
    /** Random id per process start; lets us see process restarts in the event log. */
    val processSession: String = UUID.randomUUID().toString().substring(0, 8)
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "diag-writer") }
    private val insertCount = AtomicLong()

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, type TEXT NOT NULL,
               msg TEXT, data TEXT, interaction_id TEXT, recording_id TEXT, proc TEXT)"""
        )
        db.execSQL("CREATE INDEX ev_ts ON events(ts)")
        db.execSQL("CREATE INDEX ev_type ON events(type)")
        db.execSQL(
            """CREATE TABLE recordings(id TEXT PRIMARY KEY, ts_start INTEGER, ts_end INTEGER, kind TEXT, category TEXT,
               file TEXT, bytes INTEGER, duration_ms INTEGER, sample_rate INTEGER, interaction_id TEXT,
               max_score REAL, label TEXT, note TEXT, meta TEXT)"""
        )
        db.execSQL("CREATE INDEX rec_ts ON recordings(ts_start)")
        db.execSQL(
            """CREATE TABLE recognitions(id TEXT PRIMARY KEY, recording_id TEXT, interaction_id TEXT, ts_start INTEGER,
               ts_end INTEGER, latency_ms INTEGER, source TEXT, engine TEXT, engine_component TEXT, on_device INTEGER,
               language TEXT, status TEXT, error_code INTEGER, error_name TEXT, text TEXT, command_text TEXT,
               unverified_text TEXT, alternatives TEXT, confidences TEXT, audio_binding TEXT, bytes_consumed INTEGER,
               bytes_total INTEGER, corrected_text TEXT, label TEXT, meta TEXT)"""
        )
        db.execSQL("CREATE INDEX rcg_ts ON recognitions(ts_start)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ------------------------------------------------------------------ events

    fun log(
        type: String,
        msg: String = "",
        data: JSONObject? = null,
        interactionId: String? = null,
        recordingId: String? = null,
        sync: Boolean = false,
    ) {
        val ts = System.currentTimeMillis()
        Log.i("Jarvis", "$type $msg ${data ?: ""}")
        val op = Runnable {
            try {
                val cv = ContentValues().apply {
                    put("ts", ts); put("type", type); put("msg", msg); put("data", data?.toString())
                    put("interaction_id", interactionId); put("recording_id", recordingId); put("proc", processSession)
                }
                writableDatabase.insert("events", null, cv)
                if (insertCount.incrementAndGet() % 2000 == 0L) pruneEvents(MAX_EVENTS)
            } catch (e: Exception) {
                Log.e("Jarvis", "event insert failed", e)
            }
        }
        if (sync) op.run() else writer.execute(op)
    }

    fun runOnWriter(r: () -> Unit) = writer.execute { try { r() } catch (e: Exception) { Log.e("Jarvis", "writer task failed", e) } }

    private fun pruneEvents(max: Int) {
        val db = writableDatabase
        val n = count("SELECT COUNT(*) FROM events")
        if (n > max) {
            val del = n - (max * 0.9).toLong()
            db.execSQL("DELETE FROM events WHERE id IN (SELECT id FROM events ORDER BY id ASC LIMIT $del)")
        }
    }

    data class EventRow(val id: Long, val ts: Long, val type: String, val msg: String?, val data: String?,
                        val interactionId: String?, val recordingId: String?, val proc: String?)

    fun events(types: List<String>?, limit: Int, errorsOnly: Boolean = false): List<EventRow> {
        val where = StringBuilder()
        val args = ArrayList<String>()
        if (!types.isNullOrEmpty()) {
            where.append("(" + types.joinToString(" OR ") { "type LIKE ?" } + ")")
            types.forEach { args.add("$it%") }
        }
        if (errorsOnly) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("(type LIKE '%ERROR%' OR type LIKE '%FAIL%' OR type LIKE '%SILENCE%' OR type LIKE '%SUSPECT%' OR type LIKE '%GAP%' OR type LIKE '%SILENCED%' OR type LIKE '%CRASH%' OR type LIKE '%EXIT%')")
        }
        val sql = "SELECT id, ts, type, msg, data, interaction_id, recording_id, proc FROM events" +
            (if (where.isNotEmpty()) " WHERE $where" else "") + " ORDER BY id DESC LIMIT $limit"
        val res = ArrayList<EventRow>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) res.add(
                EventRow(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7))
            )
        }
        return res
    }

    // -------------------------------------------------------------- recordings

    data class RecordingRow(
        val id: String, val tsStart: Long, val tsEnd: Long, val kind: String, val category: String?,
        val file: String, val bytes: Long, val durationMs: Long, val sampleRate: Int, val interactionId: String?,
        val maxScore: Double?, val label: String?, val note: String?, val meta: String?,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("ts_start", tsStart); put("ts_start_iso", iso(tsStart)); put("ts_end", tsEnd)
            put("kind", kind); put("category", category); put("file", "audio/$file"); put("bytes", bytes)
            put("duration_ms", durationMs); put("sample_rate", sampleRate); put("interaction_id", interactionId)
            put("max_wake_score", maxScore); put("label", label); put("note", note)
            put("meta", meta?.let { runCatching { JSONObject(it) }.getOrNull() ?: it })
        }
    }

    fun fileFor(r: RecordingRow) = File(audioDir, r.file)

    fun insertRecording(r: RecordingRow) {
        val cv = ContentValues().apply {
            put("id", r.id); put("ts_start", r.tsStart); put("ts_end", r.tsEnd); put("kind", r.kind); put("category", r.category)
            put("file", r.file); put("bytes", r.bytes); put("duration_ms", r.durationMs); put("sample_rate", r.sampleRate)
            put("interaction_id", r.interactionId); put("max_score", r.maxScore); put("label", r.label); put("note", r.note); put("meta", r.meta)
        }
        writableDatabase.insertWithOnConflict("recordings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun setRecordingLabel(id: String, label: String?, note: String?) {
        val cv = ContentValues().apply { put("label", label); if (note != null) put("note", note) }
        writableDatabase.update("recordings", cv, "id=?", arrayOf(id))
    }

    /** Applies a label to every recording that belongs to an interaction (WAKE clip, COMMAND, triggered SEGMENT). */
    fun labelInteraction(interactionId: String, label: String?) {
        writableDatabase.update("recordings", ContentValues().apply { put("label", label) }, "interaction_id=?", arrayOf(interactionId))
    }

    private fun recFrom(c: Cursor) = RecordingRow(
        c.getString(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4), c.getString(5), c.getLong(6),
        c.getLong(7), c.getInt(8), c.getString(9), if (c.isNull(10)) null else c.getDouble(10), c.getString(11),
        c.getString(12), c.getString(13)
    )

    private val recCols = "id, ts_start, ts_end, kind, category, file, bytes, duration_ms, sample_rate, interaction_id, max_score, label, note, meta"

    fun recordings(kind: String?, limit: Int): List<RecordingRow> {
        val res = ArrayList<RecordingRow>()
        val (where, args) = when {
            kind == null -> "" to arrayOf()
            kind.startsWith("LABEL:") -> " WHERE label=?" to arrayOf(kind.removePrefix("LABEL:"))
            kind.contains("/") -> " WHERE kind=? AND category=?" to arrayOf(kind.substringBefore("/"), kind.substringAfter("/"))
            else -> " WHERE kind=?" to arrayOf(kind)
        }
        readableDatabase.rawQuery("SELECT $recCols FROM recordings$where ORDER BY ts_start DESC LIMIT $limit", args).use { c ->
            while (c.moveToNext()) res.add(recFrom(c))
        }
        return res
    }

    fun recording(id: String): RecordingRow? =
        readableDatabase.rawQuery("SELECT $recCols FROM recordings WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) recFrom(c) else null
        }

    fun deleteRecording(id: String) {
        val r = recording(id) ?: return
        fileFor(r).delete()
        writableDatabase.delete("recordings", "id=?", arrayOf(id))
    }

    fun audioBytes(): Long = count("SELECT COALESCE(SUM(bytes),0) FROM recordings")

    /** Deletes oldest recordings (unlabelled first) until total audio <= limit. Returns (count, bytes) deleted. */
    fun enforceStorageLimit(limitBytes: Long): Pair<Int, Long> {
        var total = audioBytes()
        var n = 0
        var freed = 0L
        if (total <= limitBytes) return 0 to 0L
        for (pass in 0..1) {
            val sql = "SELECT $recCols FROM recordings " + (if (pass == 0) "WHERE label IS NULL " else "") + "ORDER BY ts_start ASC LIMIT 200"
            while (total > limitBytes) {
                val batch = ArrayList<RecordingRow>()
                readableDatabase.rawQuery(sql, null).use { c -> while (c.moveToNext()) batch.add(recFrom(c)) }
                if (batch.isEmpty()) break
                for (r in batch) {
                    if (total <= limitBytes) break
                    fileFor(r).delete()
                    writableDatabase.delete("recordings", "id=?", arrayOf(r.id))
                    total -= r.bytes; freed += r.bytes; n++
                }
            }
        }
        return n to freed
    }

    fun countsByKind(): Map<String, Pair<Long, Long>> {
        val m = LinkedHashMap<String, Pair<Long, Long>>()
        readableDatabase.rawQuery(
            "SELECT kind || COALESCE('/' || category, ''), COUNT(*), COALESCE(SUM(bytes),0) FROM recordings GROUP BY 1 ORDER BY 1", null
        ).use { c -> while (c.moveToNext()) m[c.getString(0)] = c.getLong(1) to c.getLong(2) }
        return m
    }

    // ------------------------------------------------------------ recognitions

    data class RecognitionRow(
        val id: String, val recordingId: String?, val interactionId: String?, val tsStart: Long, val tsEnd: Long,
        val latencyMs: Long, val source: String, val engine: String, val engineComponent: String?, val onDevice: Int?,
        val language: String, val status: String, val errorCode: Int?, val errorName: String?, val text: String?,
        val commandText: String?, val unverifiedText: String?, val alternatives: String?, val confidences: String?,
        val audioBinding: String, val bytesConsumed: Long?, val bytesTotal: Long?, val correctedText: String?,
        val label: String?, val meta: String?,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("recording_id", recordingId); put("interaction_id", interactionId)
            put("ts_start", tsStart); put("ts_start_iso", iso(tsStart)); put("ts_end", tsEnd); put("latency_ms", latencyMs)
            put("source", source); put("engine", engine); put("engine_component", engineComponent)
            put("on_device", when (onDevice) { 1 -> true; 0 -> false; else -> "unknown" })
            put("language", language); put("status", status); put("error_code", errorCode); put("error_name", errorName)
            put("text", text); put("command_text", commandText); put("unverified_text", unverifiedText)
            put("alternatives", alternatives?.let { runCatching { org.json.JSONArray(it) }.getOrNull() ?: it })
            put("confidences", confidences?.let { runCatching { org.json.JSONArray(it) }.getOrNull() ?: it })
            put("audio_binding", audioBinding); put("bytes_consumed", bytesConsumed); put("bytes_total", bytesTotal)
            put("corrected_text", correctedText); put("label", label)
            put("meta", meta?.let { runCatching { JSONObject(it) }.getOrNull() ?: it })
        }
    }

    private val rcgCols = "id, recording_id, interaction_id, ts_start, ts_end, latency_ms, source, engine, engine_component, on_device, language, status, error_code, error_name, text, command_text, unverified_text, alternatives, confidences, audio_binding, bytes_consumed, bytes_total, corrected_text, label, meta"

    private fun rcgFrom(c: Cursor) = RecognitionRow(
        c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getLong(5), c.getString(6),
        c.getString(7), c.getString(8), if (c.isNull(9)) null else c.getInt(9), c.getString(10), c.getString(11),
        if (c.isNull(12)) null else c.getInt(12), c.getString(13), c.getString(14), c.getString(15), c.getString(16),
        c.getString(17), c.getString(18), c.getString(19), if (c.isNull(20)) null else c.getLong(20),
        if (c.isNull(21)) null else c.getLong(21), c.getString(22), c.getString(23), c.getString(24)
    )

    fun insertRecognition(r: RecognitionRow) {
        val cv = ContentValues().apply {
            put("id", r.id); put("recording_id", r.recordingId); put("interaction_id", r.interactionId)
            put("ts_start", r.tsStart); put("ts_end", r.tsEnd); put("latency_ms", r.latencyMs); put("source", r.source)
            put("engine", r.engine); put("engine_component", r.engineComponent); put("on_device", r.onDevice)
            put("language", r.language); put("status", r.status); put("error_code", r.errorCode); put("error_name", r.errorName)
            put("text", r.text); put("command_text", r.commandText); put("unverified_text", r.unverifiedText)
            put("alternatives", r.alternatives); put("confidences", r.confidences); put("audio_binding", r.audioBinding)
            put("bytes_consumed", r.bytesConsumed); put("bytes_total", r.bytesTotal); put("corrected_text", r.correctedText)
            put("label", r.label); put("meta", r.meta)
        }
        writableDatabase.insertWithOnConflict("recognitions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun setCorrection(id: String, text: String?) {
        writableDatabase.update("recognitions", ContentValues().apply { put("corrected_text", text) }, "id=?", arrayOf(id))
    }

    fun setRecognitionLabel(id: String, label: String?) {
        writableDatabase.update("recognitions", ContentValues().apply { put("label", label) }, "id=?", arrayOf(id))
    }

    fun recognitions(limit: Int): List<RecognitionRow> {
        val res = ArrayList<RecognitionRow>()
        readableDatabase.rawQuery("SELECT $rcgCols FROM recognitions ORDER BY ts_start DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) res.add(rcgFrom(c))
        }
        return res
    }

    fun latestRecognition(): RecognitionRow? = recognitions(1).firstOrNull()

    // ------------------------------------------------------------------ sync bookkeeping

    private fun ensureSyncTable() {
        writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS synced(recording_id TEXT PRIMARY KEY, name TEXT, ts INTEGER)")
    }

    fun markSynced(recordingId: String, name: String) {
        ensureSyncTable()
        writableDatabase.insertWithOnConflict("synced", null, ContentValues().apply {
            put("recording_id", recordingId); put("name", name); put("ts", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun syncedName(recordingId: String): String? {
        ensureSyncTable()
        return readableDatabase.rawQuery("SELECT name FROM synced WHERE recording_id=?", arrayOf(recordingId)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    /** Completed recordings saved after [sinceTs] that have not been copied to the sync folder yet. */
    fun unsyncedSince(sinceTs: Long, limit: Int): List<RecordingRow> {
        ensureSyncTable()
        val res = ArrayList<RecordingRow>()
        readableDatabase.rawQuery(
            "SELECT $recCols FROM recordings WHERE ts_start >= ? AND COALESCE(category,'') <> 'RECORDING' " +
                "AND id NOT IN (SELECT recording_id FROM synced) ORDER BY ts_start ASC LIMIT $limit", arrayOf(sinceTs.toString())
        ).use { c -> while (c.moveToNext()) res.add(recFrom(c)) }
        return res
    }

    fun eventsBetween(fromTs: Long, toTs: Long, interactionId: String?): List<EventRow> {
        val res = ArrayList<EventRow>()
        val sql = "SELECT id, ts, type, msg, data, interaction_id, recording_id, proc FROM events WHERE ts BETWEEN ? AND ? AND " +
            "(interaction_id = ? OR type LIKE 'HEARTBEAT%' OR type LIKE 'SERVICE%' OR type LIKE 'FGS%' OR type LIKE 'DEVICE_%' " +
            "OR type LIKE 'MIC_CLIENT%' OR type LIKE 'RECORDING_CLIENTS%' OR type LIKE 'AUDIO_MODE%' OR type LIKE 'PROCESS%' " +
            "OR type LIKE 'SUSPECTED%' OR type LIKE 'CRASH%' OR type LIKE 'IMAGE_%' OR type LIKE 'SYNC_%' OR type LIKE 'STORAGE%') ORDER BY id"
        readableDatabase.rawQuery(sql, arrayOf(fromTs.toString(), toTs.toString(), interactionId ?: "")).use { c ->
            while (c.moveToNext()) res.add(EventRow(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7)))
        }
        return res
    }

    fun eventsForRecording(recordingId: String): List<EventRow> {
        val res = ArrayList<EventRow>()
        readableDatabase.rawQuery("SELECT id, ts, type, msg, data, interaction_id, recording_id, proc FROM events WHERE recording_id=? ORDER BY id LIMIT 50",
            arrayOf(recordingId)).use { c ->
            while (c.moveToNext()) res.add(EventRow(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7)))
        }
        return res
    }

    /** Deletes local recordings that started before [ts]. Returns (count, bytes). Sync folder copies are not touched. */
    fun deleteRecordingsBefore(ts: Long): Pair<Int, Long> {
        val rows = ArrayList<RecordingRow>()
        readableDatabase.rawQuery("SELECT $recCols FROM recordings WHERE ts_start < ? AND COALESCE(category,'') <> 'RECORDING'", arrayOf(ts.toString()))
            .use { c -> while (c.moveToNext()) rows.add(recFrom(c)) }
        var bytes = 0L
        for (r in rows) { fileFor(r).delete(); writableDatabase.delete("recordings", "id=?", arrayOf(r.id)); bytes += r.bytes }
        return rows.size to bytes
    }

    // ------------------------------------------------------------------ transcriptions (one row per recording and audio variant)

    data class TranscriptRow(
        val recordingId: String, val variant: String, val day: String, val tsStart: Long, val tsEnd: Long,
        val audioName: String, val kind: String, val category: String?, val durationMs: Long,
        /** SPEECH, NO_SPEECH or ERROR */
        val status: String, val language: String?, val text: String?, val info: String?, val engine: String, val processedTs: Long,
    )

    @Volatile private var transcriptTableReady = false

    private fun ensureTranscriptTable() {
        if (transcriptTableReady) return
        writableDatabase.execSQL(
            """CREATE TABLE IF NOT EXISTS transcripts(recording_id TEXT NOT NULL, variant TEXT NOT NULL, day TEXT NOT NULL,
               ts_start INTEGER, ts_end INTEGER, audio_name TEXT, kind TEXT, category TEXT, duration_ms INTEGER, status TEXT,
               language TEXT, text TEXT, info TEXT, engine TEXT, processed_ts INTEGER, PRIMARY KEY(recording_id, variant))"""
        )
        writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS tr_day ON transcripts(day, ts_start)")
        transcriptTableReady = true
    }

    /** Insert or replace: reprocessing a recording never creates a second entry. */
    fun upsertTranscript(t: TranscriptRow) {
        ensureTranscriptTable()
        writableDatabase.insertWithOnConflict("transcripts", null, ContentValues().apply {
            put("recording_id", t.recordingId); put("variant", t.variant); put("day", t.day); put("ts_start", t.tsStart)
            put("ts_end", t.tsEnd); put("audio_name", t.audioName); put("kind", t.kind); put("category", t.category)
            put("duration_ms", t.durationMs); put("status", t.status); put("language", t.language); put("text", t.text)
            put("info", t.info); put("engine", t.engine); put("processed_ts", t.processedTs)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private val trCols = "recording_id, variant, day, ts_start, ts_end, audio_name, kind, category, duration_ms, status, language, text, info, engine, processed_ts"

    private fun trFrom(c: Cursor) = TranscriptRow(
        c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getString(5) ?: "", c.getString(6) ?: "",
        c.getString(7), c.getLong(8), c.getString(9) ?: "ERROR", c.getString(10), c.getString(11), c.getString(12), c.getString(13) ?: "",
        c.getLong(14)
    )

    fun transcript(recordingId: String, variant: String): TranscriptRow? {
        ensureTranscriptTable()
        return readableDatabase.rawQuery("SELECT $trCols FROM transcripts WHERE recording_id=? AND variant=?", arrayOf(recordingId, variant))
            .use { c -> if (c.moveToFirst()) trFrom(c) else null }
    }

    fun transcriptsForDay(day: String): List<TranscriptRow> {
        ensureTranscriptTable()
        val res = ArrayList<TranscriptRow>()
        readableDatabase.rawQuery("SELECT $trCols FROM transcripts WHERE day=? ORDER BY ts_start, recording_id, variant DESC", arrayOf(day))
            .use { c -> while (c.moveToNext()) res.add(trFrom(c)) }
        return res
    }

    fun allTranscripts(): List<TranscriptRow> {
        ensureTranscriptTable()
        val res = ArrayList<TranscriptRow>()
        readableDatabase.rawQuery("SELECT $trCols FROM transcripts ORDER BY ts_start, recording_id, variant DESC", null)
            .use { c -> while (c.moveToNext()) res.add(trFrom(c)) }
        return res
    }

    fun transcriptDays(): List<String> {
        ensureTranscriptTable()
        val res = ArrayList<String>()
        readableDatabase.rawQuery("SELECT DISTINCT day FROM transcripts ORDER BY day DESC", null).use { c -> while (c.moveToNext()) res.add(c.getString(0)) }
        return res
    }

    private val transcribable = "COALESCE(category,'') NOT IN ('RECORDING','EMPTY')"

    /** Newest completed recording without a transcription of its original audio. */
    fun nextUntranscribed(): RecordingRow? {
        ensureTranscriptTable()
        return readableDatabase.rawQuery(
            "SELECT $recCols FROM recordings WHERE $transcribable AND id NOT IN (SELECT recording_id FROM transcripts WHERE variant='RAW') " +
                "ORDER BY ts_start DESC LIMIT 1", null
        ).use { c -> if (c.moveToFirst()) recFrom(c) else null }
    }

    fun countUntranscribed(): Long {
        ensureTranscriptTable()
        return count("SELECT COUNT(*) FROM recordings WHERE $transcribable AND id NOT IN (SELECT recording_id FROM transcripts WHERE variant='RAW')")
    }

    /** (transcribed, with speech, without speech, errors) for one day, original audio only. */
    fun transcriptCounts(day: String): IntArray {
        ensureTranscriptTable()
        val r = IntArray(4)
        readableDatabase.rawQuery("SELECT status, COUNT(*) FROM transcripts WHERE day=? AND variant='RAW' GROUP BY status", arrayOf(day)).use { c ->
            while (c.moveToNext()) {
                val n = c.getInt(1); r[0] += n
                when (c.getString(0)) { "SPEECH" -> r[1] += n; "NO_SPEECH" -> r[2] += n; else -> r[3] += n }
            }
        }
        return r
    }

    // ------------------------------------------------------------------ misc

    fun count(sql: String): Long = readableDatabase.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }

    fun rawQuery(sql: String): Cursor = readableDatabase.rawQuery(sql, null)

    fun dbFileBytes(): Long {
        val f = ctx.getDatabasePath("jarvis_diag.db")
        return listOf(f, File(f.path + "-wal"), File(f.path + "-shm")).sumOf { if (it.exists()) it.length() else 0L }
    }

    fun deleteAll() {
        val db = writableDatabase
        db.delete("recordings", null, null)
        db.delete("recognitions", null, null)
        db.delete("events", null, null)
        runCatching { db.delete("synced", null, null) }
        runCatching { db.delete("transcripts", null, null) }
        audioDir.listFiles()?.forEach { it.delete() }
        runCatching { db.execSQL("VACUUM") }
    }

    companion object {
        private val idFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US) }
        private val isoFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US) }
        /** Keeps the event log to roughly 100–130 MB at most (about a month of continuous listening). */
        const val MAX_EVENTS = 150_000
        fun newId(prefix: String): String =
            prefix + "-" + idFmt.get()!!.format(Date()) + "-" + UUID.randomUUID().toString().substring(0, 6)
        fun iso(ts: Long): String = if (ts <= 0) "" else isoFmt.get()!!.format(Date(ts))
    }
}
