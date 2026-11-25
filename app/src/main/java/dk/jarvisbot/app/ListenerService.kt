package dk.jarvisbot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor

class ListenerService : Service(), AudioPipeline.Listener {

    companion object {
        const val ACTION_ENABLE = "dk.jarvisbot.action.ENABLE"
        const val ACTION_DISABLE = "dk.jarvisbot.action.DISABLE"
        const val ACTION_MANUAL_START = "dk.jarvisbot.action.MANUAL_START"
        const val ACTION_MANUAL_STOP = "dk.jarvisbot.action.MANUAL_STOP"
        const val ACTION_RAW = "dk.jarvisbot.action.RAW"
        const val ACTION_MARK_MISSED = "dk.jarvisbot.action.MARK_MISSED"
        const val ACTION_STOP = "dk.jarvisbot.action.STOP"
        const val ACTION_RESTART_MIC = "dk.jarvisbot.action.RESTART_MIC"
        const val ACTION_MICTEST_START = "dk.jarvisbot.action.MICTEST_START"
        const val ACTION_MICTEST_STOP = "dk.jarvisbot.action.MICTEST_STOP"
        const val ACTION_MICTEST_RAW = "dk.jarvisbot.action.MICTEST_RAW"
        const val EXTRA_SECONDS = "seconds"
        private const val CHANNEL = "jarvis_listening"
        private const val NOTIF_ID = 1001
        private const val HEARTBEAT_MS = 30_000L

        @Volatile var instance: ListenerService? = null
            private set

        /** Sends a command to the service, starting it as a foreground service if needed. Returns false on failure. */
        fun send(ctx: Context, action: String, seconds: Int = 0): Boolean {
            val i = Intent(ctx, ListenerService::class.java).setAction(action)
            if (seconds > 0) i.putExtra(EXTRA_SECONDS, seconds)
            return try {
                ctx.startForegroundService(i)
                true
            } catch (e: Exception) {
                JarvisApp.instance.store.log("FGS_START_REQUEST_FAILED", "$action: $e")
                LiveStatus.lastError = "Could not start service: $e"
                false
            }
        }
    }

    private lateinit var app: JarvisApp
    var pipeline: AudioPipeline? = null
        private set
    /** Microphone experiment (bypasses wake word / recognition). Never runs together with [pipeline]. */
    var micTest: MicTest? = null
        private set
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var beepThread: HandlerThread
    private lateinit var beepHandler: Handler
    private var tone: ToneGenerator? = null
    private var toneStream = -1
    private var foregroundStarted = false
    private var cleanStop = false
    private var lastHeartbeatLog = 0L
    private var chunksAtLastLog = 0L
    private var receiverRegistered = false
    private var lastSilenced: Boolean? = null
    private var lastOtherClients = -1
    private var lastAudioMode = -1
    private var notifText = ""
    private var lastNotifUpdate = 0L
    private var modeListener: Any? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = application as JarvisApp
        instance = this
        beepThread = HandlerThread("jarvis-beep").also { it.start() }
        beepHandler = Handler(beepThread.looper)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis listening", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Jarvis is using the microphone"
            setShowBadge(false)
        })
        app.store.log("SERVICE_CREATED", "ListenerService.onCreate", JSONObject().put("permissions", Diagnostics.permissions(this)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = app.store
        val prefs = app.prefs
        val action = intent?.action
        if (intent == null) {
            store.log("SERVICE_RESTARTED_BY_SYSTEM", "START_STICKY restart (null intent) after the process was killed",
                JSONObject().put("jarvisEnabledPref", prefs.enabled).put("lastHeartbeatTs", prefs.lastHeartbeatTs))
        }
        store.log("SERVICE_START_COMMAND", action ?: "null", JSONObject().put("flags", flags).put("startId", startId))
        if (!ensureForeground()) {
            if (action == ACTION_MICTEST_START || action == ACTION_MICTEST_RAW || (action == null && prefs.micTestActive)) {
                MicStatus.state = "FAILED"
                MicStatus.message = "Android refused to start the listening service: ${LiveStatus.lastError}"
                prefs.micTestActive = false
                store.log("MICTEST_FAILED", "Foreground service could not start, so the microphone test could not run", sync = true)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (micTest != null && action != null && action != ACTION_MICTEST_START && action != ACTION_MICTEST_STOP && action != ACTION_MICTEST_RAW &&
            action != ACTION_STOP && action != ACTION_DISABLE && action != ACTION_MARK_MISSED
        ) {
            // A wake-word / capture command arrived while the microphone test runs: end the test first.
            stopMicTest("WAKE_WORD_MODE_REQUESTED", stopService = false)
        }
        when (action) {
            ACTION_MICTEST_START -> startMicTest(resumed = false)
            ACTION_MICTEST_STOP -> stopMicTest("USER_STOP", stopService = true)
            ACTION_MICTEST_RAW -> {
                startMicTest(resumed = false)
                micTest?.forceRaw(intent.getIntExtra(EXTRA_SECONDS, 30).coerceIn(1, 300))
            }
            ACTION_ENABLE -> {
                prefs.enabled = true
                prefs.enableTs = System.currentTimeMillis()
                prefs.batteryAtEnable = Diagnostics.batteryPct(this)
                store.log("JARVIS_ENABLED", "Wake-word listening enabled",
                    JSONObject().put("battery", Diagnostics.battery(this)).put("power", Diagnostics.power(this)))
                ensurePipeline()
                pipeline?.wakeEnabled = true
            }
            ACTION_DISABLE -> {
                prefs.enabled = false
                store.log("JARVIS_DISABLED", "Wake-word listening disabled by user")
                pipeline?.wakeEnabled = false
                maybeStop()
            }
            ACTION_MANUAL_START -> { ensurePipeline(); pipeline?.startManual() }
            ACTION_MANUAL_STOP -> pipeline?.stopManual() ?: maybeStop()
            ACTION_RAW -> { ensurePipeline(); pipeline?.startRaw(intent.getIntExtra(EXTRA_SECONDS, 10).coerceIn(1, 300)) }
            ACTION_MARK_MISSED -> {
                if (pipeline == null) {
                    store.log("USER_MARKED_MISSED", "Pressed while the listening service was not capturing audio; nothing to save")
                    maybeStop()
                } else pipeline?.markMissed()
            }
            ACTION_RESTART_MIC -> pipeline?.requestMicRestart("user request")
            ACTION_STOP -> {
                prefs.enabled = false
                store.log("JARVIS_DISABLED", "Stopped from notification")
                stopEverything()
            }
            else -> {
                if (action == null && prefs.micTestActive) {
                    startMicTest(resumed = true)
                } else if (prefs.enabled) {
                    ensurePipeline(); pipeline?.wakeEnabled = true
                } else if (pipeline == null && micTest == null) {
                    stopEverything()
                }
            }
        }
        LiveStatus.wakeEnabled = pipeline?.wakeEnabled == true
        updateNotification(force = true)
        return START_STICKY
    }

    private fun ensureForeground(): Boolean {
        if (foregroundStarted) return true
        return try {
            val n = buildNotification("Starting…")
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIF_ID, n)
            foregroundStarted = true
            LiveStatus.foreground = true
            app.store.log("FGS_STARTED", "Foreground service (type microphone) started")
            true
        } catch (e: Exception) {
            LiveStatus.lastError = "Foreground service start refused: $e"
            app.store.log(
                "FGS_START_FAILED", "${e.javaClass.simpleName}: ${e.message}",
                JSONObject().put("hint", "Android 12+/14+ only allow starting a microphone foreground service while the app is visible. Open the app and enable Jarvis again.")
                    .put("power", Diagnostics.power(this)),
                sync = true
            )
            false
        }
    }

    private fun ensurePipeline() {
        if (pipeline != null) return
        val prefs = app.prefs
        pipeline = AudioPipeline(app, this).also {
            it.wakeEnabled = prefs.enabled
            it.start()
        }
        if (prefs.wakeLock) acquireWakeLock()
        else app.store.log("WAKELOCK_DISABLED", "No wake lock (setting); the CPU may sleep with the screen off")
        markRunning()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:listening").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
        app.store.log("WAKELOCK_ACQUIRED", "PARTIAL_WAKE_LOCK held while listening")
    }

    private fun markRunning() {
        val prefs = app.prefs
        registerMonitors()
        prefs.svcRunningMarker = true
        prefs.svcStartTs = System.currentTimeMillis()
        LiveStatus.serviceRunning = true
        LiveStatus.serviceStartTs = prefs.svcStartTs
        main.removeCallbacks(heartbeat)
        main.post(heartbeat)
    }

    private fun startMicTest(resumed: Boolean) {
        if (micTest != null) return
        val store = app.store
        if (pipeline != null) {
            store.log("MICTEST_STOPPING_WAKE_WORD", "Wake-word pipeline stopped so the microphone test has the microphone to itself")
            app.prefs.enabled = false
            pipeline?.stop()
            pipeline = null
        }
        app.prefs.micTestActive = true
        cleanStop = false
        acquireWakeLock() // always: listening must continue with the screen off
        micTest = MicTest(app) { main.post { updateNotification(force = true) } }.also { it.start(resumed) }
        markRunning()
    }

    private fun stopMicTest(reason: String, stopService: Boolean) {
        val t = micTest ?: run { if (stopService) maybeStop(); return }
        app.store.log("MICTEST_STOP_REQUESTED", reason, interactionId = t.sessionId)
        t.stop()
        micTest = null
        app.prefs.micTestActive = false
        if (stopService) maybeStop()
    }

    private fun maybeStop() {
        val p = pipeline
        if (!app.prefs.enabled && micTest == null && (p == null || !p.hasActiveCapture)) stopEverything()
    }

    private fun stopEverything() {
        if (cleanStop && pipeline == null && micTest == null) return
        cleanStop = true
        main.removeCallbacks(heartbeat)
        micTest?.let { app.store.log("MICTEST_STOP_REQUESTED", "service stopping", interactionId = it.sessionId); it.stop() }
        micTest = null
        app.prefs.micTestActive = false
        pipeline?.stop()
        pipeline = null
        releaseWakeLock()
        app.prefs.svcRunningMarker = false
        LiveStatus.serviceRunning = false
        LiveStatus.wakeEnabled = false
        LiveStatus.state = "STOPPED"
        app.store.log("SERVICE_STOPPING", "Clean stop requested")
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        foregroundStarted = false
        LiveStatus.foreground = false
        stopSelf()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) { it.release(); app.store.log("WAKELOCK_RELEASED", "") } }
        wakeLock = null
    }

    override fun onDestroy() {
        main.removeCallbacks(heartbeat)
        if (pipeline != null || micTest != null) {
            app.store.log("SERVICE_DESTROYED_UNEXPECTEDLY", "onDestroy while audio capture was running (system stop?)", sync = true)
            pipeline?.stop()
            pipeline = null
            micTest?.stop()
            micTest = null
        }
        releaseWakeLock()
        unregisterMonitors()
        runCatching { tone?.release() }
        beepThread.quitSafely()
        app.prefs.svcRunningMarker = false
        LiveStatus.serviceRunning = false
        LiveStatus.foreground = false
        LiveStatus.micOpen = false
        LiveStatus.state = "STOPPED"
        app.store.log("SERVICE_DESTROYED", if (cleanStop) "clean" else "not requested by app", sync = true)
        instance = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        app.store.log("TASK_REMOVED", "App swiped away from recents; service keeps running if allowed")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        app.store.log("SERVICE_TRIM_MEMORY", "level=$level")
    }

    // ------------------------------------------------------------- heartbeat

    private val heartbeat = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val prefs = app.prefs
            val p = pipeline
            val audioAge = if (LiveStatus.lastAudioChunkTs > 0) now - LiveStatus.lastAudioChunkTs else -1
            val info = if (micTest != null)
                "mictest state=${MicStatus.state} samples=${MicStatus.samples} lastSampleAgeMs=${if (MicStatus.lastSampleTs > 0) now - MicStatus.lastSampleTs else -1}"
            else "state=${LiveStatus.state} mic=${LiveStatus.micOpen} chunks=${LiveStatus.chunks} audioAgeMs=$audioAge"
            prefs.lastHeartbeatTs = now
            prefs.lastHeartbeatInfo = info
            LiveStatus.lastHeartbeat = now
            if (now - lastHeartbeatLog >= prefs.heartbeatLogSec * 1000L - 1000) {
                val pct = Diagnostics.batteryPct(this@ListenerService)
                val hours = (now - prefs.enableTs) / 3_600_000.0
                val drain = if (prefs.batteryAtEnable >= 0 && pct >= 0 && hours > 0.05) (prefs.batteryAtEnable - pct) / hours else null
                app.store.log(
                    "HEARTBEAT", info,
                    JSONObject().put("chunksSinceLast", LiveStatus.chunks - chunksAtLastLog).put("audioAgeMs", audioAge)
                        .put("state", LiveStatus.state).put("micOpen", LiveStatus.micOpen).put("wakeEnabled", p?.wakeEnabled)
                        .put("digitalSilence", LiveStatus.digitalSilence).put("micSilencedByOs", LiveStatus.micSilencedByOs ?: JSONObject.NULL)
                        .put("wakeLockHeld", wakeLock?.isHeld == true).put("battery", Diagnostics.battery(this@ListenerService))
                        .put("batteryDrainPctPerHourSinceEnable", drain ?: Diagnostics.UNAVAILABLE)
                        .put("power", Diagnostics.power(this@ListenerService))
                        .put("procAvgMs", LiveStatus.procAvgMs).put("micErrors", LiveStatus.micErrors)
                        .put("micTest", if (micTest != null) JSONObject().put("state", MicStatus.state).put("samples", MicStatus.samples)
                            .put("sampleRate", MicStatus.sampleRate).put("files", MicStatus.files) else JSONObject.NULL)
                )
                lastHeartbeatLog = now
                chunksAtLastLog = LiveStatus.chunks
            }
            // watchdog: audio stopped arriving although the mic should be open
            if (p != null && LiveStatus.micOpen && audioAge > 5000) {
                app.store.log("AUDIO_STALL", "No audio chunk for $audioAge ms; forcing microphone restart")
                p.requestMicRestart("watchdog: audio stalled $audioAge ms")
            }
            checkAudioMode()
            updateNotification()
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    // --------------------------------------------------- interruption monitors

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val mineId = LiveStatus.micSessionId
            val mine = configs.firstOrNull { it.clientAudioSessionId == mineId }
            val others = configs.filter { it.clientAudioSessionId != mineId }
            val silenced = if (Build.VERSION.SDK_INT >= 29 && mine != null) mine.isClientSilenced else null
            if (silenced != null && silenced != lastSilenced && !(lastSilenced == null && !silenced)) {
                app.store.log(
                    if (silenced) "MIC_CLIENT_SILENCED" else "MIC_CLIENT_UNSILENCED",
                    if (silenced) "Android silenced our capture (another app or a call took the microphone)" else "Our capture is no longer silenced",
                    JSONObject().put("otherClients", others.size)
                )
                updateNotification(force = true)
            }
            if (silenced != null) LiveStatus.micSilencedByOs = silenced
            lastSilenced = silenced
            if (others.size != lastOtherClients) {
                if (others.size > lastOtherClients && lastOtherClients >= 0) LiveStatus.otherClientStarts++
                val arr = JSONArray()
                others.forEach { c ->
                    val o = JSONObject().put("source", AudioPipeline.sourceName(c.clientAudioSource))
                    if (Build.VERSION.SDK_INT >= 29) o.put("silenced", c.isClientSilenced)
                    arr.put(o)
                }
                app.store.log("RECORDING_CLIENTS_CHANGED", "other active recording clients: ${others.size}",
                    JSONObject().put("others", arr).put("ourCaptureActive", mine != null))
                lastOtherClients = others.size
                LiveStatus.otherRecordingClients = others.size
            }
        }
    }

    private val deviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val a = intent.action ?: return
            val name = a.substringAfterLast('.')
            val data = JSONObject()
            if (a == AudioManager.ACTION_HEADSET_PLUG) data.put("state", intent.getIntExtra("state", -1)).put("name", intent.getStringExtra("name"))
            data.put("power", Diagnostics.power(context))
            app.store.log("DEVICE_$name", "", data)
        }
    }

    private fun registerMonitors() {
        if (receiverRegistered) return
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED); addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(deviceReceiver, f, RECEIVER_NOT_EXPORTED) else registerReceiver(deviceReceiver, f)
        val am = getSystemService(AudioManager::class.java)
        am.registerAudioRecordingCallback(recordingCallback, main)
        recordingCallback.onRecordingConfigChanged(am.activeRecordingConfigurations)
        if (Build.VERSION.SDK_INT >= 31) {
            val l = AudioManager.OnModeChangedListener { checkAudioMode() }
            am.addOnModeChangedListener(Executor { main.post(it) }, l)
            modeListener = l
        }
        receiverRegistered = true
    }

    private fun unregisterMonitors() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(deviceReceiver) }
        val am = getSystemService(AudioManager::class.java)
        runCatching { am.unregisterAudioRecordingCallback(recordingCallback) }
        if (Build.VERSION.SDK_INT >= 31) (modeListener as? AudioManager.OnModeChangedListener)?.let { runCatching { am.removeOnModeChangedListener(it) } }
        receiverRegistered = false
    }

    private fun checkAudioMode() {
        val m = getSystemService(AudioManager::class.java).mode
        if (m != lastAudioMode) {
            if (lastAudioMode != -1) app.store.log("AUDIO_MODE_CHANGED", Diagnostics.audioModeName(m),
                JSONObject().put("from", Diagnostics.audioModeName(lastAudioMode)).put("to", Diagnostics.audioModeName(m)))
            lastAudioMode = m
        }
    }

    // ------------------------------------------------------- pipeline events

    override fun onWakeDetected(interactionId: String, score: Float, detectTs: Long) {
        val prefs = app.prefs
        beepHandler.post {
            if (!prefs.beepEnabled) {
                app.store.log("ACK_BEEP", "disabled in settings", interactionId = interactionId)
                return@post
            }
            try {
                val stream = when (prefs.beepStream) {
                    "NOTIFICATION" -> AudioManager.STREAM_NOTIFICATION; "ALARM" -> AudioManager.STREAM_ALARM
                    "SYSTEM" -> AudioManager.STREAM_SYSTEM; else -> AudioManager.STREAM_MUSIC
                }
                if (tone == null || toneStream != stream) {
                    runCatching { tone?.release() }
                    tone = ToneGenerator(stream, 90)
                    toneStream = stream
                }
                val ok = tone!!.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                val am = getSystemService(AudioManager::class.java)
                val vol = am.getStreamVolume(stream)
                val max = am.getStreamMaxVolume(stream)
                app.store.log(
                    "ACK_BEEP", "played=$ok latency=${System.currentTimeMillis() - detectTs} ms stream=${prefs.beepStream} volume=$vol/$max" +
                        if (vol == 0) " (WARNING: volume 0, inaudible)" else "",
                    JSONObject().put("played", ok).put("latencyMs", System.currentTimeMillis() - detectTs)
                        .put("stream", prefs.beepStream).put("volume", vol).put("maxVolume", max),
                    interactionId = interactionId
                )
            } catch (e: Exception) {
                app.store.log("ACK_BEEP_FAILED", e.toString(), interactionId = interactionId)
            }
        }
        main.post { updateNotification(force = true, text = "Heard “Hey Jarvis” – recording command…") }
    }

    override fun onCommandSaved(interactionId: String, recordingId: String, reason: String) {
        main.post {
            if (app.prefs.autoRecognize) Recognition.enqueue(recordingId, interactionId, "AUTO", app.prefs.language)
            else app.store.log("RECOGNITION_SKIPPED", "auto-recognition disabled in settings", interactionId = interactionId, recordingId = recordingId)
        }
    }

    override fun onPipelineState(state: String) {
        main.post { updateNotification(force = true) }
    }

    override fun onCaptureFinished() {
        main.post { maybeStop() }
    }

    // ----------------------------------------------------------- notification

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun svc(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, ListenerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle(if (micTest != null) "Jarvis microphone test" else "Jarvis prototype")
            .setContentText(text)
            .setSubText(if (micTest == null && app.prefs.diagnosticMode) "Diagnostic mode ON" else null)
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (micTest == null) b.addAction(Notification.Action.Builder(null, "Missed it!", svc(ACTION_MARK_MISSED, 1)).build())
        return b.addAction(Notification.Action.Builder(null, "Stop", svc(ACTION_STOP, 2)).build()).build()
    }

    private fun currentText(): String = if (micTest != null) when (MicStatus.state) {
        "LISTENING" -> "Listening – recording everything the microphone delivers"
        "NO_AUDIO" -> "⚠ Microphone open, but Android delivers no audio"
        "ONLY_ZEROS" -> "⚠ Android delivers only silence (all zeros) – microphone blocked?"
        "FAILED" -> "⚠ Microphone failed – open the app for details"
        else -> "Starting microphone…"
    } else when {
        LiveStatus.micSilencedByOs == true || LiveStatus.digitalSilence -> "⚠ Microphone silenced by Android / another app"
        !LiveStatus.micOpen && pipeline != null -> "⚠ Microphone not available – retrying (${LiveStatus.lastError})"
        LiveStatus.state == "COMMAND" -> "Recording command…"
        LiveStatus.manualRecordingSince > 0 -> "Manual recording…"
        LiveStatus.rawCaptureUntil > 0 -> "Raw diagnostic capture…"
        pipeline?.wakeEnabled == true -> "Listening for “Hey Jarvis” (threshold %.2f)".format(app.prefs.threshold)
        else -> "Capturing audio (wake word disabled)"
    }

    private fun updateNotification(force: Boolean = false, text: String? = null) {
        if (!foregroundStarted) return
        val t = text ?: currentText()
        val now = System.currentTimeMillis()
        if (!force && t == notifText) return
        if (!force && now - lastNotifUpdate < 1000) return
        notifText = t
        lastNotifUpdate = now
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(t)) }
    }

    fun pauseForLiveRecognition() {
        pipeline?.pauseMic()
        app.store.log("MIC_PAUSED", "Paused shared capture so the system recognizer can use the microphone (live test)")
    }

    fun resumeAfterLiveRecognition() {
        pipeline?.resumeMic()
        app.store.log("MIC_RESUMED", "Shared capture resumed after live recognition test")
    }
}
