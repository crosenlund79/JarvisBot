package dk.jarvisbot.app

import ai.onnxruntime.OrtEnvironment
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.os.SystemClock
import dk.jarvisbot.core.AudioMath
import dk.jarvisbot.core.PcmRingBuffer
import dk.jarvisbot.core.ShortArrayBuilder
import dk.jarvisbot.core.SileroVad
import dk.jarvisbot.core.Wav
import dk.jarvisbot.core.WakeWordEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The single shared microphone pipeline. One AudioRecord (16 kHz mono PCM16) feeds, per 80 ms chunk:
 * level metering -> rolling buffer -> Silero VAD -> openWakeWord -> command capture / diagnostic segments /
 * manual + raw captures. Nothing else in the app opens the microphone (except the explicit "live recognizer" test,
 * which pauses this pipeline first).
 */
class AudioPipeline(private val app: JarvisApp, private val listener: Listener) {

    interface Listener {
        fun onWakeDetected(interactionId: String, score: Float, detectTs: Long)
        fun onCommandSaved(interactionId: String, recordingId: String, reason: String)
        fun onPipelineState(state: String)
        fun onCaptureFinished()
    }

    companion object {
        const val SR = 16000
        const val CHUNK = WakeWordEngine.CHUNK
        const val CHUNK_MS = 80L
        private const val RING_SECONDS = 20
        private const val TRACE_LEN = 260 // ~20.8 s of 80 ms chunks

        fun sourceId(name: String): Int = when (name) {
            "MIC" -> MediaRecorder.AudioSource.MIC
            "UNPROCESSED" -> MediaRecorder.AudioSource.UNPROCESSED
            "VOICE_COMMUNICATION" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "CAMCORDER" -> MediaRecorder.AudioSource.CAMCORDER
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

        fun sourceName(id: Int): String = when (id) {
            MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"; MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"; MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
            MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"; MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.REMOTE_SUBMIX -> "REMOTE_SUBMIX"; MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_PERFORMANCE -> "VOICE_PERFORMANCE"; 1999 -> "HOTWORD"
            else -> "SOURCE_$id"
        }

        private val fileFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US) }
    }

    private val store = app.store
    private val prefs = app.prefs

    @Volatile var wakeEnabled = false
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var restartRequested = false
    @Volatile private var currentRecord: AudioRecord? = null
    private var thread: Thread? = null
    private val actions = ConcurrentLinkedQueue<() -> Unit>()

    // engines
    private var env: OrtEnvironment? = null
    // Loaded on a background thread so the microphone (and all mic diagnostics) start immediately.
    @Volatile private var wake: WakeWordEngine? = null
    @Volatile private var vad: SileroVad? = null
    private var wakeErrors = 0L
    private var vadErrors = 0L

    // buffers
    private val ring = PcmRingBuffer(SR * RING_SECONDS)
    private val scoreTrace = FloatRing(TRACE_LEN)
    private val vadTrace = FloatRing(TRACE_LEN)
    private val levelTrace = FloatRing(TRACE_LEN)

    // configuration snapshot, refreshed every ~2 s on the audio thread
    private inner class Cfg {
        var threshold = 0.5f; var candThr = 0.15f; var patience = 1; var vadThr = 0.5f; var gain = 1f
        var preRollMs = 1500; var cmdIgnoreMs = 300; var endSilenceMs = 1300; var maxCommandMs = 10000
        var noSpeechMs = 5000; var refractoryMs = 1500; var segPreRollMs = 500; var segHangoverMs = 800
        var segMaxMs = 20000; var wakeClipMs = 3000; var missedClipMs = 15000; var levelLogSec = 10
        var diagnostic = false
        fun load() {
            threshold = prefs.threshold; candThr = min(prefs.candidateThreshold, threshold); patience = max(1, prefs.patience)
            vadThr = prefs.vadThreshold; gain = prefs.gain; preRollMs = prefs.preRollMs; cmdIgnoreMs = prefs.cmdIgnoreMs
            endSilenceMs = prefs.endSilenceMs; maxCommandMs = prefs.maxCommandMs; noSpeechMs = prefs.noSpeechTimeoutMs
            refractoryMs = prefs.refractoryMs; segPreRollMs = prefs.segPreRollMs; segHangoverMs = prefs.segHangoverMs
            segMaxMs = prefs.segMaxMs; wakeClipMs = prefs.wakeClipMs; missedClipMs = prefs.missedClipMs
            levelLogSec = max(1, prefs.levelLogSec); diagnostic = prefs.diagnosticMode
        }
    }
    private val cfg = Cfg()

    // state machine
    private var inCommand = false
    private var refractoryUntil = 0L
    private var aboveStreak = 0
    private var chunkIndex = 0L

    private class Candidate {
        var active = false; var startTs = 0L; var peak = 0f; var outcome = "BELOW_THRESHOLD"; var suppressLogged = false
        var interactionId: String? = null
    }
    private val cand = Candidate()

    private class Command(val id: String, val activationTs: Long, val preRollMs: Int) {
        val buf = ShortArrayBuilder(SR * 12)
        var speechMs = 0L; var started = false; var lastSpeechTs = 0L; var peakAbs = 0; var maxScore = 0f
    }
    private var cmd: Command? = null

    private class Region(val startTs: Long, val reason: String) {
        var buf: ShortArrayBuilder? = null
        var lastActive = 0L; var speechChunks = 0; var maxScore = 0f; var maxVad = 0f; var peakAbs = 0
        var triggered = false; var interactionId: String? = null
        val scores = ArrayList<Float>(); val vads = ArrayList<Float>()
    }
    private var region: Region? = null

    private class Capture(val id: String, val kind: String, val startTs: Long, val maxSamples: Int) {
        val buf = ShortArrayBuilder(SR * 10)
    }
    @Volatile private var manual: Capture? = null
    @Volatile private var raw: Capture? = null

    // stats
    private inner class Stats {
        var startTs = 0L; var chunks = 0; var sumDb = 0.0; var minDb = 0.0; var maxDb = -200.0; var peakAbs = 0
        var zeroChunks = 0; var speechChunks = 0; var maxScore = 0f; var maxVad = 0f; var procSum = 0.0; var procMax = 0.0
        var wakeChunks = 0; var overruns = 0
        fun reset(now: Long) {
            startTs = now; chunks = 0; sumDb = 0.0; minDb = 0.0; maxDb = -200.0; peakAbs = 0; zeroChunks = 0
            speechChunks = 0; maxScore = 0f; maxVad = 0f; procSum = 0.0; procMax = 0.0; wakeChunks = 0; overruns = 0
        }
    }
    private val stats = Stats()
    private var zeroStreak = 0
    private var zeroStartTs = 0L
    private var clockBaseWall = 0L
    private var clockSamples = 0L
    private var lastGapLogTs = 0L
    private var gapCount = 0
    private var gapTotalMs = 0L

    val hasActiveCapture: Boolean get() = manual != null || raw != null

    // ------------------------------------------------------------------ control

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runLoop() }, "jarvis-audio").also { it.start() }
    }

    fun stop() {
        running = false
        runCatching { currentRecord?.stop() }
        thread?.join(3000)
        thread = null
    }

    fun pauseMic() { paused = true; runCatching { currentRecord?.stop() } }
    fun resumeMic() { paused = false }
    fun requestMicRestart(reason: String) {
        store.log("MIC_RESTART_REQUESTED", reason)
        restartRequested = true
        runCatching { currentRecord?.stop() }
    }

    fun startManual(maxSeconds: Int = 120) = actions.add {
        if (manual == null) {
            val c = Capture(DiagStore.newId("man"), "MANUAL", System.currentTimeMillis(), SR * maxSeconds)
            manual = c
            LiveStatus.manualRecordingSince = c.startTs
            store.log("MANUAL_RECORDING_STARTED", "max ${maxSeconds}s", recordingId = c.id)
        }
    }

    fun stopManual() = actions.add { finishCapture(manual ?: return@add, "USER_STOP") }

    fun startRaw(seconds: Int) = actions.add {
        if (raw == null) {
            val c = Capture(DiagStore.newId("raw"), "RAW", System.currentTimeMillis(), SR * seconds)
            raw = c
            LiveStatus.rawCaptureUntil = c.startTs + seconds * 1000L
            store.log("RAW_CAPTURE_STARTED", "${seconds}s unconditional raw capture", recordingId = c.id)
        }
    }

    fun markMissed() = actions.add {
        val now = System.currentTimeMillis()
        val samples = ring.copyLast(SR / 1000 * cfg.missedClipMs)
        val n = samples.size / CHUNK
        val scores = scoreTrace.last(n)
        val vads = vadTrace.last(n)
        val maxScore = scores.maxOrNull() ?: 0f
        val speech = vads.count { it >= cfg.vadThr }
        val meta = traceMeta(n).put("reason", "User pressed 'missed activation' button")
            .put("speechChunksInWindow", speech).put("thresholdAtTime", cfg.threshold)
        val id = DiagStore.newId("miss")
        store.log(
            "USER_MARKED_MISSED",
            if (maxScore < 0f) "wake engine was NOT running during the last ${samples.size / SR}s (not loaded / failed)"
            else "max wake score in last ${samples.size / SR}s = %.3f (threshold %.2f)".format(maxScore, cfg.threshold),
            JSONObject().put("maxWakeScore", if (maxScore < 0f) Diagnostics.UNAVAILABLE else maxScore.toDouble())
                .put("speechChunks", speech).put("threshold", cfg.threshold.toDouble()),
            recordingId = id
        )
        saveClip("MISSED_MARK", null, samples, now - samples.size * 1000L / SR, now, null, maxScore, meta, id, label = "MISSED_WAKE")
    }

    // ------------------------------------------------------------------ thread

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        LiveStatus.state = "STARTING"
        cfg.load()
        LiveStatus.wakeEngineStatus = "loading…"
        LiveStatus.vadEngineStatus = "loading…"
        store.log("ENGINES_LOADING", "Loading ONNX models in background; microphone capture starts immediately")
        Thread({ loadEngines() }, "jarvis-engine-load").also { it.priority = Thread.NORM_PRIORITY }.start()
        var backoff = 1000L
        while (running) {
            if (paused) {
                LiveStatus.state = "PAUSED"
                drainActions()
                SystemClock.sleep(50)
                continue
            }
            val rec = openMic()
            if (rec == null) {
                LiveStatus.micOpenFailures++
                LiveStatus.state = "MIC_UNAVAILABLE"
                listener.onPipelineState("MIC_UNAVAILABLE")
                val until = SystemClock.elapsedRealtime() + backoff
                while (running && SystemClock.elapsedRealtime() < until) { drainActions(); SystemClock.sleep(100) }
                backoff = min(backoff * 2, 30_000)
                continue
            }
            backoff = 1000L
            val reason = try {
                readLoop(rec)
            } catch (t: Throwable) {
                store.log("PIPELINE_ERROR", t.toString(), JSONObject().put("stack", t.stackTraceToString().take(4000)))
                "EXCEPTION"
            }
            closeMic(rec, reason)
            if (running && reason != "PAUSE" && reason != "RESTART_REQUESTED") {
                listener.onPipelineState("MIC_RECOVERING")
                SystemClock.sleep(500)
            }
        }
        // flush in-flight work
        val now = System.currentTimeMillis()
        cmd?.let { finishCommand("SERVICE_STOPPED", now) }
        region?.let { closeRegion(now, "SERVICE_STOPPED") }
        manual?.let { finishCapture(it, "SERVICE_STOPPED") }
        raw?.let { finishCapture(it, "SERVICE_STOPPED") }
        if (cand.active) endCandidate(now)
        drainActions()
        synchronized(engineLock) {
            runCatching { wake?.close() }; runCatching { vad?.close() }
            wake = null; vad = null
        }
        LiveStatus.state = "STOPPED"
        LiveStatus.micOpen = false
    }

    private fun drainActions() {
        while (true) {
            val a = actions.poll() ?: break
            try { a() } catch (t: Throwable) { store.log("PIPELINE_ACTION_ERROR", t.toString()) }
        }
    }

    private val engineLock = Any()

    private fun loadEngines() {
        val t0 = SystemClock.elapsedRealtime()
        fun asset(n: String) = app.assets.open("models/$n").use { it.readBytes() }
        try {
            env = OrtEnvironment.getEnvironment()
        } catch (t: Throwable) {
            LiveStatus.wakeEngineStatus = "FAILED: ONNX Runtime unavailable: $t"
            LiveStatus.vadEngineStatus = "FAILED: ONNX Runtime unavailable"
            store.log("WAKE_ENGINE_INIT_FAILED", "ONNX Runtime: $t", JSONObject().put("stack", t.stackTraceToString().take(4000)))
            return
        }
        try {
            val t1 = SystemClock.elapsedRealtime()
            val v = SileroVad(env!!, asset("silero_vad.onnx"))
            synchronized(engineLock) { if (running) vad = v else { v.close(); return } }
            LiveStatus.vadEngineStatus = "OK (Silero VAD v4, init ${SystemClock.elapsedRealtime() - t1} ms)"
            store.log("VAD_ENGINE_INIT", "OK", JSONObject().put("engine", "Silero VAD v4").put("threshold", cfg.vadThr.toDouble()))
        } catch (t: Throwable) {
            LiveStatus.vadEngineStatus = "FAILED (energy fallback > -40 dBFS): $t"
            store.log("VAD_ENGINE_INIT_FAILED", t.toString())
        }
        try {
            val e = WakeWordEngine(env!!, asset("melspectrogram.onnx"), asset("embedding_model.onnx"), asset("hey_jarvis_v0.1.onnx"))
            synchronized(engineLock) { if (running) wake = e else { e.close(); return } }
            val ms = SystemClock.elapsedRealtime() - t0
            LiveStatus.wakeEngineStatus = "OK (openWakeWord hey_jarvis_v0.1, ORT ${BuildConfig.ORT_VERSION}, init ${ms} ms)"
            store.log(
                "WAKE_ENGINE_INIT", "OK in ${ms} ms",
                JSONObject().put("engine", "openWakeWord 0.5.1 port").put("model", "hey_jarvis_v0.1.onnx")
                    .put("io", e.modelDescription).put("threshold", cfg.threshold.toDouble())
                    .put("candidateThreshold", r3(cfg.candThr)).put("patience", cfg.patience)
                    .put("onnxRuntime", BuildConfig.ORT_VERSION)
            )
        } catch (t: Throwable) {
            LiveStatus.wakeEngineStatus = "FAILED: $t"
            store.log("WAKE_ENGINE_INIT_FAILED", t.toString(), JSONObject().put("stack", t.stackTraceToString().take(4000)))
        }
    }

    private fun openMic(): AudioRecord? {
        if (!Diagnostics.hasMicPermission(app)) {
            LiveStatus.lastError = "RECORD_AUDIO permission not granted"
            store.log("MIC_PERMISSION_DENIED", "RECORD_AUDIO not granted; cannot capture")
            return null
        }
        val srcName = prefs.audioSource
        val src = sourceId(srcName)
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            store.log("MIC_INIT_FAILED", "getMinBufferSize returned $minBuf (16 kHz mono PCM16 unsupported?)")
            return null
        }
        val bufBytes = max(minBuf, SR * 2) // >= 1 s of audio
        val rec = try {
            AudioRecord(src, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
        } catch (e: Exception) {
            store.log("MIC_INIT_FAILED", "AudioRecord constructor: $e", JSONObject().put("source", srcName))
            LiveStatus.lastError = "Mic init failed: $e"
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            store.log("MIC_INIT_FAILED", "AudioRecord state=${rec.state} (not initialized)", JSONObject().put("source", srcName).put("minBuf", minBuf))
            LiveStatus.lastError = "Mic init failed (state ${rec.state})"
            rec.release()
            return null
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            store.log("MIC_START_FAILED", "startRecording: $e")
            rec.release()
            return null
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            store.log("MIC_START_FAILED", "recordingState=${rec.recordingState} after startRecording (mic busy?)")
            LiveStatus.lastError = "Mic start failed (busy?)"
            rec.release()
            return null
        }
        val info = JSONObject()
            .put("source", srcName).put("requestedSampleRate", SR).put("actualSampleRate", rec.sampleRate)
            .put("format", "PCM_16BIT").put("channels", rec.channelCount).put("minBufferBytes", minBuf)
            .put("bufferBytes", bufBytes).put("audioSessionId", rec.audioSessionId)
            .put("gain", cfg.gain.toDouble())
        if (Build.VERSION.SDK_INT >= 23) info.put("bufferSizeInFrames", rec.bufferSizeInFrames)
        if (srcName == "UNPROCESSED") {
            val am = app.getSystemService(AudioManager::class.java)
            info.put("unprocessedSupported", am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) ?: Diagnostics.UNAVAILABLE)
        }
        runCatching {
            val d = rec.routedDevice
            info.put("routedDevice", if (d == null) Diagnostics.UNAVAILABLE else "${d.productName} type=${d.type} id=${d.id}")
        }
        if (Build.VERSION.SDK_INT >= 28) runCatching {
            val arr = JSONArray()
            rec.activeMicrophones.forEach { m -> arr.put("${m.description} loc=${m.location} addr=${m.address}") }
            info.put("activeMicrophones", arr)
        }
        store.log("MIC_STARTED", "$srcName ${rec.sampleRate} Hz PCM16 mono, session ${rec.audioSessionId}", info)
        currentRecord = rec
        LiveStatus.micOpen = true
        LiveStatus.micSessionId = rec.audioSessionId
        LiveStatus.micInfo = "$srcName ${rec.sampleRate} Hz PCM16 mono (session ${rec.audioSessionId})"
        // discontinuity: reset streaming models + clock
        runCatching { wake?.reset() }
        runCatching { vad?.reset() }
        clockBaseWall = 0L
        zeroStreak = 0
        stats.reset(System.currentTimeMillis())
        LiveStatus.state = if (inCommand) "COMMAND" else "STANDBY"
        listener.onPipelineState(LiveStatus.state)
        return rec
    }

    private fun closeMic(rec: AudioRecord, reason: String) {
        currentRecord = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
        LiveStatus.micOpen = false
        store.log(if (reason == "PAUSE" || reason == "STOP" || reason == "RESTART_REQUESTED") "MIC_RELEASED" else "MIC_INTERRUPTED", reason)
    }

    private fun readLoop(rec: AudioRecord): String {
        val chunk = ShortArray(CHUNK)
        var zeroReads = 0
        while (running) {
            if (paused) return "PAUSE"
            if (restartRequested) { restartRequested = false; return "RESTART_REQUESTED" }
            drainActions()
            var off = 0
            while (off < CHUNK) {
                val n = rec.read(chunk, off, CHUNK - off)
                if (n < 0) {
                    if (!running) return "STOP"
                    if (paused) return "PAUSE"
                    if (restartRequested) { restartRequested = false; return "RESTART_REQUESTED" }
                    val name = when (n) {
                        AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                        AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
                        AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
                        else -> "ERROR($n)"
                    }
                    LiveStatus.micErrors++
                    LiveStatus.lastError = "Mic read error $name"
                    store.log("MIC_READ_ERROR", name, JSONObject().put("code", n).put("recordingState", rec.recordingState))
                    return "READ_ERROR_$name"
                }
                if (n == 0) {
                    if (!running) return "STOP"
                    if (paused) return "PAUSE"
                    if (restartRequested) { restartRequested = false; return "RESTART_REQUESTED" }
                    zeroReads++
                    if (zeroReads > 200) {
                        store.log("MIC_READ_STALLED", "AudioRecord.read returned 0 repeatedly", JSONObject().put("recordingState", rec.recordingState))
                        return "READ_STALLED"
                    }
                    SystemClock.sleep(10)
                    continue
                }
                zeroReads = 0
                off += n
            }
            processChunk(chunk)
        }
        return "STOP"
    }

    // ------------------------------------------------------------------ per chunk

    private fun processChunk(chunk: ShortArray) {
        val t0 = System.nanoTime()
        val now = System.currentTimeMillis()
        chunkIndex++
        if (chunkIndex % 25 == 0L) cfg.load()
        LiveStatus.lastAudioChunkTs = now

        // audio clock: detect gaps where wall time advanced much more than delivered audio
        if (clockBaseWall == 0L) { clockBaseWall = now; clockSamples = 0 }
        clockSamples += CHUNK
        val drift = now - (clockBaseWall + clockSamples * 1000 / SR)
        if (drift > 1000) {
            gapCount++
            gapTotalMs += drift
            val overrun = LiveStatus.procAvgMs > CHUNK_MS * 0.8
            if (now - lastGapLogTs >= 30_000) {
                val cause = if (overrun) "PROCESSING_OVERRUN: processing (avg %.0f ms/80 ms chunk) is slower than real time, audio was dropped".format(LiveStatus.procAvgMs)
                    else "CAPTURE_STALLED: audio stopped arriving (process frozen / CPU suspended / audio HAL stalled?)"
                store.log("AUDIO_GAP", "$gapCount gap(s), ${gapTotalMs} ms of audio missing since last report – $cause",
                    JSONObject().put("lastGapMs", drift).put("gapsSinceLastReport", gapCount).put("missingMsSinceLastReport", gapTotalMs)
                        .put("likelyCause", if (overrun) "PROCESSING_OVERRUN" else "CAPTURE_STALLED")
                        .put("procAvgMs", LiveStatus.procAvgMs).put("power", runCatching { Diagnostics.power(app) }.getOrNull()))
                lastGapLogTs = now; gapCount = 0; gapTotalMs = 0
            }
            clockBaseWall = now; clockSamples = 0
        } else if (drift < -3000) {
            clockBaseWall = now; clockSamples = 0
        }

        if (cfg.gain != 1f) {
            val g = cfg.gain
            for (i in chunk.indices) chunk[i] = (chunk[i] * g).toInt().coerceIn(-32768, 32767).toShort()
        }

        // levels
        var peak = 0
        var nonZero = false
        for (s in chunk) {
            val v = s.toInt()
            if (v != 0) nonZero = true
            val a = abs(v)
            if (a > peak) peak = a
        }
        val db = AudioMath.rmsDbfs(chunk)
        ring.write(chunk)

        // digital silence = the OS is feeding us exact zeros (silenced capture)
        if (!nonZero) {
            zeroStreak++
            if (zeroStreak == 1) zeroStartTs = now
            if (zeroStreak == 25) {
                LiveStatus.digitalSilence = true
                store.log("MIC_DIGITAL_SILENCE_START",
                    "Microphone delivers exact zeros for 2 s: capture is silenced (another app/call using mic, mic privacy toggle, or background restriction)",
                    JSONObject().put("power", runCatching { Diagnostics.power(app) }.getOrNull()))
                listener.onPipelineState("MIC_SILENCED")
            }
        } else {
            if (zeroStreak >= 25) {
                LiveStatus.digitalSilence = false
                store.log("MIC_DIGITAL_SILENCE_END", "Audio resumed after ${now - zeroStartTs} ms of digital silence",
                    JSONObject().put("durationMs", now - zeroStartTs))
                listener.onPipelineState(if (inCommand) "COMMAND" else "STANDBY")
            }
            zeroStreak = 0
        }

        // VAD
        var vadProb = -1f
        val v = vad
        if (v != null) {
            try {
                vadProb = max(v.process(chunk, 0, 640), v.process(chunk, 640, 640))
            } catch (t: Throwable) {
                vadErrors++
                if (vadErrors <= 5 || vadErrors % 1000 == 0L) store.log("VAD_ENGINE_ERROR", t.toString(), JSONObject().put("count", vadErrors))
            }
        }
        val speech = if (vadProb >= 0f) vadProb >= cfg.vadThr else db > -40.0

        // wake word
        var score = -1f
        val w = wake
        if (w != null) {
            try {
                score = w.process(chunk)
                stats.wakeChunks++
            } catch (t: Throwable) {
                wakeErrors++
                if (wakeErrors <= 5 || wakeErrors % 1000 == 0L) store.log("WAKE_ENGINE_ERROR", t.toString(), JSONObject().put("count", wakeErrors))
            }
        }
        scoreTrace.add(score)
        vadTrace.add(vadProb)
        levelTrace.add(db.toFloat())

        val wasCommand = inCommand
        if (wasCommand) stepCommand(chunk, now, speech, score, peak) else stepRegion(chunk, now, speech, score, vadProb, peak)

        // wake candidates and triggering
        val active = score >= cfg.candThr
        if (active) {
            if (!cand.active) {
                cand.active = true; cand.startTs = now; cand.peak = score; cand.outcome = "BELOW_THRESHOLD"
                cand.suppressLogged = false; cand.interactionId = null
            }
            cand.peak = max(cand.peak, score)
        } else if (cand.active) {
            endCandidate(now)
        }
        if (score >= cfg.threshold) aboveStreak++ else aboveStreak = 0
        if (aboveStreak >= cfg.patience && cand.outcome != "TRIGGERED") {
            val suppress = when {
                !wakeEnabled -> "SUPPRESSED_JARVIS_DISABLED"
                inCommand -> "SUPPRESSED_COMMAND_IN_PROGRESS"
                now < refractoryUntil -> "SUPPRESSED_REFRACTORY"
                else -> null
            }
            if (suppress == null) trigger(score, now, vadProb, db)
            else if (!cand.suppressLogged) {
                cand.suppressLogged = true
                cand.outcome = suppress
                store.log("WAKE_SUPPRESSED", suppress, JSONObject().put("score", score.toDouble()).put("threshold", cfg.threshold.toDouble()))
            }
        }

        // manual / raw captures (independent of state)
        manual?.let { c -> c.buf.add(chunk); if (c.buf.size >= c.maxSamples) finishCapture(c, "MAX_DURATION") }
        raw?.let { c -> c.buf.add(chunk); if (c.buf.size >= c.maxSamples) finishCapture(c, "COMPLETED") }

        // stats
        val procMs = (System.nanoTime() - t0) / 1e6
        stats.chunks++; stats.sumDb += db; stats.minDb = if (stats.chunks == 1) db else min(stats.minDb, db)
        stats.maxDb = max(stats.maxDb, db); stats.peakAbs = max(stats.peakAbs, peak)
        if (!nonZero) stats.zeroChunks++
        if (speech) stats.speechChunks++
        stats.maxScore = max(stats.maxScore, score); stats.maxVad = max(stats.maxVad, vadProb)
        stats.procSum += procMs; stats.procMax = max(stats.procMax, procMs)
        if (procMs > CHUNK_MS) stats.overruns++
        if (now - stats.startTs >= cfg.levelLogSec * 1000L) logStats(now)

        LiveStatus.chunks = chunkIndex
        LiveStatus.levelDb = db
        LiveStatus.peakDb = AudioMath.peakDbfs(peak)
        LiveStatus.vadProb = vadProb
        LiveStatus.wakeScore = score
        LiveStatus.wakeScoreMaxRecent = scoreTrace.last(62).maxOrNull() ?: 0f
        LiveStatus.procAvgMs = LiveStatus.procAvgMs * 0.98 + procMs * 0.02
        LiveStatus.procMaxMs = max(LiveStatus.procMaxMs * 0.999, procMs)
        LiveStatus.state = if (inCommand) "COMMAND" else if (LiveStatus.digitalSilence) "STANDBY (MIC SILENCED)" else "STANDBY"
    }

    private fun logStats(now: Long) {
        val s = stats
        val avg = if (s.chunks > 0) s.sumDb / s.chunks else -120.0
        store.log(
            "LEVEL", "avg %.1f dBFS, min %.1f, max %.1f, speech %d/%d, maxWake %.3f".format(avg, s.minDb, s.maxDb, s.speechChunks, s.chunks, s.maxScore),
            JSONObject().put("windowMs", now - s.startTs).put("chunks", s.chunks).put("avgDb", r1(avg)).put("minDb", r1(s.minDb))
                .put("maxDb", r1(s.maxDb)).put("peakDb", r1(AudioMath.peakDbfs(s.peakAbs))).put("zeroChunks", s.zeroChunks)
                .put("speechChunks", s.speechChunks).put("maxVad", r3(s.maxVad)).put("maxWakeScore", r3(s.maxScore))
                .put("wakeChunksProcessed", s.wakeChunks).put("wakeErrorsTotal", wakeErrors).put("vadErrorsTotal", vadErrors)
                .put("procAvgMs", r3(if (s.chunks > 0) s.procSum / s.chunks else 0.0)).put("procMaxMs", r3(s.procMax))
                .put("overrunChunks", s.overruns).put("state", LiveStatus.state).put("wakeEnabled", wakeEnabled)
                .put("diagnosticMode", cfg.diagnostic).put("threshold", cfg.threshold.toDouble()).put("gain", cfg.gain.toDouble())
        )
        s.reset(now)
    }

    private fun endCandidate(now: Long) {
        cand.active = false
        store.log(
            "WAKE_CANDIDATE", "${cand.outcome} peak=%.3f".format(cand.peak),
            JSONObject().put("peakScore", r3(cand.peak)).put("durationMs", now - cand.startTs).put("outcome", cand.outcome)
                .put("threshold", r3(cfg.threshold)).put("candidateThreshold", r3(cfg.candThr)),
            interactionId = cand.interactionId
        )
    }

    private fun trigger(score: Float, now: Long, vadProb: Float, db: Double) {
        val id = DiagStore.newId("int")
        cand.outcome = "TRIGGERED"
        cand.interactionId = id
        aboveStreak = 0
        val power = runCatching { Diagnostics.power(app) }.getOrNull()
        store.log(
            "WAKE_DETECTED", "score=%.3f threshold=%.2f".format(score, cfg.threshold),
            JSONObject().put("score", r3(score)).put("threshold", cfg.threshold.toDouble()).put("patience", cfg.patience)
                .put("chunkIndex", chunkIndex).put("vad", r3(vadProb)).put("levelDb", r1(db)).put("power", power),
            interactionId = id
        )
        LiveStatus.lastActivation = "${timeStr(now)} score %.2f".format(score)
        listener.onWakeDetected(id, score, now)

        // wake clip: audio immediately preceding (and including) the activation
        val wakeSamples = ring.copyLast(SR / 1000 * cfg.wakeClipMs)
        val n = wakeSamples.size / CHUNK
        saveClip("WAKE", null, wakeSamples, now - wakeSamples.size * 1000L / SR, now, id, score, traceMeta(n), DiagStore.newId("wake"))

        region?.let { it.triggered = true; it.interactionId = id; closeRegion(now, "WAKE_TRIGGERED") }

        val c = Command(id, now, cfg.preRollMs)
        c.buf.add(ring.copyLast(SR / 1000 * cfg.preRollMs))
        c.maxScore = score
        cmd = c
        inCommand = true
        LiveStatus.state = "COMMAND"
        store.log(
            "COMMAND_RECORDING_STARTED", "pre-roll ${cfg.preRollMs} ms included",
            JSONObject().put("activationTs", now).put("preRollMs", cfg.preRollMs).put("endSilenceMs", cfg.endSilenceMs)
                .put("maxCommandMs", cfg.maxCommandMs).put("noSpeechTimeoutMs", cfg.noSpeechMs),
            interactionId = id
        )
        listener.onPipelineState("COMMAND")
    }

    private fun stepCommand(chunk: ShortArray, now: Long, speech: Boolean, score: Float, peak: Int) {
        val c = cmd ?: run { inCommand = false; return }
        c.buf.add(chunk)
        c.peakAbs = max(c.peakAbs, peak)
        c.maxScore = max(c.maxScore, score)
        val elapsed = now - c.activationTs
        if (elapsed >= cfg.cmdIgnoreMs && speech) {
            c.speechMs += CHUNK_MS
            c.lastSpeechTs = now
            if (!c.started && c.speechMs >= 240) c.started = true
        }
        val reason = when {
            elapsed >= cfg.maxCommandMs -> "MAX_DURATION"
            c.started && now - c.lastSpeechTs >= cfg.endSilenceMs -> "END_OF_SPEECH"
            !c.started && elapsed >= cfg.noSpeechMs -> "NO_SPEECH_TIMEOUT"
            else -> null
        }
        if (reason != null) finishCommand(reason, now)
    }

    private fun finishCommand(reason: String, now: Long) {
        val c = cmd ?: return
        cmd = null
        inCommand = false
        refractoryUntil = now + cfg.refractoryMs
        val samples = c.buf.toArray()
        val durMs = samples.size * 1000L / SR
        val recId = DiagStore.newId("cmd")
        store.log(
            "COMMAND_RECORDING_STOPPED", "$reason after ${now - c.activationTs} ms (clip ${durMs} ms incl. pre-roll)",
            JSONObject().put("reason", reason).put("clipStartTs", now - durMs).put("activationTs", c.activationTs)
                .put("stopTs", now).put("clipDurationMs", durMs).put("postActivationMs", now - c.activationTs)
                .put("preRollMs", c.preRollMs).put("speechMsAfterActivation", c.speechMs)
                .put("commandSpeechDetected", c.started).put("peakDb", r1(AudioMath.peakDbfs(c.peakAbs))),
            interactionId = c.id, recordingId = recId
        )
        val meta = JSONObject().put("endReason", reason).put("preRollMs", c.preRollMs).put("activationTs", c.activationTs)
            .put("activationOffsetMs", c.preRollMs).put("speechMsAfterActivation", c.speechMs)
            .put("commandSpeechDetected", c.started).put("language", prefs.language)
        saveClip("COMMAND", reason, samples, now - durMs, now, c.id, c.maxScore, meta, recId) {
            listener.onCommandSaved(c.id, recId, reason)
        }
        LiveStatus.state = "STANDBY"
        listener.onPipelineState("STANDBY")
    }

    private fun stepRegion(chunk: ShortArray, now: Long, speech: Boolean, score: Float, vadProb: Float, peak: Int) {
        val active = speech || score >= cfg.candThr
        var r = region
        if (r == null) {
            if (!active) return
            r = Region(now - CHUNK_MS - cfg.segPreRollMs, if (speech) "VAD" else "WAKE_SCORE")
            if (cfg.diagnostic) r.buf = ShortArrayBuilder(SR * 4).also { it.add(ring.copyLast(SR / 1000 * cfg.segPreRollMs + CHUNK)) }
            region = r
        } else {
            r.buf?.add(chunk)
        }
        if (active) r.lastActive = now
        if (speech) r.speechChunks++
        r.maxScore = max(r.maxScore, score)
        r.maxVad = max(r.maxVad, vadProb)
        r.peakAbs = max(r.peakAbs, peak)
        r.scores.add(score); r.vads.add(vadProb)
        if (now - r.lastActive >= cfg.segHangoverMs) closeRegion(now, "SILENCE")
        else if (now - r.startTs >= cfg.segMaxMs) closeRegion(now, "MAX_LENGTH")
    }

    private fun closeRegion(now: Long, why: String) {
        val r = region ?: return
        region = null
        val category = when {
            r.triggered -> "TRIGGERED"
            r.maxScore >= cfg.candThr -> "CANDIDATE"
            r.speechChunks > 0 -> "SPEECH"
            else -> "OTHER"
        }
        val speechMs = r.speechChunks * CHUNK_MS
        val saved = r.buf != null && (category == "TRIGGERED" || category == "CANDIDATE" || speechMs >= 160)
        val recId = if (saved) DiagStore.newId("seg") else null
        store.log(
            "SPEECH_REGION", "$category ${now - r.startTs} ms, speech ${speechMs} ms, maxWake %.3f".format(r.maxScore),
            JSONObject().put("category", category).put("startReason", r.reason).put("endReason", why)
                .put("startTs", r.startTs).put("durationMs", now - r.startTs).put("speechMs", speechMs)
                .put("maxWakeScore", r3(r.maxScore)).put("maxVad", r3(r.maxVad)).put("peakDb", r1(AudioMath.peakDbfs(r.peakAbs)))
                .put("audioSaved", saved),
            interactionId = r.interactionId, recordingId = recId
        )
        if (saved && recId != null) {
            val samples = r.buf!!.toArray()
            val meta = JSONObject().put("startReason", r.reason).put("endReason", why).put("speechMs", speechMs)
                .put("maxVad", r3(r.maxVad)).put("thresholdAtTime", cfg.threshold.toDouble())
                .put("note", "traces are per 80 ms chunk starting after the ${cfg.segPreRollMs} ms pre-roll")
                .put("wakeScores", JSONArray(r.scores.map { r3(it) })).put("vad", JSONArray(r.vads.map { r3(it) }))
            saveClip("SEGMENT", category, samples, now - samples.size * 1000L / SR, now, r.interactionId, r.maxScore, meta, recId)
        }
    }

    private fun finishCapture(c: Capture, reason: String) {
        val now = System.currentTimeMillis()
        if (c === manual) { manual = null; LiveStatus.manualRecordingSince = 0L }
        if (c === raw) { raw = null; LiveStatus.rawCaptureUntil = 0L }
        val samples = c.buf.toArray()
        val type = if (c.kind == "MANUAL") "MANUAL_RECORDING_STOPPED" else "RAW_CAPTURE_STOPPED"
        store.log(type, "$reason, ${samples.size * 1000L / SR} ms", JSONObject().put("reason", reason), recordingId = c.id)
        val meta = JSONObject().put("endReason", reason).put("diagnosticMode", cfg.diagnostic)
        saveClip(c.kind, null, samples, c.startTs, now, null, null, meta, c.id) {
            if (c.kind == "MANUAL") prefs.lastManualRecordingId = c.id
            listener.onCaptureFinished()
        }
    }

    // ------------------------------------------------------------------ saving

    private fun traceMeta(n: Int): JSONObject = JSONObject()
        .put("note", "traces are per 80 ms chunk, ending at the clip end")
        .put("wakeScores", JSONArray(scoreTrace.last(n).map { r3(it) }))
        .put("vad", JSONArray(vadTrace.last(n).map { r3(it) }))
        .put("levelDb", JSONArray(levelTrace.last(n).map { r1(it.toDouble()) }))

    private fun saveClip(
        kind: String, category: String?, samples: ShortArray, startTs: Long, endTs: Long, interactionId: String?,
        maxScore: Float?, meta: JSONObject?, recId: String, label: String? = null, onSaved: (() -> Unit)? = null,
    ) {
        val name = fileFmt.get()!!.format(Date(startTs)) + "_" + kind.lowercase() +
            (category?.let { "_" + it.lowercase() } ?: "") + "_" + recId.takeLast(6) + ".wav"
        store.runOnWriter {
            val f = File(store.audioDir, name)
            try {
                Wav.write(f, samples, SR)
                val row = DiagStore.RecordingRow(
                    recId, startTs, endTs, kind, category, name, f.length(), samples.size * 1000L / SR, SR,
                    interactionId, maxScore?.toDouble(), label, null, meta?.toString()
                )
                store.insertRecording(row)
                DiagnosticFolder.publish(row)
                Transcription.onRecordingSaved(row)
                store.log("RECORDING_SAVED", "$kind${category?.let { "/$it" } ?: ""} ${samples.size * 1000L / SR} ms",
                    JSONObject().put("file", name).put("bytes", f.length()), interactionId, recId)
                StorageManager.requestCheck()
                onSaved?.invoke()
            } catch (e: Exception) {
                store.log("RECORDING_SAVE_FAILED", e.toString(), JSONObject().put("file", name), interactionId, recId)
            }
        }
    }

    private fun r1(v: Double) = Math.round(v * 10.0) / 10.0
    private fun r3(v: Float) = Math.round(v * 1000.0) / 1000.0
    private fun r3(v: Double) = Math.round(v * 1000.0) / 1000.0
    private fun timeStr(ts: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ts))
}

/** Fixed-size ring of floats. */
class FloatRing(private val cap: Int) {
    private val a = FloatArray(cap)
    private var pos = 0
    private var count = 0
    fun add(v: Float) { a[pos] = v; pos = (pos + 1) % cap; if (count < cap) count++ }
    fun last(n: Int): List<Float> {
        val k = min(n, count)
        val out = ArrayList<Float>(k)
        var p = (pos - k + cap) % cap
        repeat(k) { out.add(a[p]); p = (p + 1) % cap }
        return out
    }
}
