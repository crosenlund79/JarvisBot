package dk.jarvisbot.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import dk.jarvisbot.core.Wav
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The diagnostic folder (normally Documents/Jarvis/Dumps, chosen once with Android's folder picker): everything Jarvis
 * produces for analysis is written there, organised by the original recording time, so the folder can simply be copied
 * over USB (or picked up by a sync app). Jarvis itself stays offline.
 *
 *   <yyyy-MM-dd>/<HH>/<yyyy-MM-dd_HH-mm-ss-SSS>[_kind]_raw.wav           original recording, never processed
 *   <yyyy-MM-dd>/<HH>/<yyyy-MM-dd_HH-mm-ss-SSS>[_kind]_diagnostics.json  per recording (updated after transcription)
 *   <yyyy-MM-dd>/<yyyy-MM-dd>_Transcription.txt                          daily transcription
 *   <yyyy-MM-dd>/Daily_Diagnostics.json, Daily_Events.csv, Daily_Recognitions.csv
 *   <yyyy-MM-dd>/<session start>_session-log.csv / _session.json         listening sessions
 *
 * Files are written under a hidden temporary name and renamed when complete, so incomplete files never appear.
 * All folder work runs on one background thread.
 */
object DiagnosticFolder {
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "jarvis-diag-folder") }
    private val nameFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US) }
    /** Timestamped Jarvis file: group 1 = day, group 2 = hour, group 3 = the rest. */
    val NAME_RE = Regex("^(\\d{4}-\\d{2}-\\d{2})_(\\d{2})-\\d{2}-\\d{2}-\\d{3}(?:-\\d+)?_(.+)$")
    private val DAY_FILE_RE = Regex("^(\\d{4}-\\d{2}-\\d{2})_Transcription\\.txt$")
    val DAY_RE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    val HOUR_RE = Regex("^\\d{2}$")
    private const val REFRESH_MS = 30 * 60_000L

    @Volatile var ok: Boolean? = null
    @Volatile var problem: String = ""
    @Volatile var lastCopyTs = 0L
    /** Shown in the UI while longer folder work (reorganising, catching up) runs. */
    @Volatile var busyWith: String? = null
    @Volatile private var needsCatchUp = true
    /** The catch-up right after a reorganisation is reported on the main screen (later ones only in the event log). */
    @Volatile private var reportNextCatchUp = false
    private var lastFailLog = 0L
    private var lastRefreshedEventId = -1L
    private val started = AtomicBoolean(false)
    /** Relative folder path ("2026-11-25", "2026-11-25/08") -> document id. */
    private val dirIds = ConcurrentHashMap<String, String>()

    private val app get() = JarvisApp.instance
    private val cr get() = app.contentResolver

    fun baseName(r: DiagStore.RecordingRow): String =
        nameFmt.get()!!.format(Date(r.tsStart)) + if (r.kind == "MICTEST") "" else "_" + r.kind.lowercase()

    fun dayOfBase(base: String) = base.substring(0, 10)
    fun recordingPath(base: String) = base.substring(0, 10) + "/" + base.substring(11, 13)

    fun isConfigured() = app.prefs.syncTreeUri.isNotEmpty()

    /** Human-readable folder, e.g. "Documents/Jarvis/Dumps". */
    fun label(): String {
        val s = app.prefs.syncTreeUri
        if (s.isEmpty()) return "not set"
        return runCatching {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(s))
            val vol = id.substringBefore(':')
            val path = id.substringAfter(':', "")
            if (vol == "primary") path.ifEmpty { "(internal storage)" } else "$vol/$path"
        }.getOrDefault(s)
    }

    /** Document URI of the folder itself (used as the starting point of the export file picker). */
    fun rootUri(): Uri? = tree()?.let { DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it)) }

    fun runOnExecutor(delayMs: Long = 0, block: () -> Unit) {
        val r = Runnable {
            try {
                block()
            } catch (t: Throwable) {
                runCatching { app.store.log("DIAG_FOLDER_ERROR", t.toString(), JSONObject().put("stack", t.stackTraceToString().take(4000))) }
            }
        }
        if (delayMs > 0) exec.schedule(r, delayMs, TimeUnit.MILLISECONDS) else exec.execute(r)
    }

    /** Called once per process: reorganise / catch up, then refresh daily files every 30 min. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        runOnExecutor { startup() }
        exec.scheduleWithFixedDelay({ runCatching { periodic() } }, REFRESH_MS, REFRESH_MS, TimeUnit.MILLISECONDS)
    }

    private fun startup() {
        if (isConfigured()) {
            check()
            if (ok == true) {
                migrateIfNeeded()
                catchUpNow()
            }
        }
        StorageManager.enforceNow(forceScan = true) // also measures usage for the daily diagnostics
        if (ok == true) refreshPendingDays()
    }

    private fun periodic() {
        if (isConfigured() && app.store.maxEventId() != lastRefreshedEventId) {
            val today = DailyReports.dayOf(System.currentTimeMillis())
            val days = linkedSetOf(today)
            app.prefs.lastDailyRefreshDay.takeIf { it.isNotEmpty() && it < today }?.let { days.add(it) }
            refreshDailyNow(days)
        }
        StorageManager.enforceNow(forceScan = false)
    }

    fun configure(ctx: Context, treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        ctx.contentResolver.takePersistableUriPermission(treeUri, flags)
        val old = app.prefs.syncTreeUri
        if (old.isNotEmpty() && old != treeUri.toString()) {
            runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) }
        }
        app.prefs.syncTreeUri = treeUri.toString()
        ok = null; problem = ""; needsCatchUp = true
        dirIds.clear()
        app.store.log("DIAG_FOLDER_SELECTED", label(), JSONObject().put("uri", treeUri.toString()))
        runOnExecutor {
            check()
            if (ok == true) {
                migrateIfNeeded()
                catchUpNow()
                StorageManager.enforceNow(forceScan = true)
                refreshDailyNow(app.store.daysWithData())
            }
        }
        Transcription.publishAllDays()
    }

    fun disable(ctx: Context) {
        val old = app.prefs.syncTreeUri
        if (old.isNotEmpty()) runCatching {
            ctx.contentResolver.releasePersistableUriPermission(Uri.parse(old),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        app.prefs.syncTreeUri = ""
        ok = null; problem = ""
        dirIds.clear()
        StorageManager.invalidate()
        app.store.log("DIAG_FOLDER_DISABLED", "Writing to the diagnostic folder turned off (files already there are kept)")
    }

    // ------------------------------------------------------------------ public operations (all queued)

    /** A completed recording: copy it (+ its diagnostics) into <day>/<hour>/. */
    fun publish(r: DiagStore.RecordingRow) {
        if (!isConfigured() || r.category == "RECORDING" || r.category == "EMPTY") return
        runOnExecutor {
            if (needsCatchUp) catchUpNow()
            publishNow(r)
        }
    }

    /** Copy recordings that are not in the folder yet (folder was unavailable, chosen later, app killed, …). */
    fun catchUp() {
        if (isConfigured()) runOnExecutor { catchUpNow() }
    }

    /** Rewrite a recording's diagnostics file (e.g. after it was transcribed). */
    fun updateDiagnostics(r: DiagStore.RecordingRow) {
        if (!isConfigured()) return
        runOnExecutor {
            val base = app.store.syncedName(r.id) ?: return@runOnExecutor
            try {
                write(recordingPath(base), "${base}_diagnostics.json", replace = true) { os ->
                    os.write(diagnosticsJson(app.store.recording(r.id) ?: r, "${base}_raw.wav", null, null).toString(2).toByteArray())
                }
            } catch (e: Exception) {
                fail("diagnostics update", e)
            }
        }
    }

    /** Session log (CSV), refreshed during long sessions; [summary] given at session end also writes a session JSON. */
    fun publishSessionLog(sessionId: String, startTs: Long, summary: JSONObject?) {
        if (!isConfigured()) return
        runOnExecutor {
            try {
                val base = nameFmt.get()!!.format(Date(startTs)) + "_session"
                val day = base.substring(0, 10)
                val events = app.store.eventsBetween(startTs - 5000, System.currentTimeMillis(), sessionId)
                write(day, "$base-log.csv", replace = true) { os ->
                    os.write("time,type,message\n".toByteArray())
                    for (e in events) os.write((csv(DiagStore.iso(e.ts)) + "," + csv(e.type) + "," + csv(e.msg) + "\n").toByteArray(Charsets.UTF_8))
                }
                if (summary != null) {
                    write(day, "$base.json", replace = true) { os ->
                        os.write(JSONObject().put("schema", "jarvis-session-v1").put("session", sessionId)
                            .put("summary", summary).put("app", appInfo()).put("device", deviceLine())
                            .put("recordingSettings", DailyReports.recordingSettings())
                            .put("settings", app.prefs.toJson()).toString(2).toByteArray())
                    }
                }
                markOk()
            } catch (e: Exception) {
                fail("session log", e)
            }
        }
    }

    /** Daily transcription file: replaces the previous version in <day>/. */
    fun publishTranscript(day: String, file: File) {
        if (!isConfigured() || !file.exists()) return
        val bytes = file.readBytes()
        runOnExecutor {
            try {
                write(day, file.name, replace = true) { it.write(bytes) }
                markOk()
            } catch (e: Exception) {
                fail("transcription file", e)
            }
        }
    }

    /** Listening stopped: refresh today's daily diagnostics (and [extraDays], e.g. the day a session started). */
    fun listeningStopped(vararg extraDays: String) {
        if (!isConfigured()) return
        runOnExecutor { refreshDailyNow(linkedSetOf(DailyReports.dayOf(System.currentTimeMillis())) + extraDays) }
    }

    // ------------------------------------------------------------------ reading recordings back (any thread)

    /** The diagnostic-folder copy of a delivered recording, if it is there. */
    fun folderAudio(r: DiagStore.RecordingRow): Uri? = runCatching {
        val tree = tree() ?: return null
        val base = app.store.syncedName(r.id) ?: return null
        val c = findFile(tree, recordingPath(base), "${base}_raw.wav") ?: return null
        DocumentsContract.buildDocumentUriUsingTree(tree, c.docId)
    }.getOrNull()

    /** Original audio of a recording: the phone copy, or else the diagnostic-folder copy. */
    fun recordingBytes(r: DiagStore.RecordingRow): ByteArray {
        val f = app.store.fileFor(r)
        if (f.exists()) return f.readBytes()
        val u = folderAudio(r) ?: throw FileNotFoundException("audio is neither on the phone nor in the diagnostic folder: ${r.file}")
        return (cr.openInputStream(u) ?: throw FileNotFoundException(u.toString())).use { it.readBytes() }
    }

    fun readRecording(r: DiagStore.RecordingRow): Pair<ShortArray, Int> {
        val f = app.store.fileFor(r)
        return if (f.exists()) Wav.read(f) else Wav.read(recordingBytes(r))
    }

    // ------------------------------------------------------------------ storage scan (folder thread)

    class Entry(val path: String, val docId: String, val name: String, val size: Long)
    class Scan(val entries: List<Entry>, val dirs: List<Entry>, val ts: Long) {
        val exportBytes = entries.filter { it.name.endsWith(".zip", true) }.sumOf { it.size }
        val jarvisBytes = entries.sumOf { it.size } - exportBytes
        val files = entries.size
    }

    /** Lists the folder: top level, day folders and their hour folders (other folders are not Jarvis's and are ignored). */
    fun scanNow(): Scan? {
        val tree = tree() ?: return null
        return try {
            val files = ArrayList<Entry>()
            val dirs = ArrayList<Entry>()
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            for (c in children(tree, rootId)) {
                if (!c.isDir) { files.add(Entry("", c.docId, c.name, c.size)); continue }
                if (!DAY_RE.matches(c.name)) continue
                dirIds[c.name] = c.docId
                dirs.add(Entry("", c.docId, c.name, 0))
                for (d in children(tree, c.docId)) {
                    if (!d.isDir) { files.add(Entry(c.name, d.docId, d.name, d.size)); continue }
                    if (!HOUR_RE.matches(d.name)) continue
                    val hp = c.name + "/" + d.name
                    dirIds[hp] = d.docId
                    dirs.add(Entry(c.name, d.docId, d.name, 0))
                    for (h in children(tree, d.docId)) if (!h.isDir) files.add(Entry(hp, h.docId, h.name, h.size))
                }
            }
            markOk()
            Scan(files, dirs, System.currentTimeMillis())
        } catch (e: Exception) {
            fail("folder unavailable", e)
            null
        }
    }

    fun deleteEntry(e: Entry): Boolean {
        val tree = tree() ?: return false
        return runCatching { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, e.docId)) }.getOrDefault(false)
    }

    fun forgetDirs() = dirIds.clear()

    // ------------------------------------------------------------------ internals (folder thread)

    private fun tree(): Uri? = app.prefs.syncTreeUri.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }

    private class Child(val docId: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

    private fun children(tree: Uri, parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val res = ArrayList<Child>()
        (cr.query(uri, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null) ?: throw IOException("folder not accessible")).use { c ->
            while (c.moveToNext()) {
                res.add(Child(c.getString(0), c.getString(1) ?: "", c.getString(2) == Document.MIME_TYPE_DIR,
                    if (c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4)))
            }
        }
        return res
    }

    /** Document id of a folder below the chosen folder ("" = the folder itself), optionally created. */
    private fun dirId(tree: Uri, path: String, create: Boolean): String? {
        if (path.isEmpty()) return DocumentsContract.getTreeDocumentId(tree)
        dirIds[path]?.let { return it }
        val parentPath = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val parentId = dirId(tree, parentPath, create) ?: return null
        val id = children(tree, parentId).firstOrNull { it.isDir && it.name == name }?.docId
            ?: if (create) {
                val u = DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, parentId), Document.MIME_TYPE_DIR, name)
                    ?: throw IOException("could not create folder $path")
                DocumentsContract.getDocumentId(u)
            } else null
        if (id != null) dirIds[path] = id
        return id
    }

    private fun findFile(tree: Uri, path: String, name: String): Child? {
        val id = dirId(tree, path, create = false) ?: return null
        return children(tree, id).firstOrNull { !it.isDir && it.name == name }
    }

    private class Counting(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    /**
     * Writes [name] into folder [path] (created if needed) under a hidden temporary name, then renames it. With
     * [replace], an existing file of that name is removed just before the complete new one takes its place.
     * Retries once with fresh folder lookups (a folder may have been deleted over USB or by a sync app).
     */
    private fun write(path: String, name: String, replace: Boolean, body: (OutputStream) -> Unit): Long {
        val tree = tree() ?: throw IOException("no diagnostic folder chosen")
        var last: Exception? = null
        for (attempt in 0..1) {
            try {
                return writeOnce(tree, path, name, replace, body)
            } catch (e: Exception) {
                last = e
                dirIds.clear()
            }
        }
        throw last!!
    }

    private fun writeOnce(tree: Uri, path: String, name: String, replace: Boolean, body: (OutputStream) -> Unit): Long {
        val dirId = dirId(tree, path, create = true)!!
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(tree, dirId)
        val old = if (replace) children(tree, dirId).filter { !it.isDir && it.name == name } else emptyList()
        val tmp = DocumentsContract.createDocument(cr, dirUri, "application/octet-stream", ".$name.partial")
            ?: throw IOException("could not create a file in the diagnostic folder")
        try {
            var n = 0L
            (cr.openOutputStream(tmp, "w") ?: throw IOException("could not open $name for writing")).use { raw ->
                val c = Counting(raw)
                body(c)
                c.flush()
                n = c.count
            }
            for (o in old) DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, o.docId))
            DocumentsContract.renameDocument(cr, tmp, name) ?: throw IOException("rename to $name failed")
            return n
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(cr, tmp) }
            throw e
        }
    }

    private fun check() {
        val tree = tree() ?: return
        try {
            val hasPermission = cr.persistedUriPermissions.any { it.uri.toString() == app.prefs.syncTreeUri && it.isWritePermission }
            if (!hasPermission) throw SecurityException("no persisted write permission for the folder")
            children(tree, DocumentsContract.getTreeDocumentId(tree))
            markOk()
        } catch (e: Exception) {
            fail("folder unavailable", e)
        }
    }

    private fun catchUpNow() {
        needsCatchUp = false
        if (tree() == null) return
        var copied = 0
        while (true) {
            val pending = app.store.undelivered(200).filter { app.store.fileFor(it).exists() }
            if (pending.isEmpty()) break
            busyWith = "Copying ${pending.size}+ recording(s) from the phone into the diagnostic folder…"
            var progress = false
            for (r in pending) {
                if (!publishNow(r)) { busyWith = null; return }
                copied++; progress = true
            }
            if (!progress) break
        }
        busyWith = null
        if (copied > 0) {
            app.store.log("DIAG_FOLDER_CATCH_UP", "Copied $copied recording(s) that were not in the diagnostic folder yet")
            if (reportNextCatchUp) appendReport("copied $copied recording(s) from the phone that were not in the folder yet")
        }
        reportNextCatchUp = false
    }

    private fun publishNow(r: DiagStore.RecordingRow): Boolean {
        val tree = tree() ?: return false
        val store = app.store
        if (store.syncedName(r.id) != null) return true
        val src = store.fileFor(r)
        if (!src.exists()) return true
        return try {
            // Same name already in the folder: the same recording if the size matches (delivered before), otherwise
            // a different file — never overwritten, this recording gets a "-2" suffix instead.
            var base = baseName(r)
            var k = 2
            var already = false
            while (true) {
                val existing = findFile(tree, recordingPath(base), "${base}_raw.wav") ?: break
                if (existing.size == src.length()) { already = true; break }
                base = baseName(r).let { b -> b.substring(0, 23) + "-" + k++ + b.substring(23) }
            }
            if (!already) {
                val n = write(recordingPath(base), "${base}_raw.wav", replace = false) { os -> src.inputStream().use { it.copyTo(os, 64 * 1024) } }
                if (n != src.length()) throw IOException("copy incomplete: $n of ${src.length()} bytes")
                StorageManager.added(n)
            }
            store.markSynced(r.id, base)
            write(recordingPath(base), "${base}_diagnostics.json", replace = true) { os ->
                os.write(diagnosticsJson(r, "${base}_raw.wav", null, null).toString(2).toByteArray())
            }
            lastCopyTs = System.currentTimeMillis()
            if (!already) store.log("DIAG_FOLDER_COPIED", "${recordingPath(base)}/${base}_raw.wav (+ diagnostics)",
                JSONObject().put("bytes", src.length()), r.interactionId, r.id)
            markOk()
            true
        } catch (e: Exception) {
            fail("copy failed", e)
            needsCatchUp = true
            false
        }
    }

    // ------------------------------------------------------------------ one-time reorganisation of the flat layout

    private fun destinationFor(name: String): String? {
        NAME_RE.matchEntire(name)?.let { m ->
            val rest = m.groupValues[3]
            return if (rest.startsWith("session")) m.groupValues[1] else m.groupValues[1] + "/" + m.groupValues[2]
        }
        DAY_FILE_RE.matchEntire(name)?.let { return it.groupValues[1] }
        return null
    }

    private fun sha256(u: Uri): String {
        val md = MessageDigest.getInstance("SHA-256")
        (cr.openInputStream(u) ?: throw IOException("cannot read $u")).use { s ->
            val b = ByteArray(64 * 1024)
            while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sizeOf(u: Uri): Long = cr.query(u, arrayOf(Document.COLUMN_SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
    } ?: -1L

    /**
     * Moves files of the old flat layout into <day>/<hour>/ (recordings, diagnostics) or <day>/ (session logs,
     * transcriptions). A move is a rename on the same storage; where it is not supported the file is copied, the copy
     * verified (size + SHA-256) and only then the original removed. A file whose name already exists at the
     * destination is removed only if both are identical; otherwise both are kept and the conflict is reported.
     */
    private fun migrateIfNeeded() {
        val tree = tree() ?: return
        val key = app.prefs.syncTreeUri
        if (app.prefs.diagLayoutDoneFor == key) return
        busyWith = "Reorganising the diagnostic folder into date/hour folders…"
        val t0 = System.currentTimeMillis()
        var moved = 0; var copied = 0; var duplicates = 0; var conflicts = 0; var staleTemp = 0
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        try {
            for (f in children(tree, rootId)) {
                if (f.isDir) continue
                val src = DocumentsContract.buildDocumentUriUsingTree(tree, f.docId)
                if (f.name.startsWith(".") && f.name.endsWith(".partial")) {
                    // an unfinished temporary file of ours (app killed while writing); never a complete recording
                    if (System.currentTimeMillis() - f.modified > 3_600_000L && runCatching { DocumentsContract.deleteDocument(cr, src) }.getOrDefault(false)) staleTemp++
                    continue
                }
                val dest = destinationFor(f.name) ?: continue
                val destId = dirId(tree, dest, create = true)!!
                val destUri = DocumentsContract.buildDocumentUriUsingTree(tree, destId)
                val existing = children(tree, destId).firstOrNull { !it.isDir && it.name == f.name }
                if (existing != null) {
                    if (existing.size == f.size && sha256(src) == sha256(DocumentsContract.buildDocumentUriUsingTree(tree, existing.docId))) {
                        DocumentsContract.deleteDocument(cr, src); duplicates++
                    } else {
                        conflicts++
                        app.store.log("DIAG_FOLDER_MIGRATION_CONFLICT", "${f.name}: a different file with this name is already in $dest; both kept")
                    }
                    continue
                }
                val m = if (Build.VERSION.SDK_INT >= 24) runCatching { DocumentsContract.moveDocument(cr, src, rootUri, destUri) }.getOrNull() else null
                if (m != null) {
                    if (sizeOf(m) == f.size) moved++ else {
                        conflicts++
                        app.store.log("DIAG_FOLDER_MIGRATION_CONFLICT", "${f.name}: size differs after moving to $dest (${sizeOf(m)} vs ${f.size})")
                    }
                } else {
                    val n = write(dest, f.name, replace = false) { os -> (cr.openInputStream(src) ?: throw IOException("cannot read ${f.name}")).use { it.copyTo(os, 64 * 1024) } }
                    val copy = findFile(tree, dest, f.name)
                    if (n == f.size && copy != null && copy.size == f.size &&
                        sha256(DocumentsContract.buildDocumentUriUsingTree(tree, copy.docId)) == sha256(src)) {
                        DocumentsContract.deleteDocument(cr, src); copied++
                    } else {
                        conflicts++
                        app.store.log("DIAG_FOLDER_MIGRATION_CONFLICT", "${f.name}: copy to $dest could not be verified; original kept")
                    }
                }
            }
        } catch (e: Exception) {
            busyWith = null
            fail("reorganising the folder", e)
            return // retried at the next start; nothing is lost (each step is verified)
        }
        val parts = ArrayList<String>()
        if (moved + copied > 0) parts.add("moved ${moved + copied} file(s) into date/hour folders")
        if (duplicates > 0) parts.add("removed $duplicates duplicate(s) after verifying identical copies")
        if (conflicts > 0) parts.add("kept $conflicts file(s) in place because of a name conflict (see the event log)")
        if (staleTemp > 0) parts.add("removed $staleTemp unfinished temporary file(s)")
        app.prefs.diagLayoutDoneFor = key
        app.store.log("DIAG_FOLDER_REORGANISED", parts.joinToString("; ").ifEmpty { "nothing to move" },
            JSONObject().put("moved", moved).put("copiedAndVerified", copied).put("duplicates", duplicates).put("conflicts", conflicts)
                .put("staleTemporaryFiles", staleTemp).put("ms", System.currentTimeMillis() - t0))
        app.prefs.diagFolderReport = ""
        if (parts.isNotEmpty()) appendReport(parts.joinToString("; "))
        reportNextCatchUp = true
        // diagnostics of recordings transcribed before the reorganisation now get their transcription
        for ((base, id) in app.store.deliveredBases()) {
            if (base.length < 23 || app.store.transcript(id, "RAW") == null) continue
            val r = app.store.recording(id) ?: continue
            runCatching {
                write(recordingPath(base), "${base}_diagnostics.json", replace = true) { os ->
                    os.write(diagnosticsJson(r, "${base}_raw.wav", null, null).toString(2).toByteArray())
                }
            }
        }
        busyWith = null
        StorageManager.invalidate()
    }

    private fun appendReport(s: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val old = app.prefs.diagFolderReport
        app.prefs.diagFolderReport = if (old.isEmpty()) "$stamp: $s" else "$old; $s"
    }

    // ------------------------------------------------------------------ daily files

    private fun refreshPendingDays() {
        val today = DailyReports.dayOf(System.currentTimeMillis())
        val last = app.prefs.lastDailyRefreshDay
        val days = app.store.daysWithData().filter { last.isEmpty() || it >= last }.toMutableSet()
        days.add(today)
        refreshDailyNow(days)
    }

    private fun refreshDailyNow(days: Collection<String>) {
        if (tree() == null) return
        val ctx = app
        val maxId = app.store.maxEventId()
        for (day in days.sorted()) {
            try {
                write(day, DailyReports.DIAGNOSTICS, replace = true) { it.write(DailyReports.diagnosticsJson(ctx, day).toString(2).toByteArray()) }
                write(day, DailyReports.EVENTS, replace = true) { DailyReports.writeEvents(day, it) }
                write(day, DailyReports.RECOGNITIONS, replace = true) { DailyReports.writeRecognitions(day, it) }
                markOk()
            } catch (e: Exception) {
                fail("daily diagnostics", e)
                return
            }
        }
        lastRefreshedEventId = maxId
        val today = DailyReports.dayOf(System.currentTimeMillis())
        if (days.contains(today) || app.prefs.lastDailyRefreshDay < today) app.prefs.lastDailyRefreshDay = today
    }

    private fun markOk() {
        if (ok == false) app.store.log("DIAG_FOLDER_OK", "Diagnostic folder is reachable again: ${label()}")
        ok = true
        problem = ""
    }

    private fun fail(what: String, e: Exception) {
        ok = false
        problem = "$what: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
        val now = System.currentTimeMillis()
        if (now - lastFailLog > 60_000) {
            lastFailLog = now
            app.store.log("DIAG_FOLDER_FAILED", "$problem – recordings stay on the phone and are copied later",
                JSONObject().put("folder", label()))
        }
    }

    // ------------------------------------------------------------------ shared with the exporter

    fun appInfo(): JSONObject = JSONObject().put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
        .put("git", BuildConfig.GIT_SHA)

    fun deviceLine() = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

    /** Per-recording diagnostics: recording metadata (levels, threshold, sensitivity…), events, recognitions, transcription. */
    fun diagnosticsJson(r: DiagStore.RecordingRow, rawName: String?, enhancedName: String?, enhancement: JSONObject?): JSONObject {
        val store = app.store
        val events = JSONArray()
        for (e in store.eventsForRecording(r.id)) {
            events.put(JSONObject().put("time", DiagStore.iso(e.ts)).put("type", e.type).put("message", e.msg)
                .put("data", e.data?.let { runCatching { JSONObject(it) }.getOrNull() ?: it }))
        }
        val recognitions = JSONArray()
        store.recognitions(500).filter { it.recordingId == r.id }.forEach { recognitions.put(it.toJson()) }
        return JSONObject().put("schema", "jarvis-recording-diagnostics-v1")
            .put("files", JSONObject().put("raw", rawName ?: JSONObject.NULL).put("enhanced", enhancedName ?: JSONObject.NULL))
            .put("originalAudio", "unmodified samples exactly as delivered by Android (no application-level processing)")
            .put("enhancement", enhancement ?: JSONObject.NULL)
            .put("recording", r.toJson())
            .put("events", events)
            .put("recognitions", recognitions)
            .put("transcription", JSONObject().also { o ->
                for (v in listOf("RAW", "ENHANCED")) store.transcript(r.id, v)?.let { t ->
                    o.put(v.lowercase(), JSONObject().put("status", t.status).put("language", t.language).put("text", t.text)
                        .put("audio", t.audioName).put("details", t.info?.let { runCatching { JSONObject(it) }.getOrNull() }))
                }
            })
            .put("updated", DiagStore.iso(System.currentTimeMillis()))
            .put("app", appInfo())
            .put("device", deviceLine())
    }

    private fun csv(v: String?): String {
        val s = v ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
