package dk.jarvisbot.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deliberately plain UI.
 * Main screen: listening, sensitivity, image, sync folder, export, storage, recordings.
 * "Wake word & recognition" screen: the original wake-word prototype controls (Control / Commands / Events).
 * Technical parameters are no longer shown; they keep their internal defaults (and any earlier saved values).
 */
class MainActivity : Activity() {

    private lateinit var app: JarvisApp
    private val store get() = app.store
    private val prefs get() = app.prefs
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var playingId: String? = null
    private var tick = 0

    // advanced (wake word) screen
    private var fullUi = false
    private lateinit var content: FrameLayout
    private var currentTab = "Control"
    private var statusView: TextView? = null
    private var levelBar: ProgressBar? = null
    private var scoreBar: ProgressBar? = null
    private var vadBar: ProgressBar? = null
    private var thresholdLabel: TextView? = null
    private var listRefresher: (() -> Unit)? = null
    private var slowInfo = ""

    // main screen
    private var micStateView: TextView? = null
    private var micMeter: LevelMeter? = null
    private var micLevelText: TextView? = null
    private var micInfoText: TextView? = null
    private var historyBox: LinearLayout? = null
    private var historySig = 0
    private var syncText: TextView? = null
    private var storageText: TextView? = null
    private var imageText: TextView? = null
    private var exportText: TextView? = null
    private var micInterrupted: String? = null
    private var micStartRequestedTs = 0L
    private var pendingMicStart = false
    @Volatile private var exporting = false

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_EXPORT = 2
        private const val REQ_FOLDER = 3
        private const val REQ_IMAGE = 4
        private val dateTimeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        private val msFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        const val IMAGE_DIR = "display"
        const val IMAGE_FILE = "chosen_image"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as JarvisApp
        Recognition.onUpdate = { runOnUiThread { if (fullUi && currentTab == "Commands") listRefresher?.invoke() } }
        cleanupOldExports()
        showMain()
        if (!Diagnostics.hasMicPermission(this)) requestPerms()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        store.log("UI_RESUMED", "", JSONObject().put("serviceRunning", LiveStatus.serviceRunning).put("micTestRunning", MicStatus.running))
        checkMicTestInterrupted()
        autostartWakeWordIfEnabled()
        SyncFolder.refreshUsageAsync()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        stopPlayback()
        Recognition.onUpdate = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply { this.text = text; isAllCaps = false; setOnClickListener { onClick() } }
    private fun tv(text: String, size: Float = 14f, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (mono) typeface = Typeface.MONOSPACE
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }
    private fun header(text: String) = tv(text, 16f, bold = true).apply { setPadding(dp(4), dp(18), dp(4), dp(2)) }
    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }
    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
    private fun scroll(v: View) = ScrollView(this).apply { addView(v) }
    private fun bar() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
    private fun spinner(items: List<String>, selected: String, onSelect: (String) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(maxOf(0, items.indexOf(selected)))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onSelect(items[pos])
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
    private fun info(title: String, msg: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }
    private fun fmtTs(ts: Long) = if (ts <= 0) "-" else dateTimeFmt.format(Date(ts))
    private fun mb(bytes: Long) = "%.0f MB".format(bytes / 1_000_000.0)

    // ================================================================== MAIN SCREEN

    private fun showMain() {
        fullUi = false
        statusView = null; levelBar = null; scoreBar = null; vadBar = null; listRefresher = null
        val l = vbox()

        l.addView(tv("Jarvis", 22f, bold = true))
        micStateView = tv("", 17f, bold = true)
        l.addView(micStateView)
        l.addView(row(
            btn("START LISTENING") { startMicTest() }.apply { textSize = 16f },
            btn("STOP LISTENING") { stopMicTest() }.apply { textSize = 16f }
        ))
        micMeter = LevelMeter(this)
        l.addView(micMeter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { setMargins(0, dp(6), 0, dp(2)) })
        micLevelText = tv("", 15f, mono = true)
        l.addView(micLevelText)
        micInfoText = tv("", 12f, mono = true)
        l.addView(micInfoText)

        // Sensitivity
        val sensLabel = tv("", 15f, bold = true)
        fun showSens(v: Int) { sensLabel.text = "Sensitivity %d  →  saves sounds louder than %.0f dBFS".format(v, MicTest.thresholdDb(v)) }
        showSens(prefs.micSensitivity)
        l.addView(sensLabel)
        l.addView(SeekBar(this).apply {
            max = 100
            progress = prefs.micSensitivity
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    prefs.micSensitivity = v // read live by the listening service; no restart needed
                    showSens(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val v = prefs.micSensitivity
                    store.log("MICTEST_SENSITIVITY_CHANGED", "sensitivity $v → threshold %.0f dBFS".format(MicTest.thresholdDb(v)),
                        JSONObject().put("sensitivity", v).put("thresholdDb", MicTest.thresholdDb(v)), interactionId = MicStatus.sessionId.ifEmpty { null })
                }
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        l.addView(tv("← only loud sounds                                   quiet sounds too →", 11f))
        l.addView(btn("Raw 30 s microphone test (saves everything, ignores the threshold)") {
            ensureMic {
                micInterrupted = null
                if (!MicStatus.running) { MicStatus.state = "STARTING"; MicStatus.message = "Starting…"; micStartRequestedTs = System.currentTimeMillis() }
                ListenerService.send(this, ListenerService.ACTION_MICTEST_RAW, 30)
                toast("Saving everything for 30 s")
            }
        })

        // Image
        l.addView(header("Image"))
        imageText = tv("", 13f)
        l.addView(imageText)
        l.addView(row(btn("Choose Image") { chooseImage() }, btn("Show Image") { startActivity(Intent(this, ImageActivity::class.java)) }))

        // Sync folder
        l.addView(header("Sync folder (picked up by OneSync → OneDrive)"))
        syncText = tv("", 13f)
        l.addView(syncText)
        l.addView(row(btn("Choose folder…") { chooseSyncFolder() }, btn("Stop copying") {
            if (!SyncFolder.isConfigured()) toast("No sync folder is set")
            else confirm("Stop copying new recordings to ${SyncFolder.label()}? Files already there are kept.") { SyncFolder.disable(this); updateTexts() }
        }))

        // Export
        l.addView(header("Export (ZIP with recordings + diagnostic logs)"))
        val modes = listOf("RAW" to "Raw audio (original)", "ENHANCED" to "Voice-enhanced audio", "BOTH" to "Both raw and voice-enhanced")
        l.addView(RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            modes.forEachIndexed { i, (key, label) ->
                addView(RadioButton(this@MainActivity).apply {
                    id = 1000 + i; text = label; isChecked = prefs.exportAudioMode == key
                })
            }
            setOnCheckedChangeListener { _, checkedId -> prefs.exportAudioMode = modes[checkedId - 1000].first }
        })
        l.addView(row(btn("Save ZIP…") { saveExport() }, btn("Share ZIP…") { shareExport() }))
        exportText = tv("Originals are never changed. Voice enhancement runs only during export.", 12f)
        l.addView(exportText)

        // Storage
        l.addView(header("Storage"))
        storageText = tv("", 13f)
        l.addView(storageText)
        l.addView(btn("Clean up…") { cleanupDialog() })

        // Recordings
        l.addView(header("Recordings (tap = play / stop, long-press = more)"))
        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        l.addView(historyBox)
        historySig = 0
        refreshHistory()

        // Footer
        l.addView(header("Speech recognition language"))
        l.addView(spinner(Prefs.LANGUAGES, prefs.language) {
            if (it != prefs.language) { prefs.language = it; store.log("LANGUAGE_CHANGED", it) }
        })
        l.addView(row(
            btn("Wake word & recognition") { showFullUi() },
            btn("Phone settings") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        ))
        l.addView(tv("Build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.GIT_SHA}", 11f))

        setContentView(scroll(l).apply { fitsSystemWindows = true })
        updateTexts()
    }

    private fun updateTexts() {
        imageText?.text = if (File(File(filesDir, IMAGE_DIR), IMAGE_FILE).exists())
            "Selected: ${prefs.displayImageName.ifEmpty { "image" }}" else "No image chosen yet (a placeholder is shown)"
        syncText?.text = when {
            !SyncFolder.isConfigured() -> "Not set. Recordings stay inside the app. Suggested: Documents/Jarvis/Dumps"
            SyncFolder.ok == false -> "⚠ ${SyncFolder.label()} – not reachable (${SyncFolder.problem}). Recordings stay on the phone and are copied when the folder works again. Choose the folder again if this persists."
            else -> "✓ ${SyncFolder.label()}\nNew recordings are copied there automatically (raw WAV + diagnostics JSON)." +
                (if (SyncFolder.lastCopyTs > 0) "\nLast copy: ${fmtTs(SyncFolder.lastCopyTs)}" else "") +
                (if (SyncFolder.folderFiles >= 0) "\nIn folder: ${SyncFolder.folderFiles} Jarvis files, ${mb(SyncFolder.folderBytes)} (max ${mb(SyncFolder.CAP_BYTES)})" else "")
        }
        val stat = StatFs(filesDir.path)
        storageText?.text = "On this phone: ${mb(store.audioBytes())} audio (limit ${mb(prefs.storageLimitMb * 1048576L)}, oldest deleted automatically), " +
            "${mb(store.dbFileBytes())} logs. Free: %.1f GB".format(stat.availableBytes / 1e9)
    }

    private fun refreshHistory(force: Boolean = false) {
        val box = historyBox ?: return
        val items = store.recordings(null, 60)
        val sig = items.joinToString("|") { it.id + it.category + it.bytes + it.label } .hashCode() + (playingId?.hashCode() ?: 0)
        if (!force && sig == historySig) return
        historySig = sig
        box.removeAllViews()
        if (items.isEmpty()) { box.addView(tv("No recordings yet.", 13f)); return }
        for (r in items) {
            val meta = r.meta?.let { runCatching { JSONObject(it) }.getOrNull() }
            val peak = meta?.optDouble("peakDb", Double.NaN) ?: Double.NaN
            val peakTxt = if (peak.isNaN()) "" else " · loudest %.0f dBFS".format(peak)
            val what = when {
                r.kind != "MICTEST" -> "${r.kind.lowercase()}${r.category?.let { "/" + it.lowercase() } ?: ""}" +
                    (r.maxScore?.let { " · wake %.2f".format(it) } ?: "")
                r.category == "RECORDING" -> "recording…"
                r.category == "RAW" -> "raw test$peakTxt"
                r.category == "ALL_ZEROS" -> "ALL ZEROS (mic silenced)"
                r.category == "INTERRUPTED" -> "interrupted$peakTxt"
                else -> "sound$peakTxt"
            }
            val synced = if (SyncFolder.isConfigured() && store.syncedName(r.id) != null) " · copied" else ""
            val t = tv("${if (r.id == playingId) "▶ " else ""}${fmtTs(r.tsStart)} · %.0f s · %s%s%s".format(
                r.durationMs / 1000.0, what, r.label?.let { " [$it]" } ?: "", synced), 14f).apply {
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { if (playingId == r.id) stopPlayback() else play(r); refreshHistory(true) }
                setOnLongClickListener { recordingActions(r) { refreshHistory(true) }; true }
            }
            box.addView(t)
            box.addView(View(this).apply { setBackgroundColor(Color.rgb(70, 70, 70)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }
    }

    private fun cleanupDialog() {
        val opts = arrayOf("Delete recordings older than 7 days", "Delete ALL recordings and logs on this phone")
        AlertDialog.Builder(this).setTitle("Clean up (files in the sync folder are not touched)").setItems(opts) { _, w ->
            when (w) {
                0 -> confirm("Delete recordings older than 7 days from the phone?") {
                    val (n, b) = store.deleteRecordingsBefore(System.currentTimeMillis() - 7 * 86_400_000L)
                    store.log("USER_CLEANUP", "Deleted $n recordings older than 7 days ($b bytes)")
                    toast("Deleted $n recordings (${mb(b)})"); refreshHistory(true); updateTexts()
                }
                1 -> confirm("Delete ALL recordings, recognitions and logs on this phone? This cannot be undone.") {
                    stopPlayback()
                    store.deleteAll()
                    store.log("USER_DELETED_ALL_DATA", "")
                    toast("All local recordings and logs deleted"); refreshHistory(true); updateTexts()
                }
            }
        }.setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ microphone listening (main screen)

    private fun startMicTest() {
        store.log("MICTEST_START_PRESSED", "", JSONObject().put("permissions", Diagnostics.permissions(this)), sync = true)
        micInterrupted = null
        if (!Diagnostics.hasMicPermission(this)) {
            store.log("MICTEST_FAILED", "Microphone permission not granted – asking for it", sync = true)
            pendingMicStart = true
            requestPerms()
            return
        }
        MicStatus.state = "STARTING"
        MicStatus.message = "Starting…"
        micStartRequestedTs = System.currentTimeMillis()
        if (!ListenerService.send(this, ListenerService.ACTION_MICTEST_START)) {
            MicStatus.state = "FAILED"
            MicStatus.message = "Could not start the listening service: ${LiveStatus.lastError}"
            store.log("MICTEST_FAILED", MicStatus.message, sync = true)
        }
    }

    private fun stopMicTest() {
        store.log("MICTEST_STOP_PRESSED", "")
        micStartRequestedTs = 0
        if (ListenerService.instance != null) ListenerService.send(this, ListenerService.ACTION_MICTEST_STOP)
        handler.postDelayed({ refreshHistory(true); updateTexts() }, 1500)
    }

    /** If listening was active but the service is gone (Android killed the app), say so clearly. */
    private fun checkMicTestInterrupted() {
        if (prefs.micTestActive && ListenerService.instance?.micTest == null) {
            val last = prefs.lastHeartbeatTs
            micInterrupted = "⚠ Listening stopped unexpectedly – Android probably stopped the app. Last sign of life: ${fmtTs(last)}. Press Start to listen again."
            store.log("MICTEST_INTERRUPTED", "Listening was active, but the service was not running when the app was opened",
                JSONObject().put("lastHeartbeatTs", last).put("lastHeartbeatIso", DiagStore.iso(last)), sync = true)
            prefs.micTestActive = false
        }
    }

    private fun updateMicTest() {
        val sv = micStateView ?: return
        val s = MicStatus
        val now = System.currentTimeMillis()
        if (!s.running && s.state == "STARTING" && micStartRequestedTs > 0 && now - micStartRequestedTs > 4000) {
            s.state = "FAILED"
            s.message = "Listening did not start. ${LiveStatus.lastError}"
            store.log("MICTEST_FAILED", "Service did not start within 4 s: ${LiveStatus.lastError}", sync = true)
        }
        val (text, color) = when {
            s.state == "FAILED" -> "🔴 MICROPHONE PROBLEM: ${s.message}" to Color.rgb(255, 90, 90)
            !s.running && micInterrupted != null -> micInterrupted!! to Color.rgb(255, 170, 60)
            !s.running && s.state == "STOPPED" -> "⚪ Not listening. Last session: ${s.message}" to Color.LTGRAY
            !s.running -> "⚪ Not listening" to Color.LTGRAY
            s.state == "LISTENING" -> "🟢 Listening" to Color.rgb(90, 220, 110)
            s.state == "NO_AUDIO" || s.state == "ONLY_ZEROS" -> "🟠 ${s.message}" to Color.rgb(255, 170, 60)
            else -> "⏳ ${s.message}" to Color.LTGRAY
        }
        sv.text = text
        sv.setTextColor(color)
        val live = s.running && now - s.lastSampleTs < 1000
        val thr = MicTest.thresholdDb(prefs.micSensitivity)
        micMeter?.set(if (live) s.peakDb else -120.0, if (s.samples > 0) s.maxPeakDb else -120.0, thr)
        val recTxt = when {
            !s.running -> ""
            s.recordingNow && now < s.rawUntil -> "● SAVING (raw test, %d s left)".format((s.rawUntil - now) / 1000 + 1)
            s.recordingNow -> "● SAVING – %.0f s".format((now - s.recordingSinceTs) / 1000.0)
            else -> "below threshold – not saving"
        }
        micLevelText?.text = if (live) "Peak %.0f dBFS · threshold %.0f\n%s".format(s.peakDb, thr, recTxt)
            else if (s.samples > 0) "No live level. Loudest this session: %.0f dBFS".format(s.maxPeakDb) else "No audio received yet"
        micLevelText?.setTextColor(if (live && s.recordingNow) Color.rgb(255, 110, 110) else Color.LTGRAY)
        val elapsed = if (s.startTs > 0) (if (s.running) now else s.lastSampleTs.coerceAtLeast(s.startTs)) - s.startTs else 0
        micInfoText?.text = if (s.startTs == 0L) "No listening session yet"
            else "%s %d:%02d · %d saved · mic silenced by Android: %s".format(
                if (s.running) "Listening" else "Last session", elapsed / 60000, (elapsed / 1000) % 60, s.files,
                LiveStatus.micSilencedByOs?.let { if (it) "YES" else "no" } ?: "unknown")
        if (tick++ % 30 == 0) { refreshHistory(); updateTexts() }
    }

    // ------------------------------------------------------------------ image

    private fun chooseImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        runCatching { startActivityForResult(Intent.createChooser(i, "Choose an image"), REQ_IMAGE) }.onFailure { toast("No image picker available: $it") }
    }

    private fun onImageChosen(uri: Uri) {
        Thread {
            val res = runCatching {
                var name = "image"
                var size = -1L
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) { name = c.getString(0) ?: name; if (!c.isNull(1)) size = c.getLong(1) }
                }
                if (size > 60_000_000L) throw IllegalArgumentException("image is too large (${mb(size)})")
                val dir = File(filesDir, IMAGE_DIR).apply { mkdirs() }
                val tmp = File(dir, "$IMAGE_FILE.tmp")
                (contentResolver.openInputStream(uri) ?: throw IllegalStateException("cannot open the image")).use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                if (!ImageActivity.canDecode(tmp)) { tmp.delete(); throw IllegalArgumentException("this file cannot be shown as an image") }
                val dst = File(dir, IMAGE_FILE)
                dst.delete()
                if (!tmp.renameTo(dst)) throw IllegalStateException("could not store the image")
                prefs.displayImageName = name
                store.log("IMAGE_SELECTED", name, JSONObject().put("bytes", dst.length()).put("mime", contentResolver.getType(uri)))
                name
            }
            runOnUiThread {
                res.onSuccess { toast("Image selected: $it"); updateTexts() }
                    .onFailure { toast("Could not use this image: ${it.message}"); store.log("IMAGE_SELECT_FAILED", it.toString()) }
            }
        }.start()
    }

    // ------------------------------------------------------------------ sync folder

    private fun chooseSyncFolder() {
        AlertDialog.Builder(this).setTitle("Choose the sync folder")
            .setMessage("In the next screen open \"Documents\", create the folder \"Jarvis\" and inside it \"Dumps\" (⋮ or folder icon → New folder), " +
                "open Dumps and tap \"Use this folder\" → Allow.\n\nThen point OneSync (Autosync for OneDrive) at that same folder.")
            .setPositiveButton("Continue") { _, _ ->
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                runCatching {
                    i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents"))
                }
                runCatching { startActivityForResult(i, REQ_FOLDER) }.onFailure { toast("No folder picker available: $it") }
            }.setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ export

    private fun saveExport() {
        if (exporting) { toast("An export is already running"); return }
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
            .putExtra(Intent.EXTRA_TITLE, Exporter.fileName())
        startActivityForResult(i, REQ_EXPORT)
    }

    private fun runExport(open: () -> java.io.OutputStream, done: (String) -> Unit) {
        val mode = prefs.exportAudioMode
        exporting = true
        exportText?.text = "Exporting…"
        Thread {
            val res = runCatching { open().use { Exporter.write(this, it, mode) { p -> runOnUiThread { exportText?.text = p } } } }
            runOnUiThread {
                exporting = false
                res.onSuccess { exportText?.text = it; done(it) }
                    .onFailure { exportText?.text = "Export failed: $it"; toast("Export failed: $it"); store.log("EXPORT_FAILED", it.toString()) }
            }
        }.start()
    }

    private fun shareExport() {
        if (exporting) { toast("An export is already running"); return }
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, Exporter.fileName())
        runExport({ f.outputStream() }) { summary ->
            val send = Intent(Intent.ACTION_SEND).setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, ExportProvider.uriFor(f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Share Jarvis diagnostics").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            toast(summary)
        }
    }

    private fun cleanupOldExports() {
        File(cacheDir, "exports").listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
    }

    @Deprecated("platform API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT -> runExport({ contentResolver.openOutputStream(uri)!! }) { toast("$it → saved") }
            REQ_FOLDER -> runCatching { SyncFolder.configure(this, uri) }
                .onSuccess { toast("Sync folder: ${SyncFolder.label()}"); updateTexts() }
                .onFailure { toast("Could not use this folder: $it"); store.log("SYNC_FOLDER_SELECT_FAILED", it.toString()) }
            REQ_IMAGE -> onImageChosen(uri)
        }
    }

    // ------------------------------------------------------------------ playback & recording actions

    private fun play(r: DiagStore.RecordingRow) {
        stopPlayback()
        val f = store.fileFor(r)
        if (!f.exists()) { toast("File missing: ${r.file}"); return }
        try {
            player = MediaPlayer().apply {
                setDataSource(f.path)
                setOnCompletionListener { stopPlayback(); refreshHistory(true) }
                prepare(); start()
            }
            playingId = r.id
        } catch (e: Exception) {
            toast("Playback failed: $e")
        }
    }

    private fun stopPlayback() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playingId = null
    }

    private fun recordingActions(r: DiagStore.RecordingRow, refresh: () -> Unit) {
        val actions = listOf(
            "Play", "Stop", "Recognize speech (${prefs.language})", "Analyze with wake engine (offline)",
            "Label MISSED_WAKE (I said Hey Jarvis, it did not trigger)",
            "Label FALSE_WAKE (triggered but I did not say it)",
            "Label CORRECT_WAKE", "Label NOT_WAKE (correctly ignored)", "Clear label", "Add note…", "Details (JSON)", "Delete from phone"
        )
        AlertDialog.Builder(this).setTitle(fmtTs(r.tsStart)).setItems(actions.toTypedArray()) { _, which ->
            when (which) {
                0 -> { play(r); refresh() }
                1 -> { stopPlayback(); refresh() }
                2 -> { Recognition.enqueue(r.id, r.interactionId, "MANUAL", prefs.language); toast("Recognizing… (result on the Wake word & recognition screen → Commands)") }
                3 -> {
                    toast("Analyzing…")
                    Thread {
                        val res = runCatching { OfflineAnalyzer.analyze(app, r) }.getOrElse { "Analysis failed: $it" }
                        runOnUiThread { info("Wake engine analysis", res) }
                    }.start()
                }
                4, 5, 6, 7 -> {
                    val label = listOf("MISSED_WAKE", "FALSE_WAKE", "CORRECT_WAKE", "NOT_WAKE")[which - 4]
                    store.setRecordingLabel(r.id, label, null)
                    if ((label == "FALSE_WAKE" || label == "CORRECT_WAKE") && r.interactionId != null) store.labelInteraction(r.interactionId, label)
                    store.log("USER_LABEL", label, JSONObject().put("kind", r.kind).put("category", r.category).put("maxWakeScore", r.maxScore),
                        r.interactionId, r.id)
                    refresh()
                }
                8 -> { store.setRecordingLabel(r.id, null, null); store.log("USER_LABEL", "cleared", recordingId = r.id); refresh() }
                9 -> editText("Note", r.note ?: "") { store.setRecordingLabel(r.id, r.label, it); refresh() }
                10 -> info(r.id, r.toJson().toString(2))
                11 -> confirm("Delete this recording from the phone? (A copy in the sync folder is not touched.)") {
                    if (playingId == r.id) stopPlayback()
                    store.deleteRecording(r.id); store.log("USER_DELETED_RECORDING", r.file, recordingId = r.id); refresh()
                }
            }
        }.show()
    }

    private fun editText(title: String, initial: String, onOk: (String) -> Unit) {
        val et = EditText(this).apply { setText(initial); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("Save") { _, _ -> onOk(et.text.toString()) }.setNegativeButton("Cancel", null).show()
    }

    private fun confirm(msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Yes") { _, _ -> onYes() }.setNegativeButton("No", null).show()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (fullUi) updateStatus() else updateMicTest()
            handler.postDelayed(this, if (fullUi) 250 else 100)
        }
    }

    // ================================================================== WAKE WORD & RECOGNITION SCREEN

    private fun showFullUi() {
        fullUi = true
        micStateView = null; micMeter = null; historyBox = null
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        root.addView(btn("← Back to main screen") { showMain() })
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (t in listOf("Control", "Commands", "Events")) {
            tabs.addView(Button(this).apply {
                text = t; isAllCaps = false; textSize = 13f; minWidth = 0; minimumWidth = 0
                setOnClickListener { showTab(t) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabs)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        showTab("Control")
        autostartWakeWordIfEnabled()
    }

    private fun autostartWakeWordIfEnabled() {
        if (fullUi && prefs.enabled && !LiveStatus.serviceRunning && Diagnostics.hasMicPermission(this)) {
            store.log("SERVICE_AUTOSTART_FROM_UI", "Jarvis is enabled but the service was not running; restarting it now")
            ListenerService.send(this, ListenerService.ACTION_ENABLE)
        }
    }

    private fun showTab(t: String) {
        currentTab = t
        statusView = null; levelBar = null; scoreBar = null; vadBar = null; listRefresher = null
        content.removeAllViews()
        content.addView(when (t) { "Commands" -> commandsTab(); "Events" -> eventsTab(); else -> controlTab() })
    }

    private fun controlTab(): View {
        val l = vbox()
        l.addView(tv("Wake word (“Hey Jarvis”) and speech recognition. This uses the microphone separately from the main screen: " +
            "starting one stops the other.", 12f))
        statusView = tv("", 12f, mono = true)
        l.addView(statusView)
        l.addView(tv("Mic level (dBFS, -90..0)", 12f)); levelBar = bar(); l.addView(levelBar)
        l.addView(tv("Speech probability (VAD)", 12f)); vadBar = bar(); l.addView(vadBar)
        l.addView(tv("Wake-word score (0..1)", 12f)); scoreBar = bar(); l.addView(scoreBar)

        l.addView(header("Wake word"))
        l.addView(row(btn("ENABLE wake word") { enableJarvis() }, btn("DISABLE") {
            ListenerService.send(this, ListenerService.ACTION_DISABLE)
        }))
        l.addView(CheckBox(this).apply {
            text = "Diagnostic Mode (store audio of speech & wake candidates)"
            isChecked = prefs.diagnosticMode
            setOnCheckedChangeListener { _, c ->
                prefs.diagnosticMode = c
                store.log(if (c) "DIAGNOSTIC_MODE_ON" else "DIAGNOSTIC_MODE_OFF", "")
            }
        })
        thresholdLabel = tv("Wake threshold: %.2f".format(prefs.threshold))
        l.addView(thresholdLabel)
        l.addView(SeekBar(this).apply {
            max = 95
            progress = (prefs.threshold * 100).toInt().coerceIn(1, 95)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = maxOf(1, p) / 100f
                    prefs.threshold = v
                    thresholdLabel?.text = "Wake threshold: %.2f".format(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { store.log("THRESHOLD_CHANGED", "%.2f".format(prefs.threshold)) }
            })
        })
        l.addView(btn("It MISSED my “Hey Jarvis” – save the last 15 s") {
            ListenerService.send(this, ListenerService.ACTION_MARK_MISSED); toast("Saved as MISSED_MARK recording")
        })

        l.addView(header("Speech recognition (${prefs.language})"))
        l.addView(CheckBox(this).apply {
            text = "Allow the phone's online recognizer (may send audio to Google). Off = on-device only."
            isChecked = prefs.recognizerMode == "DEFAULT_SERVICE"
            setOnCheckedChangeListener { cb, c ->
                if (c && prefs.recognizerMode != "DEFAULT_SERVICE") {
                    AlertDialog.Builder(this@MainActivity).setTitle("Allow possibly-online recognition?")
                        .setMessage("The default recognition service (usually Google) may send recording audio to its servers. Jarvis itself has no internet permission.")
                        .setPositiveButton("Allow") { _, _ -> prefs.recognizerMode = "DEFAULT_SERVICE"; store.log("ONLINE_RECOGNITION_OPT_IN", "user accepted") }
                        .setNegativeButton("Cancel") { _, _ -> cb.isChecked = false }.show()
                } else if (!c) {
                    prefs.recognizerMode = "ON_DEVICE"; store.log("ONLINE_RECOGNITION_OPT_OUT", "")
                }
            }
        })
        l.addView(btn("Live recognizer test (the recognizer listens itself)") {
            ensureMic { Recognition.enqueueLive(prefs.language); toast("Speak now (${prefs.language})…") }
        })
        l.addView(row(
            btn("Check language support") { Recognition.checkSupport(prefs.language) { runOnUiThread { info("Recognition support", it) } } },
            btn("Download language") { Recognition.triggerDownload(prefs.language) { runOnUiThread { info("Model download", it) } } }
        ))
        l.addView(tv("To recognize a saved recording: main screen → long-press it → Recognize speech.", 12f))

        l.addView(header("Phone"))
        l.addView(row(
            btn("Grant permissions") { requestPerms() },
            btn("Allow background use") {
                store.log("BATTERY_OPT_REQUEST", "User opened ignore-battery-optimizations prompt")
                runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                    .onFailure { toast("Not available: $it") }
            }
        ))
        slowInfo = ""
        return scroll(l)
    }

    private fun enableJarvis() {
        ensureMic { if (ListenerService.send(this, ListenerService.ACTION_ENABLE)) toast("Wake word enabled – say “Hey Jarvis”") }
    }

    private fun ensureMic(action: () -> Unit) {
        if (Diagnostics.hasMicPermission(this)) action()
        else { toast("Microphone permission required"); requestPerms() }
    }

    private fun updateStatus() {
        val sv = statusView ?: return
        val now = System.currentTimeMillis()
        if (tick++ % 8 == 0 || slowInfo.isEmpty()) {
            val perms = Diagnostics.permissions(this)
            val power = Diagnostics.power(this)
            val onDev = if (Build.VERSION.SDK_INT >= 31) runCatching { android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this) }.getOrDefault(false).toString() else "n/a (<Android 12)"
            slowInfo = "Permissions: mic ${perms.optString("RECORD_AUDIO")}, notif ${perms.optString("POST_NOTIFICATIONS")}\n" +
                "Battery opt. ignored: ${power.optBoolean("ignoringBatteryOptimizations")} | bg-restricted ${power.opt("backgroundRestricted")}\n" +
                "Recognizer: ${if (prefs.recognizerMode == "ON_DEVICE") "on-device only" else "online allowed"} | on-device available: $onDev"
        }
        sv.text = buildString {
            append("Wake word: ${if (prefs.enabled) "ENABLED" else "DISABLED"} | state ${LiveStatus.state} | Diag ${if (prefs.diagnosticMode) "ON" else "OFF"}\n")
            append("Mic: ${if (LiveStatus.micOpen) "OPEN" else "CLOSED"} ${LiveStatus.micInfo}\n")
            append("Level %.1f dBFS | VAD %.2f | Wake %.3f (max 5 s %.3f)\n".format(LiveStatus.levelDb, LiveStatus.vadProb, LiveStatus.wakeScore, LiveStatus.wakeScoreMaxRecent))
            append("Wake engine: ${LiveStatus.wakeEngineStatus}\n")
            append("Last activation: ${LiveStatus.lastActivation}\nLast result: ${LiveStatus.lastCommand}\n")
            append("Recognition: ${LiveStatus.recognitionStatus}\n")
            append("Last error: ${LiveStatus.lastError}\n")
            append(slowInfo)
        }
        levelBar?.progress = ((LiveStatus.levelDb + 90) / 90 * 100).toInt().coerceIn(0, 100)
        vadBar?.progress = (LiveStatus.vadProb * 100).toInt().coerceIn(0, 100)
        scoreBar?.progress = (LiveStatus.wakeScore * 100).toInt().coerceIn(0, 100)
    }

    private fun commandsTab(): View {
        val l = vbox()
        val list = ListView(this)
        var items: List<DiagStore.RecognitionRow> = emptyList()
        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val r = items[p]
                val t = (cv as? TextView) ?: tv("", 13f)
                val main = when {
                    r.commandText != null -> "“${r.commandText}”"
                    r.unverifiedText != null -> "⚠ NOT FROM STORED AUDIO: “${r.unverifiedText}”"
                    else -> "✗ ${r.errorName ?: r.status}"
                }
                t.text = "${fmtTs(r.tsStart)} [${r.status}] ${r.language} ${r.source} ${r.latencyMs} ms\n" +
                    "$main\n" +
                    (if (r.text != null && r.text != r.commandText) "raw: ${r.text}\n" else "") +
                    (r.correctedText?.let { "corrected: $it\n" } ?: "") +
                    "engine: ${r.engine} · audio: ${r.audioBinding}" + (r.bytesConsumed?.let { " ${it}/${r.bytesTotal} B" } ?: "") +
                    (r.label?.let { " · [$it]" } ?: "")
                return t
            }
        }
        fun refresh() { items = store.recognitions(500); adapter.notifyDataSetChanged() }
        listRefresher = ::refresh
        l.addView(btn("Refresh") { refresh() })
        l.addView(tv("Tap = play the audio that was recognized · Long-press = correct / mark / re-recognize", 12f))
        list.adapter = adapter
        list.setOnItemClickListener { _, _, p, _ ->
            val r = items[p]
            val rec = r.recordingId?.let { store.recording(it) }
            if (rec == null) toast(if (r.recordingId == null) "Live test – no stored audio" else "Recording no longer stored")
            else if (playingId == rec.id) stopPlayback() else play(rec)
        }
        list.setOnItemLongClickListener { _, _, p, _ ->
            val r = items[p]
            val acts = listOf("Correct text…", "Mark FALSE activation", "Mark CORRECT activation", "Re-recognize (${prefs.language})", "Details (JSON)")
            AlertDialog.Builder(this).setTitle(r.id).setItems(acts.toTypedArray()) { _, w ->
                when (w) {
                    0 -> editText("Correct transcript", r.correctedText ?: r.commandText ?: r.text ?: "") {
                        store.setCorrection(r.id, it.ifBlank { null })
                        store.log("USER_CORRECTED_TEXT", it, JSONObject().put("recognized", r.text).put("language", r.language), r.interactionId, r.recordingId)
                        refresh()
                    }
                    1, 2 -> {
                        val label = if (w == 1) "FALSE_WAKE" else "CORRECT_WAKE"
                        store.setRecognitionLabel(r.id, label)
                        r.interactionId?.let { store.labelInteraction(it, label) }
                        store.log("USER_LABEL", label, null, r.interactionId, r.recordingId)
                        refresh()
                    }
                    3 -> r.recordingId?.let { Recognition.enqueue(it, r.interactionId, "MANUAL", prefs.language); toast("Recognizing…") }
                        ?: toast("No stored audio")
                    4 -> info(r.id, r.toJson().toString(2))
                }
            }.show()
            true
        }
        l.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        refresh()
        return l
    }

    private fun eventsTab(): View {
        val l = vbox()
        val filters = linkedMapOf(
            "All" to null, "Errors / warnings" to listOf("%"), "Microphone test" to listOf("MICTEST"),
            "Sync folder" to listOf("SYNC_"), "Wake word" to listOf("WAKE_", "USER_MARKED", "ACK_BEEP", "SPEECH_REGION"),
            "Commands / recognition" to listOf("COMMAND_", "RECOGNITION"), "Mic / audio" to listOf("MIC_", "AUDIO_", "RECORDING_CLIENTS"),
            "Heartbeat" to listOf("HEARTBEAT"),
            "Service / process" to listOf("SERVICE", "FGS", "PROCESS", "SUSPECTED", "CRASH", "JARVIS_", "WAKELOCK", "TASK_", "MEMORY"),
            "Screen / power" to listOf("DEVICE_"), "Image / export" to listOf("IMAGE_", "EXPORT", "ENHANCEMENT"),
        )
        var filter = "All"
        val list = ListView(this)
        var items: List<DiagStore.EventRow> = emptyList()
        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val e = items[p]
                val t = (cv as? TextView) ?: tv("", 12f)
                t.text = "${msFmt.format(Date(e.ts))} ${e.type}\n${e.msg ?: ""}"
                return t
            }
        }
        fun refresh() {
            val f = filters[filter]
            items = if (filter == "Errors / warnings") store.events(null, 500, errorsOnly = true) else store.events(f, 500)
            adapter.notifyDataSetChanged()
        }
        listRefresher = ::refresh
        l.addView(row(spinner(filters.keys.toList(), filter) { filter = it; refresh() }, btn("Refresh") { refresh() }))
        list.adapter = adapter
        list.setOnItemClickListener { _, _, p, _ ->
            val e = items[p]
            val data = e.data?.let { runCatching { JSONObject(it).toString(2) }.getOrDefault(it) } ?: ""
            info(e.type, "${DiagStore.iso(e.ts)}\n${e.msg ?: ""}\ninteraction: ${e.interactionId ?: "-"}\nrecording: ${e.recordingId ?: "-"}\nprocess: ${e.proc}\n\n$data")
        }
        l.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        refresh()
        return l
    }

    // ------------------------------------------------------------------ permissions

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val o = JSONObject()
        permissions.forEachIndexed { i, p -> o.put(p.substringAfterLast('.'), if (grantResults.getOrNull(i) == 0) "GRANTED" else "DENIED") }
        store.log("PERMISSION_RESULT", o.toString(), o)
        if (pendingMicStart) {
            pendingMicStart = false
            if (Diagnostics.hasMicPermission(this)) startMicTest()
            else {
                MicStatus.state = "FAILED"
                MicStatus.message = "Microphone permission was denied. Tap 'Phone settings' → Permissions → Microphone → Allow, then press Start again."
                store.log("MICTEST_FAILED", "Microphone permission denied by user", sync = true)
            }
        }
    }
}

/** Big, simple level bar: −90 dBFS (empty) … 0 dBFS (full), marker for the loudest level, orange line = threshold. */
class LevelMeter(ctx: Context) : View(ctx) {
    private var db = -120.0
    private var maxDb = -120.0
    private var thrDb = Double.NaN
    private val bg = Paint().apply { color = Color.rgb(85, 85, 85) }
    private val fg = Paint()
    private val mark = Paint().apply { color = Color.WHITE; strokeWidth = 6f }
    private val thrPaint = Paint().apply { color = Color.rgb(255, 150, 0); strokeWidth = 10f }

    fun set(level: Double, loudest: Double, threshold: Double = Double.NaN) {
        if (level == db && loudest == maxDb && threshold == thrDb) return
        db = level; maxDb = loudest; thrDb = threshold; invalidate()
    }

    private fun frac(d: Double) = ((d + 90.0) / 90.0).coerceIn(0.0, 1.0).toFloat()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, bg)
        fg.color = when { db > -6 -> Color.rgb(230, 60, 60); db > -30 -> Color.rgb(230, 200, 60); else -> Color.rgb(70, 200, 90) }
        c.drawRect(0f, 0f, w * frac(db), h, fg)
        if (maxDb > -120.0) { val x = w * frac(maxDb); c.drawLine(x, 0f, x, h, mark) }
        if (!thrDb.isNaN()) { val x = w * frac(thrDb); c.drawLine(x, 0f, x, h, thrPaint) }
    }
}
