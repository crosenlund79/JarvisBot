package dk.jarvisbot.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Copies completed recordings (+ a diagnostics JSON each) and session logs into a user-chosen folder
 * (Storage Access Framework), from where an external app such as OneSync uploads them. Jarvis itself stays offline.
 *
 * - Files are written under a hidden temporary name and renamed when complete, so incomplete files are never exposed.
 * - Names: yyyy-MM-dd_HH-mm-ss-SSS_raw.wav / _diagnostics.json (start time of the recording).
 * - If the folder is unavailable nothing is lost: the recording stays in app storage and is copied later.
 * - Jarvis-named files in the folder are capped at [CAP_BYTES] (oldest deleted first).
 */
object SyncFolder {
    const val CAP_BYTES = 2_000_000_000L
    private const val CAP_CHECK_INTERVAL_MS = 10 * 60_000L
    private val NAME_RE = Regex("^\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-\\d{3}_.+")
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-sync") }
    private val nameFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US) }
    private val sessionLogUris = ConcurrentHashMap<String, Uri>()

    @Volatile var ok: Boolean? = null
    @Volatile var problem: String = ""
    @Volatile var lastCopyTs = 0L
    @Volatile var copiedThisRun = 0
    @Volatile var folderBytes = -1L
    @Volatile var folderFiles = -1
    @Volatile private var needsCatchUp = true
    private var lastCapCheck = 0L
    private var lastFailLog = 0L

    private val app get() = JarvisApp.instance

    fun baseName(r: DiagStore.RecordingRow): String =
        nameFmt.get()!!.format(Date(r.tsStart)) + if (r.kind == "MICTEST") "" else "_" + r.kind.lowercase()

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

    fun configure(ctx: Context, treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        ctx.contentResolver.takePersistableUriPermission(treeUri, flags)
        val old = app.prefs.syncTreeUri
        if (old.isNotEmpty() && old != treeUri.toString()) {
            runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) }
        }
        app.prefs.syncTreeUri = treeUri.toString()
        app.prefs.syncSinceTs = System.currentTimeMillis()
        ok = null; problem = ""; needsCatchUp = true
        app.store.log("SYNC_FOLDER_SELECTED", label(), JSONObject().put("uri", treeUri.toString()))
        exec.execute { check(); refreshUsage(prune = false) }
    }

    fun disable(ctx: Context) {
        val old = app.prefs.syncTreeUri
        if (old.isNotEmpty()) runCatching {
            ctx.contentResolver.releasePersistableUriPermission(Uri.parse(old),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        app.prefs.syncTreeUri = ""
        ok = null; problem = ""; folderBytes = -1; folderFiles = -1
        app.store.log("SYNC_FOLDER_DISABLED", "Automatic copying turned off (files already in the folder are kept)")
    }

    /** Queue a completed recording for copying. */
    fun publish(r: DiagStore.RecordingRow) {
        if (!isConfigured() || r.category == "RECORDING" || r.category == "EMPTY") return
        exec.execute {
            if (needsCatchUp) catchUpNow()
            publishNow(r)
        }
    }

    /** Copy any recordings that were missed (folder unavailable, app killed, …). */
    fun catchUp() {
        if (!isConfigured()) return
        exec.execute { catchUpNow() }
    }

    fun refreshUsageAsync() {
        if (!isConfigured()) return
        exec.execute { check(); refreshUsage(prune = false) }
    }

    /** Session log (CSV), refreshed during long sessions; [summary] given at session end also writes a session JSON. */
    fun publishSessionLog(sessionId: String, startTs: Long, summary: JSONObject?) {
        if (!isConfigured()) return
        exec.execute {
            val dir = dirUri() ?: return@execute
            try {
                val base = nameFmt.get()!!.format(Date(startTs)) + "_session"
                val events = app.store.eventsBetween(startTs - 5000, System.currentTimeMillis(), sessionId)
                sessionLogUris.remove(sessionId)?.let { runCatching { DocumentsContract.deleteDocument(app.contentResolver, it) } }
                val uri = writeAtomic(dir, "$base-log.csv") { os ->
                    os.write("time,type,message\n".toByteArray())
                    for (e in events) os.write((csv(DiagStore.iso(e.ts)) + "," + csv(e.type) + "," + csv(e.msg) + "\n").toByteArray(Charsets.UTF_8))
                }
                if (summary == null) sessionLogUris[sessionId] = uri
                if (summary != null) {
                    writeAtomic(dir, "$base.json") { os ->
                        os.write(JSONObject().put("schema", "jarvis-session-v1").put("session", sessionId)
                            .put("summary", summary).put("app", appInfo()).put("device", deviceLine())
                            .put("settings", app.prefs.toJson()).toString(2).toByteArray())
                    }
                }
                markOk()
            } catch (e: Exception) {
                fail("session log", e)
            }
        }
    }

    // ------------------------------------------------------------------ internals (sync thread)

    private fun dirUri(): Uri? {
        val s = app.prefs.syncTreeUri
        if (s.isEmpty()) return null
        val tree = Uri.parse(s)
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }

    private fun check() {
        val dir = dirUri() ?: return
        try {
            val hasPermission = app.contentResolver.persistedUriPermissions.any { it.uri.toString() == app.prefs.syncTreeUri && it.isWritePermission }
            if (!hasPermission) throw SecurityException("no persisted write permission for the folder")
            app.contentResolver.query(dir, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use {
                if (!it.moveToFirst()) throw IOException("folder not found")
            } ?: throw IOException("folder not accessible")
            markOk()
        } catch (e: Exception) {
            fail("folder unavailable", e)
        }
    }

    private fun catchUpNow() {
        needsCatchUp = false
        val pending = app.store.unsyncedSince(app.prefs.syncSinceTs, 300)
        if (pending.isEmpty()) return
        app.store.log("SYNC_CATCH_UP", "Copying ${pending.size} recording(s) that are not in the sync folder yet")
        for (r in pending) { if (!publishNow(r)) break }
    }

    private fun publishNow(r: DiagStore.RecordingRow): Boolean {
        val dir = dirUri() ?: return false
        val store = app.store
        if (store.syncedName(r.id) != null) return true
        val src = store.fileFor(r)
        if (!src.exists()) return true
        return try {
            val base = baseName(r)
            val rawName = "${base}_raw.wav"
            writeAtomic(dir, rawName) { os -> src.inputStream().use { it.copyTo(os, 64 * 1024) } }
            writeAtomic(dir, "${base}_diagnostics.json") { os ->
                os.write(diagnosticsJson(r, rawName, null, null).toString(2).toByteArray())
            }
            store.markSynced(r.id, base)
            lastCopyTs = System.currentTimeMillis()
            copiedThisRun++
            store.log("SYNC_COPIED", "$rawName (+ diagnostics) → ${label()}", JSONObject().put("bytes", src.length()), r.interactionId, r.id)
            markOk()
            maybeEnforceCap()
            true
        } catch (e: Exception) {
            fail("copy failed", e)
            needsCatchUp = true
            false
        }
    }

    /** Write under a hidden temporary name, then rename: the final name only appears once the file is complete. */
    private fun writeAtomic(dir: Uri, finalName: String, body: (OutputStream) -> Unit): Uri {
        val cr = app.contentResolver
        val tmp = runCatching { DocumentsContract.createDocument(cr, dir, "application/octet-stream", ".$finalName.partial") }.getOrNull()
            ?: DocumentsContract.createDocument(cr, dir, "application/octet-stream", "$finalName.partial")
            ?: throw IOException("could not create a file in the sync folder")
        try {
            (cr.openOutputStream(tmp, "w") ?: throw IOException("could not open $finalName for writing")).use { body(it) }
            return DocumentsContract.renameDocument(cr, tmp, finalName) ?: throw IOException("rename to $finalName failed")
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(cr, tmp) }
            throw e
        }
    }

    private fun maybeEnforceCap() {
        val now = System.currentTimeMillis()
        if (now - lastCapCheck < CAP_CHECK_INTERVAL_MS) return
        lastCapCheck = now
        refreshUsage(prune = true)
    }

    private fun refreshUsage(prune: Boolean) {
        val s = app.prefs.syncTreeUri
        if (s.isEmpty()) return
        try {
            val tree = Uri.parse(s)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val files = ArrayList<Triple<String, String, Long>>() // id, name, size
            app.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (NAME_RE.matches(name)) files.add(Triple(c.getString(0), name, if (c.isNull(2)) 0L else c.getLong(2)))
                }
            }
            var total = files.sumOf { it.third }
            if (prune && total > CAP_BYTES) {
                var n = 0; var freed = 0L
                for (f in files.sortedBy { it.second }) {
                    if (total <= CAP_BYTES * 9 / 10) break
                    if (DocumentsContract.deleteDocument(app.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, f.first))) {
                        total -= f.third; freed += f.third; n++
                    }
                }
                app.store.log("SYNC_FOLDER_PRUNED", "Deleted $n oldest Jarvis files ($freed bytes) from the sync folder to stay under ${CAP_BYTES / 1_000_000} MB")
                folderFiles = files.size - n
            } else folderFiles = files.size
            folderBytes = total
        } catch (e: Exception) {
            fail("folder unavailable", e)
        }
    }

    private fun markOk() {
        if (ok == false) app.store.log("SYNC_FOLDER_OK", "Sync folder is reachable again: ${label()}")
        ok = true
        problem = ""
    }

    private fun fail(what: String, e: Exception) {
        ok = false
        problem = "$what: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
        val now = System.currentTimeMillis()
        if (now - lastFailLog > 60_000) {
            lastFailLog = now
            app.store.log("SYNC_FAILED", problem + " – recordings stay on the phone and are copied later",
                JSONObject().put("folder", label()))
        }
    }

    // ------------------------------------------------------------------ shared with the exporter

    fun appInfo(): JSONObject = JSONObject().put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
        .put("git", BuildConfig.GIT_SHA)

    fun deviceLine() = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

    /** Per-recording diagnostics: recording metadata (levels, threshold, sensitivity…), its events and recognitions. */
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
            .put("app", appInfo())
            .put("device", deviceLine())
    }

    private fun csv(v: String?): String {
        val s = v ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
