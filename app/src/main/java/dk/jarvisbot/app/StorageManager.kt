package dk.jarvisbot.app

import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One storage limit for everything Jarvis keeps: recordings on the phone, logs/transcriptions, and Jarvis's files in
 * the diagnostic folder (exported ZIPs there are the user's and are only reported). Android always keeps a reserve of
 * free space. When over the limit, or below the reserve, the oldest data goes first:
 *   1. phone copies of recordings that are verified to be in the diagnostic folder (pure duplicates),
 *   2. then the oldest recordings / per-recording files / session logs everywhere (labelled recordings last).
 * Daily transcription and diagnostics files are small and are not deleted automatically.
 * Runs on the diagnostic-folder thread.
 */
object StorageManager {
    val LIMITS_MB = listOf(500, 1000, 2000, 5000, 10000)
    private const val SCAN_MAX_AGE_MS = 15 * 60_000L

    class Usage(
        val phoneAudio: Long, val phoneOther: Long, val folderJarvis: Long, val folderExports: Long, val folderFiles: Int,
        val folderKnown: Boolean, val free: Long, val reserve: Long, val limit: Long, val ts: Long,
    ) {
        val total get() = phoneAudio + phoneOther + folderJarvis
    }

    @Volatile var last: Usage? = null
        private set
    @Volatile private var scan: DiagnosticFolder.Scan? = null
    private val addedSinceScan = AtomicLong()
    private val checkPending = AtomicBoolean(false)
    private var lastLimitLog = 0L
    private val app get() = JarvisApp.instance

    fun label(mb: Int) = if (mb >= 1000) "${mb / 1000} GB" else "$mb MB"
    fun limitBytes() = app.prefs.storageLimitTotalMb * 1_000_000L

    /** Space always left free for Android: 1 GB or 5 % of the storage, whichever is larger. */
    fun reserveBytes(): Long = maxOf(1_000_000_000L, StatFs(app.filesDir.path).totalBytes / 20)

    /** A file was added to the diagnostic folder (keeps the estimate current between scans). */
    fun added(bytes: Long) { addedSinceScan.addAndGet(bytes) }

    fun invalidate() { scan = null }

    /** Check soon (debounced), e.g. after a recording was saved or the limit changed. */
    fun requestCheck(delayMs: Long = 20_000) {
        if (checkPending.compareAndSet(false, true)) DiagnosticFolder.runOnExecutor(delayMs) { checkPending.set(false); enforceNow(false) }
    }

    private fun dirBytes(d: File): Long = d.listFiles()?.sumOf { if (it.isDirectory) dirBytes(it) else it.length() } ?: 0L

    private fun currentScan(force: Boolean): DiagnosticFolder.Scan? {
        if (!DiagnosticFolder.isConfigured()) { scan = null; return null }
        val s = scan
        if (!force && s != null && System.currentTimeMillis() - s.ts < SCAN_MAX_AGE_MS) return s
        return DiagnosticFolder.scanNow()?.also { scan = it; addedSinceScan.set(0) }
    }

    /** Measures usage (folder thread). */
    fun usageNow(forceScan: Boolean): Usage {
        val store = app.store
        val s = currentScan(forceScan)
        val st = StatFs(app.filesDir.path)
        return Usage(
            phoneAudio = store.audioBytes(),
            phoneOther = store.dbFileBytes() + dirBytes(Transcription.dir) + dirBytes(File(app.cacheDir, "exports")),
            folderJarvis = (s?.jarvisBytes ?: 0L) + if (s != null) addedSinceScan.get() else 0L,
            folderExports = s?.exportBytes ?: 0L,
            folderFiles = s?.files ?: 0,
            folderKnown = s != null,
            free = st.availableBytes,
            reserve = reserveBytes(),
            limit = limitBytes(),
            ts = System.currentTimeMillis(),
        ).also { last = it }
    }

    private fun base(name: String): String? {
        if (!DiagnosticFolder.NAME_RE.matches(name)) return null
        for (suf in listOf("_raw.wav", "_enhanced.wav", "_diagnostics.json", "-log.csv", ".json")) if (name.endsWith(suf)) return name.removeSuffix(suf)
        return null
    }

    /** Applies the limit and the free-space reserve (folder thread). */
    fun enforceNow(forceScan: Boolean) {
        val store = app.store
        var u = usageNow(forceScan)
        if (DiagnosticFolder.isConfigured() && !u.folderKnown) return // folder not readable right now: decide later, with full information
        if (!app.prefs.sp.contains("storageLimitTotalMb")) {
            // First start with the single limit: never delete data that was already kept under the old limits.
            val neededMb = (u.total * 1.25 / 1e6).toLong()
            val mb = LIMITS_MB.firstOrNull { it >= 2000 && it >= neededMb } ?: LIMITS_MB.last()
            app.prefs.storageLimitTotalMb = mb
            store.log("STORAGE_LIMIT_SET", "Storage limit set to ${label(mb)}" +
                if (mb > 2000) " so the ${mb(u.total)} already stored are kept (change it on the main screen)" else " (default)",
                JSONObject().put("limitMb", mb).put("usageBytes", u.total))
            u = usageNow(false)
        }
        val overLimit = u.total - u.limit
        val belowReserve = u.reserve - u.free
        if (overLimit <= 0 && belowReserve <= 0) return
        val s = currentScan(force = true) // decisions need an up-to-date view of the folder
        var toFree = maxOf(overLimit, belowReserve) + u.limit / 20 // 5 % headroom so cleanup does not run constantly
        var phoneCopies = 0; var phoneBytes = 0L
        var removed = 0; var removedBytes = 0L
        val deleted = HashSet<String>() // doc ids
        val rows = store.allRecordings().associateBy { it.id }
        val delivered = store.deliveredBases()

        // 1) Phone copies of recordings that are safely in the diagnostic folder (same name and size there).
        if (s != null) {
            val folderRaw = s.entries.filter { it.name.endsWith("_raw.wav") }.associateBy { it.name }
            for ((b, id) in delivered.entries.sortedBy { it.key }) {
                if (toFree <= 0) break
                val r = rows[id] ?: continue
                val f = store.fileFor(r)
                if (!f.exists()) continue
                val e = folderRaw["${b}_raw.wav"] ?: continue
                if (e.size != f.length()) continue
                if (app.prefs.transcriptionMode == "AUTO" && store.transcript(id, "RAW") == null) continue // still needed
                val len = f.length()
                if (f.delete()) { toFree -= len; phoneBytes += len; phoneCopies++ }
            }
        }

        // 2) The oldest recordings (and session logs) everywhere: phone copy, folder files and database row.
        if (toFree > 0) {
            class Group(val base: String) { var row: DiagStore.RecordingRow? = null; var orphan: File? = null; val entries = ArrayList<DiagnosticFolder.Entry>() }
            val units = sortedMapOf<String, Group>()
            s?.entries?.forEach { e -> base(e.name)?.let { b -> units.getOrPut(b) { Group(b) }.entries.add(e) } }
            val idToBase = delivered.entries.associate { it.value to it.key }
            for (r in rows.values) {
                if (r.category == "RECORDING") continue
                val b = idToBase[r.id]?.takeIf { it.length >= 23 } ?: DiagnosticFolder.baseName(r)
                val unit = units[b] ?: if (store.fileFor(r).exists()) units.getOrPut(b) { Group(b) } else null
                unit?.row = r
            }
            // audio files on the phone without a database entry (e.g. the app was killed while saving): invisible in the app
            val known = rows.values.map { it.file }.toHashSet()
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US)
            store.audioDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name !in known && System.currentTimeMillis() - f.lastModified() > 3_600_000L)
                    units.getOrPut(stamp.format(Date(f.lastModified())) + "_orphan-" + f.name) { Group(stamp.format(Date(f.lastModified())) + "_orphan-" + f.name) }.orphan = f
            }
            val today = DailyReports.dayOf(System.currentTimeMillis())
            for (pass in 0..1) {
                for (unit in units.values) {
                    if (toFree <= 0) break
                    val r = unit.row
                    if (r?.category == "RECORDING") continue
                    if (pass == 0 && r?.label != null) continue // labelled recordings are kept as long as possible
                    if (unit.entries.isEmpty() && r == null && unit.orphan == null) continue
                    if (unit.base.endsWith("_session") && unit.base.startsWith(today)) continue // today's session logs are still growing
                    var freed = 0L
                    unit.orphan?.let { f -> val len = f.length(); if (f.delete()) freed += len }
                    r?.let { row ->
                        val f = store.fileFor(row)
                        if (f.exists()) { val len = f.length(); if (f.delete()) freed += len }
                    }
                    for (e in unit.entries) if (e.docId !in deleted && DiagnosticFolder.deleteEntry(e)) { deleted.add(e.docId); freed += e.size }
                    r?.let { row -> store.deleteRecordingRow(row.id); store.forgetDelivered(row.id) }
                    unit.entries.clear(); unit.row = null
                    if (freed > 0) { removed++; removedBytes += freed; toFree -= freed }
                }
            }
            // hour folders left empty
            if (s != null && deleted.isNotEmpty()) {
                val remaining = s.entries.filter { it.docId !in deleted }.map { it.path }.toHashSet()
                for (d in s.dirs) if (d.path.isNotEmpty() && "${d.path}/${d.name}" !in remaining) DiagnosticFolder.deleteEntry(d)
                DiagnosticFolder.forgetDirs()
            }
        }

        if (phoneCopies > 0 || removed > 0) {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val why = if (overLimit > 0) "Jarvis data (${mb(u.total)}) was over the ${label(app.prefs.storageLimitTotalMb)} limit"
            else "free space on the phone (${gb(u.free)}) was below the ${gb(u.reserve)} kept for Android"
            val what = ArrayList<String>()
            if (phoneCopies > 0) what.add("removed $phoneCopies phone copies of recordings that are already in the diagnostic folder (${mb(phoneBytes)})")
            if (removed > 0) what.add("deleted the $removed oldest recordings/logs from the phone and the diagnostic folder (${mb(removedBytes)})")
            val text = "$stamp: $why – ${what.joinToString("; ")}."
            app.prefs.lastCleanupText = text
            store.log("STORAGE_CLEANUP", text, JSONObject().put("phoneCopiesRemoved", phoneCopies).put("phoneCopyBytes", phoneBytes)
                .put("oldestRemoved", removed).put("oldestBytes", removedBytes).put("totalBefore", u.total).put("limit", u.limit)
                .put("free", u.free).put("reserve", u.reserve))
            scan = null
            usageNow(forceScan = true)
        } else if (System.currentTimeMillis() - lastLimitLog > 3_600_000L) {
            lastLimitLog = System.currentTimeMillis()
            store.log("STORAGE_LIMIT_REACHED", "Over the storage limit or low on free space, but nothing more can be deleted automatically",
                JSONObject().put("total", u.total).put("limit", u.limit).put("free", u.free).put("reserve", u.reserve))
        }
    }

    fun usageJson(): JSONObject {
        val u = last ?: return JSONObject().put("note", "not measured yet")
        return JSONObject().put("limitBytes", u.limit).put("totalBytes", u.total).put("phoneRecordingsBytes", u.phoneAudio)
            .put("phoneLogsAndTranscriptionsBytes", u.phoneOther).put("diagnosticFolderBytes", u.folderJarvis)
            .put("diagnosticFolderFiles", u.folderFiles).put("exportsInFolderBytes", u.folderExports)
            .put("freeOnPhoneBytes", u.free).put("reservedForAndroidBytes", u.reserve).put("measured", DiagStore.iso(u.ts))
            .put("lastCleanup", app.prefs.lastCleanupText)
    }

    private fun mb(b: Long) = "%.0f MB".format(b / 1e6)
    private fun gb(b: Long) = "%.1f GB".format(b / 1e9)

    fun summaryText(): String {
        val u = last ?: return "Measuring…"
        val sb = StringBuilder("Jarvis uses ${mb(u.total)} of ${label(app.prefs.storageLimitTotalMb)}: ${mb(u.phoneAudio)} recordings on the phone, " +
            "${mb(u.phoneOther)} logs and transcriptions")
        if (u.folderKnown) sb.append(", ${mb(u.folderJarvis)} in the diagnostic folder (${u.folderFiles} files)")
        sb.append(".")
        if (u.folderExports > 0) sb.append(" Exported ZIPs in the folder: ${mb(u.folderExports)} (not counted, never deleted automatically).")
        sb.append("\nFree on the phone: ${gb(u.free)} (at least ${gb(u.reserve)} is always left for Android).")
        sb.append("\nWhen over the limit, phone copies of recordings already in the diagnostic folder are removed first, then the oldest recordings.")
        app.prefs.lastCleanupText.takeIf { it.isNotEmpty() }?.let { sb.append("\nLast automatic cleanup: $it") }
        return sb.toString()
    }
}
