package dk.jarvisbot.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
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

/** Deliberately plain development UI. */
class MainActivity : Activity() {

    private lateinit var app: JarvisApp
    private val store get() = app.store
    private val prefs get() = app.prefs
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: FrameLayout
    private var currentTab = "Control"
    private var statusView: TextView? = null
    private var levelBar: ProgressBar? = null
    private var scoreBar: ProgressBar? = null
    private var vadBar: ProgressBar? = null
    private var thresholdLabel: TextView? = null
    private var player: MediaPlayer? = null
    private var playingId: String? = null
    private var listRefresher: (() -> Unit)? = null
    private var slowInfo = ""
    private var tick = 0
    private var includeAudioInExport = true
    private var fullUi = false
    // microphone test screen
    private var micStateView: TextView? = null
    private var micMeter: LevelMeter? = null
    private var micLevelText: TextView? = null
    private var micInfoText: TextView? = null
    private var micListRefresh: (() -> Unit)? = null
    private var micInterrupted: String? = null
    private var micStartRequestedTs = 0L
    private var pendingMicStart = false

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_EXPORT = 2
        private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        private val dateTimeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        private val msFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as JarvisApp
        Recognition.onUpdate = { runOnUiThread { if (fullUi && currentTab == "Commands") listRefresher?.invoke() } }
        cleanupOldExports()
        showMicTest()
        if (!Diagnostics.hasMicPermission(this)) requestPerms()
    }

    /** The original wake-word prototype UI (tabs). */
    private fun showFullUi() {
        fullUi = true
        micStateView = null; micMeter = null
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        // Two rows of three tabs so every tab is always reachable on narrow phone screens.
        for (rowTabs in listOf(listOf("Control", "Recordings", "Commands"), listOf("Events", "Settings", "Export"))) {
            val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (t in rowTabs) {
                tabs.addView(Button(this).apply {
                    text = t; isAllCaps = false; textSize = 13f; minWidth = 0; minimumWidth = 0
                    setOnClickListener { showTab(t) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            root.addView(tabs)
        }
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

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        store.log("UI_RESUMED", "", JSONObject().put("serviceRunning", LiveStatus.serviceRunning).put("micTestRunning", MicStatus.running))
        checkMicTestInterrupted()
        autostartWakeWordIfEnabled()
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
        setTextIsSelectable(false)
    }
    private fun header(text: String) = tv(text, 16f, bold = true).apply { setPadding(dp(4), dp(16), dp(4), dp(4)) }
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

    private fun showTab(t: String) {
        currentTab = t
        statusView = null; levelBar = null; scoreBar = null; vadBar = null; listRefresher = null
        content.removeAllViews()
        content.addView(
            when (t) {
                "Recordings" -> recordingsTab()
                "Commands" -> commandsTab()
                "Events" -> eventsTab()
                "Settings" -> settingsTab()
                "Export" -> exportTab()
                else -> controlTab()
            }
        )
    }

    // ------------------------------------------------------------------ control tab

    private fun controlTab(): View {
        val l = vbox()
        l.addView(btn("← Back to the microphone test") { showMicTest() })
        statusView = tv("", 12f, mono = true)
        l.addView(statusView)
        l.addView(tv("Mic level (dBFS, -90..0)", 12f)); levelBar = bar(); l.addView(levelBar)
        l.addView(tv("Speech probability (VAD)", 12f)); vadBar = bar(); l.addView(vadBar)
        l.addView(tv("Wake-word score (0..1)", 12f)); scoreBar = bar(); l.addView(scoreBar)

        l.addView(header("Jarvis"))
        l.addView(row(btn("ENABLE Jarvis") { enableJarvis() }, btn("DISABLE Jarvis") {
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
        l.addView(tv("Recognition language:"))
        l.addView(spinner(Prefs.LANGUAGES, prefs.language) {
            if (it != prefs.language) { prefs.language = it; store.log("LANGUAGE_CHANGED", it) }
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

        l.addView(header("Manual tests (independent of the wake word)"))
        l.addView(row(
            btn("● Start manual rec") { ensureMic { ListenerService.send(this, ListenerService.ACTION_MANUAL_START) } },
            btn("■ Stop manual rec") { ListenerService.send(this, ListenerService.ACTION_MANUAL_STOP) }
        ))
        l.addView(btn("Recognize last manual recording (stored audio)") {
            val id = prefs.lastManualRecordingId
            if (id.isEmpty() || store.recording(id) == null) toast("No manual recording yet")
            else { Recognition.enqueue(id, null, "MANUAL", prefs.language); toast("Recognizing $id…") }
        })
        l.addView(btn("Live recognizer test (recognizer uses the mic itself; no stored audio)") {
            ensureMic { Recognition.enqueueLive(prefs.language); toast("Speak now (${prefs.language})…") }
        })
        l.addView(row(
            btn("Raw 10 s") { ensureMic { ListenerService.send(this, ListenerService.ACTION_RAW, 10) } },
            btn("Raw 30 s") { ensureMic { ListenerService.send(this, ListenerService.ACTION_RAW, 30) } },
            btn("Raw 60 s") { ensureMic { ListenerService.send(this, ListenerService.ACTION_RAW, 60) } }
        ))
        l.addView(btn("It MISSED my “Hey Jarvis” – save last ${prefs.missedClipMs / 1000} s") {
            ListenerService.send(this, ListenerService.ACTION_MARK_MISSED); toast("Saved as MISSED_MARK recording")
        })
        l.addView(row(
            btn("Test beep") {
                runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90).startTone(ToneGenerator.TONE_PROP_BEEP, 150) }
            },
            btn("Restart mic") { ListenerService.send(this, ListenerService.ACTION_RESTART_MIC) }
        ))

        l.addView(header("Speech recognizer"))
        l.addView(tv("Mode: ${prefs.recognizerMode} (change in Settings). ON_DEVICE = offline. DEFAULT_SERVICE = opt-in, may use network.", 12f))
        l.addView(btn("Check language support (${prefs.language})") {
            Recognition.checkSupport(prefs.language) { runOnUiThread { info("Recognition support", it) } }
        })
        l.addView(btn("Request on-device model download (${prefs.language})") {
            Recognition.triggerDownload(prefs.language) { runOnUiThread { info("Model download", it) } }
        })
        l.addView(btn("Show device / recognizer / engine info") {
            info("Device info", Diagnostics.deviceJson(this).toString(2))
        })

        l.addView(header("Permissions & battery"))
        l.addView(btn("Grant microphone + notification permission") { requestPerms() })
        l.addView(btn("Request: ignore battery optimizations") {
            store.log("BATTERY_OPT_REQUEST", "User opened ignore-battery-optimizations prompt")
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }.onFailure { toast("Not available: $it") }
        })
        l.addView(btn("Open app system settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        l.addView(tv("Build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) git ${BuildConfig.GIT_SHA}", 11f))
        slowInfo = ""
        return scroll(l)
    }

    private fun enableJarvis() {
        ensureMic {
            if (ListenerService.send(this, ListenerService.ACTION_ENABLE)) toast("Jarvis enabled – say “Hey Jarvis”")
        }
    }

    private fun ensureMic(action: () -> Unit) {
        if (Diagnostics.hasMicPermission(this)) action()
        else { toast("Microphone permission required"); requestPerms() }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (fullUi) updateStatus() else updateMicTest()
            handler.postDelayed(this, if (fullUi) 250 else 100)
        }
    }

    private fun updateStatus() {
        val sv = statusView ?: return
        val now = System.currentTimeMillis()
        if (tick++ % 8 == 0 || slowInfo.isEmpty()) {
            val perms = Diagnostics.permissions(this)
            val power = Diagnostics.power(this)
            val pct = Diagnostics.batteryPct(this)
            val hours = (now - prefs.enableTs) / 3_600_000.0
            val drain = if (prefs.enabled && prefs.batteryAtEnable >= 0 && pct >= 0 && hours > 0.05)
                "%.1f %%/h since enable".format((prefs.batteryAtEnable - pct) / hours) else "n/a"
            val onDev = if (Build.VERSION.SDK_INT >= 31) runCatching { android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this) }.getOrDefault(false).toString() else "n/a (<Android 12)"
            slowInfo = "Permissions: mic ${perms.optString("RECORD_AUDIO")}, notif ${perms.optString("POST_NOTIFICATIONS")}\n" +
                "Battery opt. ignored: ${power.optBoolean("ignoringBatteryOptimizations")} | bucket ${power.optString("appStandbyBucket")} | bg-restricted ${power.opt("backgroundRestricted")}\n" +
                "Battery: $pct% | drain: $drain | power-save ${power.optBoolean("powerSaveMode")}\n" +
                "Recognizer: ${prefs.recognizerMode} | ${prefs.language} | on-device available: $onDev | stored-audio input: ${Build.VERSION.SDK_INT >= 33}"
        }
        val manual = if (LiveStatus.manualRecordingSince > 0) "MANUAL REC %.1f s".format((now - LiveStatus.manualRecordingSince) / 1000.0) else ""
        val raw = if (LiveStatus.rawCaptureUntil > 0) "RAW CAPTURE %d s left".format(maxOf(0, (LiveStatus.rawCaptureUntil - now) / 1000)) else ""
        val hb = if (LiveStatus.lastHeartbeat > 0) "${(now - LiveStatus.lastHeartbeat) / 1000} s ago" else "-"
        sv.text = buildString {
            append("Service: ${if (LiveStatus.serviceRunning) "RUNNING since ${fmtTs(LiveStatus.serviceStartTs)}" else "STOPPED"}")
            append(" | Jarvis: ${if (prefs.enabled) "ENABLED" else "DISABLED"} | Diag: ${if (prefs.diagnosticMode) "ON" else "OFF"}\n")
            append("State: ${LiveStatus.state} | chunks ${LiveStatus.chunks} | proc %.1f ms (max %.1f)\n".format(LiveStatus.procAvgMs, LiveStatus.procMaxMs))
            append("Mic: ${if (LiveStatus.micOpen) "OPEN" else "CLOSED"} ${LiveStatus.micInfo}\n")
            append("OS-silenced: ${LiveStatus.micSilencedByOs ?: "unknown"} | zeros: ${LiveStatus.digitalSilence} | other rec clients: ${LiveStatus.otherRecordingClients}\n")
            append("Level: %.1f dBFS (peak %.1f)\n".format(LiveStatus.levelDb, LiveStatus.peakDb))
            append("VAD: %.2f | Wake: %.3f (max 5 s %.3f) thr %.2f\n".format(LiveStatus.vadProb, LiveStatus.wakeScore, LiveStatus.wakeScoreMaxRecent, prefs.threshold))
            append("Wake engine: ${LiveStatus.wakeEngineStatus}\nVAD engine: ${LiveStatus.vadEngineStatus}\n")
            append("Last activation: ${LiveStatus.lastActivation}\nLast result: ${LiveStatus.lastCommand}\n")
            append("Recognition: ${LiveStatus.recognitionStatus}\n")
            if (manual.isNotEmpty() || raw.isNotEmpty()) append("$manual $raw\n")
            append("Heartbeat: $hb | mic errors ${LiveStatus.micErrors} | open failures ${LiveStatus.micOpenFailures}\n")
            append("Last error: ${LiveStatus.lastError}\n")
            append(slowInfo)
        }
        levelBar?.progress = ((LiveStatus.levelDb + 90) / 90 * 100).toInt().coerceIn(0, 100)
        vadBar?.progress = (LiveStatus.vadProb * 100).toInt().coerceIn(0, 100)
        scoreBar?.progress = (LiveStatus.wakeScore * 100).toInt().coerceIn(0, 100)
    }

    // ------------------------------------------------------------------ playback

    private fun play(r: DiagStore.RecordingRow) {
        stopPlayback()
        val f = store.fileFor(r)
        if (!f.exists()) { toast("File missing: ${r.file}"); return }
        try {
            player = MediaPlayer().apply {
                setDataSource(f.path)
                setOnCompletionListener { stopPlayback() }
                prepare(); start()
            }
            playingId = r.id
            toast("Playing ${r.kind} ${r.durationMs / 1000.0} s")
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

    // ------------------------------------------------------------------ recordings tab

    private fun recordingsTab(): View {
        val l = vbox()
        val filters = listOf("All", "COMMAND", "WAKE", "SEGMENT", "SEGMENT/TRIGGERED", "SEGMENT/CANDIDATE", "SEGMENT/SPEECH",
            "MANUAL", "RAW", "MISSED_MARK", "LABEL:MISSED_WAKE", "LABEL:FALSE_WAKE", "LABEL:CORRECT_WAKE")
        var filter = "All"
        val list = ListView(this)
        var items: List<DiagStore.RecordingRow> = emptyList()
        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val r = items[p]
                val t = (cv as? TextView) ?: tv("", 13f)
                t.text = "${fmtTs(r.tsStart)}  ${r.kind}${r.category?.let { "/$it" } ?: ""}  %.1f s".format(r.durationMs / 1000.0) +
                    (r.maxScore?.let { "  wake %.3f".format(it) } ?: "") + (r.label?.let { "  [$it]" } ?: "") +
                    (if (r.id == playingId) "  ▶" else "") + "\n" + r.file + (r.note?.let { "\nnote: $it" } ?: "")
                return t
            }
        }
        fun refresh() {
            items = store.recordings(if (filter == "All") null else filter, 500)
            adapter.notifyDataSetChanged()
        }
        listRefresher = ::refresh
        l.addView(row(spinner(filters, filter) { filter = it; refresh() }, btn("Refresh") { refresh() }))
        l.addView(tv("Tap = play/stop · Long-press = label / recognize / delete / details", 12f))
        list.adapter = adapter
        list.setOnItemClickListener { _, _, p, _ ->
            val r = items[p]
            if (playingId == r.id) stopPlayback() else play(r)
            adapter.notifyDataSetChanged()
        }
        list.setOnItemLongClickListener { _, _, p, _ -> recordingActions(items[p]) { refresh() }; true }
        l.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        refresh()
        return l
    }

    private fun recordingActions(r: DiagStore.RecordingRow, refresh: () -> Unit) {
        val actions = listOf(
            "Play", "Stop", "Recognize now (${prefs.language})", "Analyze with wake engine (offline)",
            "Label MISSED_WAKE (I said Hey Jarvis, it did not trigger)",
            "Label FALSE_WAKE (triggered but I did not say it)",
            "Label CORRECT_WAKE", "Label NOT_WAKE (correctly ignored)", "Clear label", "Add note…", "Details (JSON)", "Delete"
        )
        AlertDialog.Builder(this).setTitle(r.id).setItems(actions.toTypedArray()) { _, which ->
            when (which) {
                0 -> play(r)
                1 -> stopPlayback()
                2 -> { Recognition.enqueue(r.id, r.interactionId, "MANUAL", prefs.language); toast("Recognizing…") }
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
                11 -> confirm("Delete ${r.file}?") { store.deleteRecording(r.id); store.log("USER_DELETED_RECORDING", r.file, recordingId = r.id); refresh() }
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

    // ------------------------------------------------------------------ commands tab

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

    // ------------------------------------------------------------------ events tab

    private fun eventsTab(): View {
        val l = vbox()
        val filters = linkedMapOf(
            "All" to null, "Errors / warnings" to listOf("%"), "WAKE_" to listOf("WAKE_", "USER_MARKED", "ACK_BEEP"),
            "SPEECH_REGION" to listOf("SPEECH_REGION"), "COMMAND / RECOGNITION" to listOf("COMMAND_", "RECOGNITION"),
            "MIC / AUDIO" to listOf("MIC_", "AUDIO_", "RECORDING_CLIENTS", "RAW_", "MANUAL_"), "LEVEL" to listOf("LEVEL"),
            "HEARTBEAT" to listOf("HEARTBEAT"),
            "SERVICE / PROCESS" to listOf("SERVICE", "FGS", "PROCESS", "SUSPECTED", "CRASH", "JARVIS_", "WAKELOCK", "TASK_", "MEMORY"),
            "DEVICE (screen/power)" to listOf("DEVICE_"),
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

    // ------------------------------------------------------------------ settings tab

    private fun settingsTab(): View {
        val l = vbox()
        l.addView(tv("Numbers apply within ~2 s (audio source/wake lock: press 'Restart mic' or re-enable). Saved settings are included in exports.", 12f))
        val fields = ArrayList<Triple<String, EditText, (String) -> Unit>>()
        fun num(label: String, value: String, decimal: Boolean, set: (String) -> Unit) {
            val et = EditText(this).apply {
                setText(value)
                inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_NUMBER
            }
            l.addView(row(tv(label, 13f), et))
            fields.add(Triple(label, et, set))
        }
        num("Wake threshold (0-1)", prefs.threshold.toString(), true) { prefs.threshold = it.toFloat().coerceIn(0.01f, 0.99f) }
        num("Candidate threshold (log/save below threshold)", prefs.candidateThreshold.toString(), true) { prefs.candidateThreshold = it.toFloat().coerceIn(0.01f, 0.99f) }
        num("Patience (consecutive 80 ms chunks ≥ threshold)", prefs.patience.toString(), false) { prefs.patience = it.toInt().coerceIn(1, 10) }
        num("VAD speech threshold (0-1)", prefs.vadThreshold.toString(), true) { prefs.vadThreshold = it.toFloat().coerceIn(0.05f, 0.99f) }
        num("Input gain (x)", prefs.gain.toString(), true) { prefs.gain = it.toFloat().coerceIn(0.1f, 20f) }
        num("Command pre-roll ms (audio before activation)", prefs.preRollMs.toString(), false) { prefs.preRollMs = it.toInt().coerceIn(0, 10000) }
        num("Ignore speech first ms after activation", prefs.cmdIgnoreMs.toString(), false) { prefs.cmdIgnoreMs = it.toInt().coerceIn(0, 3000) }
        num("End-of-command silence ms", prefs.endSilenceMs.toString(), false) { prefs.endSilenceMs = it.toInt().coerceIn(300, 10000) }
        num("Max command ms", prefs.maxCommandMs.toString(), false) { prefs.maxCommandMs = it.toInt().coerceIn(1000, 60000) }
        num("No-speech timeout ms", prefs.noSpeechTimeoutMs.toString(), false) { prefs.noSpeechTimeoutMs = it.toInt().coerceIn(1000, 30000) }
        num("Refractory after command ms", prefs.refractoryMs.toString(), false) { prefs.refractoryMs = it.toInt().coerceIn(0, 30000) }
        num("Segment pre-roll ms", prefs.segPreRollMs.toString(), false) { prefs.segPreRollMs = it.toInt().coerceIn(0, 5000) }
        num("Segment hangover ms", prefs.segHangoverMs.toString(), false) { prefs.segHangoverMs = it.toInt().coerceIn(80, 10000) }
        num("Segment max ms", prefs.segMaxMs.toString(), false) { prefs.segMaxMs = it.toInt().coerceIn(1000, 60000) }
        num("Wake clip ms (before activation)", prefs.wakeClipMs.toString(), false) { prefs.wakeClipMs = it.toInt().coerceIn(500, 19000) }
        num("Missed-button clip ms", prefs.missedClipMs.toString(), false) { prefs.missedClipMs = it.toInt().coerceIn(1000, 19000) }
        num("Storage limit MB (oldest deleted first)", prefs.storageLimitMb.toString(), false) { prefs.storageLimitMb = it.toInt().coerceIn(10, 20000) }
        num("Level log interval s", prefs.levelLogSec.toString(), false) { prefs.levelLogSec = it.toInt().coerceIn(1, 3600) }
        num("Heartbeat log interval s", prefs.heartbeatLogSec.toString(), false) { prefs.heartbeatLogSec = it.toInt().coerceIn(30, 3600) }
        num("Recognition timeout ms", prefs.recognitionTimeoutMs.toString(), false) { prefs.recognitionTimeoutMs = it.toInt().coerceIn(3000, 120000) }

        l.addView(tv("Audio source:", 13f))
        var src = prefs.audioSource
        l.addView(spinner(Prefs.AUDIO_SOURCES, src) { src = it })
        l.addView(tv("Beep stream:", 13f))
        var beepStream = prefs.beepStream
        l.addView(spinner(Prefs.BEEP_STREAMS, beepStream) { beepStream = it })
        l.addView(tv("Recognizer mode:\nON_DEVICE = Android on-device recognizer, offline (Android 12+, language pack required).\nDEFAULT_SERVICE = the phone's default recognition service (usually Google). It MAY SEND AUDIO TO THE VENDOR'S SERVERS. Explicit opt-in.", 13f))
        var mode = prefs.recognizerMode
        l.addView(spinner(Prefs.RECOGNIZER_MODES, mode) { mode = it })
        val beep = CheckBox(this).apply { text = "Acknowledgement beep"; isChecked = prefs.beepEnabled }
        val wl = CheckBox(this).apply { text = "Hold partial wake lock while listening"; isChecked = prefs.wakeLock }
        val auto = CheckBox(this).apply { text = "Automatically recognize commands"; isChecked = prefs.autoRecognize }
        l.addView(beep); l.addView(wl); l.addView(auto)

        l.addView(btn("SAVE SETTINGS") {
            val before = prefs.toJson()
            val errors = ArrayList<String>()
            for ((label, et, set) in fields) {
                try { set(et.text.toString().trim()) } catch (e: Exception) { errors.add(label) }
            }
            prefs.beepEnabled = beep.isChecked
            prefs.wakeLock = wl.isChecked
            prefs.autoRecognize = auto.isChecked
            prefs.beepStream = beepStream
            val srcChanged = src != prefs.audioSource
            prefs.audioSource = src
            fun finish() {
                val after = prefs.toJson()
                val diff = JSONObject()
                for (k in after.keys()) if (before.opt(k)?.toString() != after.opt(k)?.toString()) diff.put(k, after.opt(k))
                store.log("SETTINGS_CHANGED", diff.keys().asSequence().joinToString(), diff)
                if (srcChanged && LiveStatus.serviceRunning) ListenerService.send(this, ListenerService.ACTION_RESTART_MIC)
                toast(if (errors.isEmpty()) "Saved" else "Saved; invalid: ${errors.joinToString()}")
                showTab("Settings")
            }
            if (mode == "DEFAULT_SERVICE" && prefs.recognizerMode != "DEFAULT_SERVICE") {
                AlertDialog.Builder(this).setTitle("Allow possibly-online recognition?")
                    .setMessage("The default recognition service (usually Google) may send command audio to its servers. This app itself has no internet permission, but the recognizer service does. Enable?")
                    .setPositiveButton("Yes, opt in") { _, _ -> prefs.recognizerMode = mode; store.log("ONLINE_RECOGNITION_OPT_IN", "user accepted"); finish() }
                    .setNegativeButton("No") { _, _ -> finish() }.show()
            } else {
                prefs.recognizerMode = mode
                finish()
            }
        })
        return scroll(l)
    }

    // ------------------------------------------------------------------ export tab

    private fun exportTab(): View {
        val l = vbox()
        val storageText = tv("", 13f, mono = true)
        fun refresh() {
            val stat = StatFs(filesDir.path)
            storageText.text = buildString {
                append("Audio: %.1f MB of %d MB limit\n".format(store.audioBytes() / 1048576.0, prefs.storageLimitMb))
                append("Database: %.1f MB\n".format(store.dbFileBytes() / 1048576.0))
                append("Free on device: %.0f MB\n\n".format(stat.availableBytes / 1048576.0))
                store.countsByKind().forEach { (k, v) -> append("%-22s %5d  %7.1f MB\n".format(k, v.first, v.second / 1048576.0)) }
                append("\nEvents: ${store.count("SELECT COUNT(*) FROM events")}  Recognitions: ${store.count("SELECT COUNT(*) FROM recognitions")}")
            }
        }
        listRefresher = ::refresh
        l.addView(header("Local storage"))
        l.addView(storageText)
        l.addView(btn("Refresh") { refresh() })
        l.addView(header("Export (manual only – nothing is ever uploaded)"))
        l.addView(CheckBox(this).apply {
            text = "Include audio files"; isChecked = includeAudioInExport
            setOnCheckedChangeListener { _, c -> includeAudioInExport = c }
        })
        l.addView(btn("Save diagnostics ZIP… (choose location)") {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, Exporter.fileName())
            startActivityForResult(i, REQ_EXPORT)
        })
        l.addView(btn("Share diagnostics ZIP… (share sheet)") { shareExport() })
        l.addView(header("Delete"))
        l.addView(btn("Apply storage limit now") {
            val (n, b) = store.enforceStorageLimit(prefs.storageLimitMb.toLong() * 1048576)
            toast("Deleted $n recordings (${b / 1048576} MB)"); refresh()
        })
        l.addView(btn("DELETE ALL diagnostic data (audio, events, commands)") {
            confirm("Delete ALL recordings, recognitions and events? This cannot be undone.") {
                store.deleteAll()
                store.log("USER_DELETED_ALL_DATA", "")
                toast("All diagnostic data deleted"); refresh()
            }
        })
        refresh()
        return scroll(l)
    }

    private fun shareExport() {
        toast("Building ZIP…")
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, Exporter.fileName())
        val withAudio = includeAudioInExport
        Thread {
            val res = runCatching { f.outputStream().use { Exporter.write(this, it, withAudio) } }
            runOnUiThread {
                res.onSuccess { summary ->
                    val send = Intent(Intent.ACTION_SEND).setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, ExportProvider.uriFor(f))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(send, "Share Jarvis diagnostics").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    toast(summary)
                }.onFailure { toast("Export failed: $it"); store.log("EXPORT_FAILED", it.toString()) }
            }
        }.start()
    }

    private fun cleanupOldExports() {
        File(cacheDir, "exports").listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
    }

    @Deprecated("platform API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val withAudio = includeAudioInExport
        toast("Exporting…")
        Thread {
            val res = runCatching { contentResolver.openOutputStream(uri)!!.use { Exporter.write(this, it, withAudio) } }
            runOnUiThread {
                res.onSuccess { toast("$it → saved") }.onFailure { toast("Export failed: $it"); store.log("EXPORT_FAILED", it.toString()) }
            }
        }.start()
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
                MicStatus.message = "Microphone permission was denied. Tap 'App settings' → Permissions → Microphone → Allow, then press Start again."
                store.log("MICTEST_FAILED", "Microphone permission denied by user", sync = true)
            }
        }
    }

    // ------------------------------------------------------------------ microphone test screen

    private fun showMicTest() {
        fullUi = false
        statusView = null; levelBar = null; scoreBar = null; vadBar = null; listRefresher = null
        val l = vbox().apply { fitsSystemWindows = true }
        l.addView(tv("Microphone test", 22f, bold = true))
        l.addView(tv("Press Start and make noise. The bar always shows the live level; the orange line is the recording threshold. " +
            "Sounds above it are saved (with 1 s before), until it has been quiet for 2 s. Keeps listening when minimized or with the screen off.", 13f))
        micStateView = tv("", 17f, bold = true)
        l.addView(micStateView)
        l.addView(row(
            btn("START LISTENING") { startMicTest() }.apply { textSize = 16f },
            btn("STOP LISTENING") { stopMicTest() }.apply { textSize = 16f }
        ))
        micMeter = LevelMeter(this)
        l.addView(micMeter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { setMargins(0, dp(8), 0, dp(4)) })
        micLevelText = tv("", 15f, mono = true)
        l.addView(micLevelText)
        micInfoText = tv("", 12f, mono = true)
        l.addView(micInfoText)
        val sensLabel = tv("", 15f, bold = true)
        fun showSens(v: Int) {
            sensLabel.text = "Sensitivity %d  →  saves sounds louder than %.0f dBFS".format(v, MicTest.thresholdDb(v))
        }
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
        l.addView(tv("← low sensitivity (only loud sounds)          high sensitivity (quiet sounds) →", 11f))
        l.addView(row(
            btn("Raw 30 s (save everything)") {
                ensureMic {
                    micInterrupted = null
                    if (!MicStatus.running) { MicStatus.state = "STARTING"; MicStatus.message = "Starting…"; micStartRequestedTs = System.currentTimeMillis() }
                    ListenerService.send(this, ListenerService.ACTION_MICTEST_RAW, 30)
                    toast("Saving everything for 30 s")
                }
            },
            btn("Show Image") { startActivity(Intent(this, ImageActivity::class.java)) }
        ))
        l.addView(btn("Export recordings + logs (ZIP)") {
            includeAudioInExport = true
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, Exporter.fileName())
            startActivityForResult(i, REQ_EXPORT)
        })
        l.addView(tv("Recordings – tap to play / stop (newest first)", 15f, bold = true))
        val list = ListView(this)
        var items: List<DiagStore.RecordingRow> = emptyList()
        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val r = items[p]
                val t = (cv as? TextView) ?: tv("", 14f)
                val meta = r.meta?.let { runCatching { JSONObject(it) }.getOrNull() }
                val peak = meta?.optDouble("peakDb", -120.0) ?: -120.0
                val thr = meta?.optDouble("thresholdDb", Double.NaN) ?: Double.NaN
                val thrTxt = if (thr.isNaN()) "" else " (threshold %.0f)".format(thr)
                val what = when (r.category) {
                    "ALL_ZEROS" -> "ALL ZEROS (silenced)"
                    "EMPTY" -> "no samples"
                    "RECORDING" -> "recording…"
                    "INTERRUPTED" -> "loudest %.0f dBFS (session was killed)".format(peak)
                    "RAW" -> "RAW · loudest %.0f dBFS".format(peak)
                    "TRIGGERED" -> "loudest %.0f dBFS$thrTxt".format(peak)
                    else -> "continuous · loudest %.0f dBFS".format(peak)
                }
                t.text = "${if (r.id == playingId) "▶ " else ""}${fmtTs(r.tsStart)} · %.0f s · %s".format(r.durationMs / 1000.0, what)
                return t
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, p, _ ->
            val r = items[p]
            if (playingId == r.id) stopPlayback() else play(r)
            adapter.notifyDataSetChanged()
        }
        micListRefresh = { items = store.recordings("MICTEST", 300); adapter.notifyDataSetChanged() }
        micListRefresh?.invoke()
        l.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        l.addView(row(
            btn("App settings") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
            btn("Wake-word screen") { showFullUi() }
        ))
        setContentView(l)
    }

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
        handler.postDelayed({ micListRefresh?.invoke() }, 1500)
    }

    /** If a test was running but the service is gone (Android killed the app), say so clearly. */
    private fun checkMicTestInterrupted() {
        if (prefs.micTestActive && ListenerService.instance?.micTest == null) {
            val last = prefs.lastHeartbeatTs
            micInterrupted = "⚠ Listening stopped unexpectedly – Android probably stopped the app. Last sign of life: ${fmtTs(last)}. Press Start to listen again."
            store.log("MICTEST_INTERRUPTED", "A microphone test was active, but the listening service was not running when the app was opened",
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
            s.state == "LISTENING" -> "🟢 Listening – audio is arriving" to Color.rgb(90, 220, 110)
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
            s.recordingNow && now < s.rawUntil -> "● SAVING (raw, %d s left)".format((s.rawUntil - now) / 1000 + 1)
            s.recordingNow -> "● SAVING – %.0f s".format((now - s.recordingSinceTs) / 1000.0)
            else -> "below threshold – not saving"
        }
        micLevelText?.text = if (live) "Peak %.0f dBFS · threshold %.0f\n%s".format(s.peakDb, thr, recTxt)
            else if (s.samples > 0) "No live level. Loudest this session: %.0f dBFS".format(s.maxPeakDb) else "No audio received yet"
        micLevelText?.setTextColor(if (live && s.recordingNow) Color.rgb(255, 110, 110) else Color.LTGRAY)
        val elapsed = if (s.startTs > 0) (if (s.running) now else s.lastSampleTs.coerceAtLeast(s.startTs)) - s.startTs else 0
        micInfoText?.text = "Listening: %d:%02d · %s Hz · %,d samples · %d saved · loudest %s\nAndroid says mic silenced: %s".format(
            elapsed / 60000, (elapsed / 1000) % 60, if (s.sampleRate > 0) s.sampleRate.toString() else "-", s.samples, s.files,
            if (s.samples > 0) "%.0f dBFS".format(s.maxPeakDb) else "-",
            LiveStatus.micSilencedByOs?.let { if (it) "YES" else "no" } ?: "unknown")
        if (tick++ % 30 == 0) micListRefresh?.invoke()
    }
}

/** Big, simple level bar: −90 dBFS (empty) … 0 dBFS (full), with a marker for the loudest level so far. */
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
