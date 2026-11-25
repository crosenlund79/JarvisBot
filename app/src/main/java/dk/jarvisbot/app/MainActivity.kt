package dk.jarvisbot.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import android.widget.HorizontalScrollView
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (t in listOf("Control", "Recordings", "Commands", "Events", "Settings", "Export")) {
            tabs.addView(Button(this).apply {
                text = t; isAllCaps = false; textSize = 13f
                setOnClickListener { showTab(t) }
            })
        }
        root.addView(HorizontalScrollView(this).apply { addView(tabs) })
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        Recognition.onUpdate = { runOnUiThread { if (currentTab == "Commands") listRefresher?.invoke() } }
        cleanupOldExports()
        showTab("Control")
        if (!Diagnostics.hasMicPermission(this)) requestPerms()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        store.log("UI_RESUMED", "", JSONObject().put("serviceRunning", LiveStatus.serviceRunning))
        if (prefs.enabled && !LiveStatus.serviceRunning && Diagnostics.hasMicPermission(this)) {
            store.log("SERVICE_AUTOSTART_FROM_UI", "Jarvis is enabled but the service was not running; restarting it now")
            ListenerService.send(this, ListenerService.ACTION_ENABLE)
        }
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
            updateStatus()
            handler.postDelayed(this, 250)
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
            "Play", "Stop", "Recognize now (${prefs.language})",
            "Label MISSED_WAKE (I said Hey Jarvis, it did not trigger)",
            "Label FALSE_WAKE (triggered but I did not say it)",
            "Label CORRECT_WAKE", "Label NOT_WAKE (correctly ignored)", "Clear label", "Add note…", "Details (JSON)", "Delete"
        )
        AlertDialog.Builder(this).setTitle(r.id).setItems(actions.toTypedArray()) { _, which ->
            when (which) {
                0 -> play(r)
                1 -> stopPlayback()
                2 -> { Recognition.enqueue(r.id, r.interactionId, "MANUAL", prefs.language); toast("Recognizing…") }
                3, 4, 5, 6 -> {
                    val label = listOf("MISSED_WAKE", "FALSE_WAKE", "CORRECT_WAKE", "NOT_WAKE")[which - 3]
                    store.setRecordingLabel(r.id, label, null)
                    if ((label == "FALSE_WAKE" || label == "CORRECT_WAKE") && r.interactionId != null) store.labelInteraction(r.interactionId, label)
                    store.log("USER_LABEL", label, JSONObject().put("kind", r.kind).put("category", r.category).put("maxWakeScore", r.maxScore),
                        r.interactionId, r.id)
                    refresh()
                }
                7 -> { store.setRecordingLabel(r.id, null, null); store.log("USER_LABEL", "cleared", recordingId = r.id); refresh() }
                8 -> editText("Note", r.note ?: "") { store.setRecordingLabel(r.id, r.label, it); refresh() }
                9 -> info(r.id, r.toJson().toString(2))
                10 -> confirm("Delete ${r.file}?") { store.deleteRecording(r.id); store.log("USER_DELETED_RECORDING", r.file, recordingId = r.id); refresh() }
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
    }
}
