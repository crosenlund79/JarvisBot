package dk.jarvisbot.app

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.os.SystemClock
import dk.jarvisbot.core.PcmRingBuffer
import dk.jarvisbot.core.Wav
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Live values of the microphone test, polled by the UI. */
object MicStatus {
    @Volatile var running = false
    /** IDLE, STARTING, LISTENING, NO_AUDIO, ONLY_ZEROS, FAILED, STOPPED */
    @Volatile var state = "IDLE"
    @Volatile var message = "Not listening"
    @Volatile var sessionId = ""
    @Volatile var startTs = 0L
    @Volatile var sampleRate = 0
    @Volatile var samples = 0L
    @Volatile var lastSampleTs = 0L
    @Volatile var levelDb = -120.0
    @Volatile var peakDb = -120.0
    @Volatile var maxPeakDb = -120.0
    /** Recordings saved in this session. */
    @Volatile var files = 0
    @Volatile var recordingNow = false
    @Volatile var recordingSinceTs = 0L
    @Volatile var rawUntil = 0L
}

/**
 * Microphone experiment: one AudioRecord on the standard MIC source, listening continuously.
 *
 * Saving rule (sound-level trigger, works for any sound, not only speech):
 *  - a recording starts when the peak level of a 100 ms block reaches the sensitivity threshold;
 *    the preceding [PRE_ROLL_MS] of audio is included so the start of a sound is not lost;
 *  - it continues until the level has stayed more than [HYSTERESIS_DB] below the threshold for [HANGOVER_MS]
 *    (short dips do not split a recording); very long sounds are split into [PART_SECONDS] parts;
 *  - "Raw" forces unconditional recording for a fixed time (troubleshooting).
 * Lightweight status is logged every 5 s whether or not anything is saved. No VAD, wake word or ONNX is involved.
 */
class MicTest(private val app: JarvisApp, private val onStateChange: () -> Unit) {
    companion object {
        private val RATES = intArrayOf(44100, 48000, 16000) // 44.1 kHz is the only rate Android guarantees
        const val PRE_ROLL_MS = 1000
        const val HANGOVER_MS = 2000L
        const val HYSTERESIS_DB = 6.0
        private const val PART_SECONDS = 60
        private const val STATUS_INTERVAL_MS = 5000L
        private const val NO_AUDIO_MS = 2000L
        private const val RESTART_AFTER_MS = 6000L
        const val DEFAULT_SENSITIVITY = 45
        private val fileFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US) }
        fun db(v: Double) = if (v <= 0.0) -120.0 else max(-120.0, 20.0 * log10(v / 32768.0))
        fun r1(v: Double) = Math.round(v * 10.0) / 10.0

        /** Sensitivity 0..100 → trigger threshold in dBFS (peak). 100 = −90 dBFS (very sensitive), 0 = −10 dBFS. */
        fun thresholdDb(sensitivity: Int): Double = -90.0 + (100 - sensitivity.coerceIn(0, 100)) * 0.8

        /**
         * Called once per process start (before any test can run in this process): WAV parts still marked RECORDING
         * belong to a session that was killed. Fix their header to the full written length and mark them INTERRUPTED.
         */
        fun recoverOrphans(app: JarvisApp) {
            val store = app.store
            for (r in store.recordings("MICTEST/RECORDING", 100)) {
                val f = store.fileFor(r)
                if (!f.exists()) { store.deleteRecording(r.id); continue }
                val dataBytes = ((f.length() - Wav.HEADER_BYTES).coerceAtLeast(0) / 2) * 2
                runCatching { RandomAccessFile(f, "rw").use { it.seek(0); it.write(Wav.header(dataBytes, r.sampleRate)) } }
                val meta = (r.meta?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject())
                    .put("closeReason", "APP_KILLED_RECOVERED_ON_NEXT_START")
                    .put("statsNote", "rms/peak/zero counts cover only the audio up to the last 10 s checkpoint")
                store.insertRecording(r.copy(category = "INTERRUPTED", bytes = f.length(),
                    durationMs = dataBytes / 2 * 1000 / r.sampleRate, meta = meta.toString()))
                store.log("MICTEST_FILE_RECOVERED", "${r.file}: session was killed while recording; file kept (${dataBytes / 2 * 1000 / r.sampleRate} ms)",
                    JSONObject().put("bytes", f.length()), interactionId = r.interactionId, recordingId = r.id)
            }
        }
    }

    private val store = app.store
    val sessionId: String = DiagStore.newId("mic")
    @Volatile private var running = false
    @Volatile private var rec: AudioRecord? = null
    @Volatile private var restartReason: String? = null
    @Volatile private var micOpenTs = 0L
    @Volatile private var noAudioFlag = false
    private var thread: Thread? = null
    private var watchdog: Thread? = null
    private var ring: PcmRingBuffer? = null
    private var event: WavPart? = null
    private var eventLastLoudTs = 0L
    private var partNo = 0
    private val lock = Any()

    // session totals
    private var totalSamples = 0L
    private var totalZero = 0L
    private var totalReads = 0L
    private var emptyReads = 0L
    private var readErrors = 0
    private var micOpens = 0
    private var micOpenMsTotal = 0L
    private var maxPeak = 0
    private var zeroRun = 0L
    private var zerosFlag = false
    private var firstSamplesLogged = false
    private var recordingsSaved = 0
    private var savedMs = 0L

    // status window (guarded by lock)
    private var winStart = 0L
    private var winSamples = 0L
    private var winZero = 0L
    private var winSumSq = 0.0
    private var winPeak = 0
    private var winReads = 0L
    private var winEmptyReads = 0L
    private var winBlocks = 0
    private var winAbove = 0

    fun start(resumedAfterRestart: Boolean = false) {
        running = true
        MicStatus.running = true
        MicStatus.sessionId = sessionId
        MicStatus.startTs = System.currentTimeMillis()
        MicStatus.samples = 0; MicStatus.files = 0; MicStatus.sampleRate = 0
        MicStatus.levelDb = -120.0; MicStatus.peakDb = -120.0; MicStatus.maxPeakDb = -120.0
        MicStatus.recordingNow = false; MicStatus.rawUntil = 0
        setState("STARTING", "Starting microphone…")
        val am = app.getSystemService(AudioManager::class.java)
        val sens = app.prefs.micSensitivity
        store.log(
            "MICTEST_SESSION_START", if (resumedAfterRestart) "resumed by Android after the app was restarted" else "user pressed Start",
            JSONObject().put("session", sessionId).put("resumedAfterRestart", resumedAfterRestart)
                .put("sensitivity", sens).put("thresholdDb", thresholdDb(sens)).put("preRollMs", PRE_ROLL_MS)
                .put("hangoverMs", HANGOVER_MS).put("hysteresisDb", HYSTERESIS_DB)
                .put("permissions", Diagnostics.permissions(app)).put("power", Diagnostics.power(app))
                .put("battery", Diagnostics.battery(app)).put("otherActiveRecordings", am.activeRecordingConfigurations.size)
                .put("audioMode", Diagnostics.audioModeName(am.mode)).put("micMuted", am.isMicrophoneMute)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                .put("app", "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})"),
            interactionId = sessionId, sync = true
        )
        thread = Thread({ runCapture() }, "jarvis-mictest").also { it.start() }
        watchdog = Thread({ runWatchdog() }, "jarvis-mictest-watchdog").also { it.start() }
    }

    fun stop() {
        running = false
        runCatching { rec?.stop() } // unblocks a pending read()
        thread?.join(4000)
        watchdog?.interrupt()
        watchdog?.join(1000)
    }

    /** Record everything (no threshold) for the next [seconds] seconds. */
    fun forceRaw(seconds: Int) {
        MicStatus.rawUntil = System.currentTimeMillis() + seconds * 1000L
        store.log("MICTEST_RAW_STARTED", "Recording everything for ${seconds}s regardless of the threshold", interactionId = sessionId)
    }

    private fun setState(state: String, message: String) {
        val changed = MicStatus.state != state
        MicStatus.state = state
        MicStatus.message = message
        if (changed) onStateChange()
    }

    // ------------------------------------------------------------------ capture thread

    private fun runCapture() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var failures = 0
        while (running) {
            if (!Diagnostics.hasMicPermission(app)) {
                setState("FAILED", "Microphone permission is not granted. Allow it in app settings → Permissions → Microphone.")
                store.log("MICTEST_PERMISSION_DENIED", "RECORD_AUDIO not granted; cannot capture", interactionId = sessionId, sync = true)
                sleepWhileRunning(3000)
                continue
            }
            val r = openMic()
            if (r == null) {
                failures++
                setState("FAILED", "The microphone could not be opened (attempt $failures). Retrying… Details are in the log.")
                sleepWhileRunning(minOf(2000L * failures, 10_000L))
                continue
            }
            failures = 0
            rec = r
            micOpens++
            micOpenTs = System.currentTimeMillis()
            noAudioFlag = false
            LiveStatus.micSessionId = r.audioSessionId
            MicStatus.sampleRate = r.sampleRate
            synchronized(lock) { resetWindow(micOpenTs); ring = PcmRingBuffer(r.sampleRate * 2) }
            setState("STARTING", "Microphone opened (${r.sampleRate} Hz) – waiting for audio…")
            val reason = try {
                captureLoop(r)
            } catch (t: Throwable) {
                store.log("MICTEST_CAPTURE_EXCEPTION", t.toString(), JSONObject().put("stack", t.stackTraceToString().take(4000)),
                    interactionId = sessionId, sync = true)
                "EXCEPTION"
            }
            rec = null
            micOpenMsTotal += System.currentTimeMillis() - micOpenTs
            micOpenTs = 0
            runCatching { r.stop() }
            runCatching { r.release() }
            LiveStatus.micSessionId = -1
            synchronized(lock) { finishEvent(if (reason == "STOP") "LISTENING_STOPPED" else "MIC_INTERRUPTED") }
            store.log(
                if (reason == "STOP") "MICTEST_MIC_CLOSED" else "MICTEST_MIC_INTERRUPTED", reason,
                JSONObject().put("session", sessionId).put("reason", reason), interactionId = sessionId
            )
            if (running) {
                setState("STARTING", "Microphone interrupted ($reason) – reopening…")
                sleepWhileRunning(500)
            }
        }
        logSummary()
        MicStatus.running = false
        MicStatus.recordingNow = false
    }

    private fun sleepWhileRunning(ms: Long) {
        val until = SystemClock.elapsedRealtime() + ms
        while (running && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
    }

    private fun openMic(): AudioRecord? {
        val attempts = JSONArray()
        for (rate in RATES) {
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val a = JSONObject().put("source", "MIC").put("sampleRate", rate).put("minBufferBytes", minBuf)
            attempts.put(a)
            if (minBuf <= 0) { a.put("result", "getMinBufferSize failed ($minBuf)"); continue }
            val bytes = max(minBuf * 4, rate) // at least ~0.5 s of audio
            a.put("bufferBytes", bytes)
            val r = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes)
            } catch (e: Exception) {
                a.put("result", "constructor failed: $e"); continue
            }
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                a.put("result", "not initialized (state ${r.state})"); r.release(); continue
            }
            try {
                r.startRecording()
            } catch (e: Exception) {
                a.put("result", "startRecording failed: $e"); r.release(); continue
            }
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                a.put("result", "not recording after startRecording (state ${r.recordingState}) – microphone busy or blocked?")
                r.release(); continue
            }
            a.put("result", "OK")
            val info = JSONObject().put("session", sessionId).put("attempts", attempts).put("source", "MIC")
                .put("sampleRate", r.sampleRate).put("channels", r.channelCount).put("format", "PCM_16BIT")
                .put("bufferBytes", bytes).put("audioSessionId", r.audioSessionId)
            if (Build.VERSION.SDK_INT >= 23) info.put("bufferSizeInFrames", r.bufferSizeInFrames)
            runCatching { r.routedDevice?.let { info.put("routedDevice", "${it.productName} type=${it.type} id=${it.id}") } ?: info.put("routedDevice", Diagnostics.UNAVAILABLE) }
            if (Build.VERSION.SDK_INT >= 28) runCatching {
                val mics = JSONArray(); r.activeMicrophones.forEach { m -> mics.put("${m.description} loc=${m.location} addr=${m.address}") }
                info.put("activeMicrophones", mics)
            }
            store.log("MICTEST_MIC_OPENED", "MIC ${r.sampleRate} Hz mono 16-bit, buffer $bytes bytes", info, interactionId = sessionId)
            return r
        }
        store.log(
            "MICTEST_MIC_OPEN_FAILED", "Could not open the microphone with any configuration",
            JSONObject().put("session", sessionId).put("attempts", attempts).put("permissions", Diagnostics.permissions(app))
                .put("otherActiveRecordings", app.getSystemService(AudioManager::class.java).activeRecordingConfigurations.size),
            interactionId = sessionId, sync = true
        )
        return null
    }

    private fun captureLoop(r: AudioRecord): String {
        val rate = r.sampleRate
        val buf = ShortArray(rate / 10) // 100 ms per read
        while (running) {
            restartReason?.let { restartReason = null; return it }
            val n = r.read(buf, 0, buf.size)
            val now = System.currentTimeMillis()
            totalReads++
            synchronized(lock) { winReads++ }
            if (n > 0) consume(buf, n, rate, now)
            if (!running) return "STOP"
            restartReason?.let { restartReason = null; return it }
            if (n < 0) {
                readErrors++
                val name = when (n) {
                    AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                    AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
                    AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
                    else -> "ERROR($n)"
                }
                store.log("MICTEST_READ_ERROR", "AudioRecord.read returned $name", JSONObject().put("code", n)
                    .put("recordingState", r.recordingState), interactionId = sessionId)
                return "READ_ERROR_$name"
            }
            if (n == 0) {
                emptyReads++
                synchronized(lock) { winEmptyReads++ }
                SystemClock.sleep(10)
            }
        }
        return "STOP"
    }

    private fun consume(buf: ShortArray, n: Int, rate: Int, now: Long) {
        var zeros = 0
        var sumSq = 0.0
        var peak = 0
        for (i in 0 until n) {
            val v = buf[i].toInt()
            if (v == 0) zeros++
            sumSq += v.toDouble() * v
            val a = abs(v)
            if (a > peak) peak = a
        }
        val peakDb = db(peak.toDouble())
        val sens = app.prefs.micSensitivity
        val thr = thresholdDb(sens)
        val forced = now < MicStatus.rawUntil
        val above = peakDb >= thr

        synchronized(lock) {
            winSamples += n; winZero += zeros; winSumSq += sumSq; winPeak = max(winPeak, peak)
            winBlocks++
            if (above) winAbove++
            var ev = event
            if (ev == null && (above || forced)) {
                // Start a recording, including the audio just before the trigger.
                val pre = ring?.copyLast(rate * PRE_ROLL_MS / 1000) ?: ShortArray(0)
                val trigger = if (above) "LEVEL" else "RAW_BUTTON"
                ev = WavPart(++partNo, rate, now - (pre.size + n) * 1000L / rate, trigger, peakDb, thr, sens,
                    pre.size * 1000 / rate, null)
                event = ev
                ev.write(pre, pre.size)
                MicStatus.recordingNow = true
                MicStatus.recordingSinceTs = ev.startTs
                store.log(
                    "MICTEST_RECORDING_STARTED",
                    if (above) "sound %.1f dBFS reached threshold %.1f dBFS (sensitivity %d)".format(peakDb, thr, sens)
                    else "raw (unconditional) recording",
                    JSONObject().put("trigger", trigger).put("triggerPeakDb", r1(peakDb)).put("thresholdDb", thr)
                        .put("sensitivity", sens).put("preRollMs", pre.size * 1000 / rate),
                    interactionId = sessionId, recordingId = ev.id
                )
            }
            if (ev != null) {
                ev.write(buf, n)
                ev.lastThresholdDb = thr
                if (forced || peakDb >= thr - HYSTERESIS_DB) eventLastLoudTs = now
                if (now - eventLastLoudTs >= HANGOVER_MS) {
                    finishEvent("QUIET_FOR_${HANGOVER_MS}_MS")
                } else if (ev.samples >= rate.toLong() * PART_SECONDS) {
                    // Very long sound: split into parts, the next part continues seamlessly.
                    val prevId = ev.id
                    ev.close("MAX_LENGTH_${PART_SECONDS}S")
                    countSaved(ev)
                    val next = WavPart(++partNo, rate, now, ev.trigger, peakDb, thr, sens, 0, prevId)
                    event = next
                    MicStatus.recordingSinceTs = next.startTs
                }
            }
        }
        ring?.write(buf, 0, n)

        totalSamples += n
        totalZero += zeros
        maxPeak = max(maxPeak, peak)
        MicStatus.samples = totalSamples
        MicStatus.lastSampleTs = now
        MicStatus.levelDb = db(sqrt(sumSq / n))
        MicStatus.peakDb = peakDb
        MicStatus.maxPeakDb = db(maxPeak.toDouble())

        if (!firstSamplesLogged) {
            firstSamplesLogged = true
            store.log("MICTEST_FIRST_SAMPLES", "First audio samples arrived ${now - micOpenTs} ms after opening the microphone",
                JSONObject().put("latencyMs", now - micOpenTs).put("samples", n).put("allZero", zeros == n), interactionId = sessionId)
        }
        if (noAudioFlag) {
            noAudioFlag = false
            store.log("MICTEST_AUDIO_RESUMED", "Audio samples are arriving again", interactionId = sessionId)
        }
        if (zeros == n) {
            zeroRun += n
            if (!zerosFlag && zeroRun >= rate * 2L) {
                zerosFlag = true
                store.log("MICTEST_ONLY_ZEROS",
                    "Android delivers audio samples, but every sample for 2 s is exactly zero (capture silenced: privacy switch, another app/call, or background restriction)",
                    JSONObject().put("power", Diagnostics.power(app)), interactionId = sessionId)
                setState("ONLY_ZEROS", "Android sends audio, but it is pure digital silence (all zeros). The microphone is blocked or silenced.")
            }
        } else {
            if (zerosFlag) store.log("MICTEST_NONZERO_RESUMED", "Non-zero audio samples are arriving again after ${zeroRun * 1000 / rate} ms of zeros", interactionId = sessionId)
            zerosFlag = false
            zeroRun = 0
            if (MicStatus.state != "LISTENING") setState("LISTENING", "Audio is arriving from the microphone.")
        }
    }

    /** Must be called with [lock] held. */
    private fun finishEvent(reason: String) {
        val ev = event ?: return
        event = null
        ev.close(reason)
        countSaved(ev)
        MicStatus.recordingNow = false
    }

    private fun countSaved(ev: WavPart) {
        recordingsSaved++
        savedMs += ev.samples * 1000 / ev.rate
        MicStatus.files = recordingsSaved
    }

    // ------------------------------------------------------------------ watchdog + periodic status

    private fun runWatchdog() {
        var lastStatus = System.currentTimeMillis()
        while (running) {
            try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            val now = System.currentTimeMillis()
            val openTs = micOpenTs
            if (openTs > 0) {
                val ref = max(openTs, MicStatus.lastSampleTs)
                val silentFor = now - ref
                if (silentFor >= NO_AUDIO_MS && !noAudioFlag) {
                    noAudioFlag = true
                    store.log("MICTEST_NO_AUDIO", "No audio samples received from Android for $silentFor ms (read() is not returning data)",
                        JSONObject().put("msWithoutSamples", silentFor).put("recordingState", rec?.recordingState ?: -1)
                            .put("power", Diagnostics.power(app)), interactionId = sessionId)
                    setState("NO_AUDIO", "The microphone is open, but Android is not delivering any audio samples.")
                }
                if (silentFor >= RESTART_AFTER_MS && restartReason == null && rec != null) {
                    restartReason = "NO_AUDIO_FOR_${silentFor}_MS"
                    store.log("MICTEST_MIC_RESTART", "No audio for $silentFor ms – restarting the microphone", interactionId = sessionId)
                    runCatching { rec?.stop() }
                }
            }
            if (now - lastStatus >= STATUS_INTERVAL_MS) {
                logStatus(now)
                lastStatus = now
            }
        }
    }

    private fun resetWindow(now: Long) {
        winStart = now; winSamples = 0; winZero = 0; winSumSq = 0.0; winPeak = 0; winReads = 0; winEmptyReads = 0
        winBlocks = 0; winAbove = 0
    }

    private fun logStatus(now: Long) {
        val o = JSONObject()
        val msg: String
        val sens = app.prefs.micSensitivity
        val thr = thresholdDb(sens)
        synchronized(lock) {
            val windowMs = now - winStart
            val rate = MicStatus.sampleRate
            val micOpen = micOpenTs > 0
            val expected = if (micOpen && rate > 0) rate.toLong() * windowMs / 1000 else 0L
            val rms = if (winSamples > 0) db(sqrt(winSumSq / winSamples)) else -120.0
            val pk = db(winPeak.toDouble())
            val allZero = winSamples > 0 && winZero == winSamples
            val arriving = winSamples > 0
            val recording = event != null
            o.put("session", sessionId).put("windowMs", windowMs).put("micOpen", micOpen).put("sampleRate", rate)
                .put("samplesArriving", arriving).put("samples", winSamples).put("expectedSamples", expected)
                .put("deliveredPct", if (expected > 0) Math.round(winSamples * 1000.0 / expected) / 10.0 else JSONObject.NULL)
                .put("rmsDb", r1(rms)).put("peakDb", r1(pk)).put("zeroSamples", winZero).put("allZero", allZero)
                .put("sensitivity", sens).put("thresholdDb", thr).put("blocks", winBlocks).put("blocksAboveThreshold", winAbove)
                .put("recordingActive", recording).put("recordingsSavedTotal", recordingsSaved)
                .put("rawActive", now < MicStatus.rawUntil)
                .put("reads", winReads).put("emptyReads", winEmptyReads).put("readErrorsTotal", readErrors)
                .put("recordingState", rec?.recordingState ?: -1).put("silencedByAndroid", LiveStatus.micSilencedByOs ?: JSONObject.NULL)
                .put("otherRecordingClients", LiveStatus.otherRecordingClients)
                .put("screenOn", runCatching { Diagnostics.power(app).optBoolean("screenInteractive") }.getOrNull())
                .put("file", event?.file?.name)
            msg = when {
                !micOpen -> "microphone NOT open"
                !arriving -> "NO audio samples arriving"
                allZero -> "samples arriving but ALL ZERO (silenced)"
                else -> "audio arriving: avg %.1f dBFS, peak %.1f dBFS vs threshold %.1f dBFS → %s (%s%% of expected samples)".format(
                    rms, pk, thr,
                    when {
                        recording -> "RECORDING"
                        winAbove > 0 -> "above threshold"
                        else -> "below threshold, nothing saved"
                    },
                    o.opt("deliveredPct"))
            }
            resetWindow(now)
        }
        store.log("MICTEST_STATUS", msg, o, interactionId = sessionId)
    }

    private fun logSummary() {
        val now = System.currentTimeMillis()
        val rate = MicStatus.sampleRate
        val expected = if (rate > 0) rate.toLong() * micOpenMsTotal / 1000 else 0L
        val maxDb = db(maxPeak.toDouble())
        val sens = app.prefs.micSensitivity
        val thr = thresholdDb(sens)
        val verdict = when {
            micOpens == 0 -> "THE MICROPHONE COULD NOT BE OPENED"
            totalSamples == 0L -> "NO AUDIO SAMPLES WERE RECEIVED FROM ANDROID"
            totalZero == totalSamples -> "Android delivered audio samples, but ALL of them were zero (microphone silenced/blocked)"
            recordingsSaved == 0 -> "Audio received (loudest %.1f dBFS), but nothing reached the threshold (%.1f dBFS): no recordings saved".format(maxDb, thr)
            else -> "Audio received (loudest %.1f dBFS); %d recording(s), %.0f s saved (threshold %.1f dBFS)".format(maxDb, recordingsSaved, savedMs / 1000.0, thr)
        }
        store.log(
            "MICTEST_SESSION_STOP", verdict,
            JSONObject().put("session", sessionId).put("durationMs", now - MicStatus.startTs).put("micOpenMs", micOpenMsTotal)
                .put("sampleRate", rate).put("samples", totalSamples).put("expectedSamples", expected)
                .put("deliveredPct", if (expected > 0) Math.round(totalSamples * 1000.0 / expected) / 10.0 else JSONObject.NULL)
                .put("zeroSamples", totalZero).put("allZero", totalSamples > 0 && totalZero == totalSamples)
                .put("loudestPeakDb", r1(maxDb)).put("sensitivity", sens).put("thresholdDb", thr)
                .put("recordingsSaved", recordingsSaved).put("savedMs", savedMs).put("micOpens", micOpens)
                .put("reads", totalReads).put("emptyReads", emptyReads).put("readErrors", readErrors),
            interactionId = sessionId, sync = true
        )
        setState(if (micOpens == 0 || totalSamples == 0L) "FAILED" else "STOPPED", verdict)
    }

    // ------------------------------------------------------------------ WAV files

    /** One saved recording. The header is rewritten every ~2 s so the file stays playable even if the app is killed. */
    private inner class WavPart(
        val no: Int, val rate: Int, val startTs: Long, val trigger: String, val triggerPeakDb: Double,
        val thresholdAtStart: Double, val sensitivityAtStart: Int, val preRollMs: Int, val continuationOf: String?,
    ) {
        val id = "$sessionId-p" + "%03d".format(no)
        val file = File(store.audioDir, fileFmt.get()!!.format(Date(startTs)) + "_mictest_" + sessionId.takeLast(6) + "_p" + "%03d".format(no) + ".wav")
        private val raf = RandomAccessFile(file, "rw")
        var samples = 0L
        var lastThresholdDb = thresholdAtStart
        private var zeroSamples = 0L
        private var sumSq = 0.0
        private var peak = 0
        private var headerAt = 0L
        private var rowAt = 0L
        private var bytes = ByteArray(0)

        init {
            raf.setLength(0)
            raf.write(Wav.header(0, rate))
            saveRow(null)
        }

        fun write(buf: ShortArray, n: Int) {
            if (n <= 0) return
            if (bytes.size < n * 2) bytes = ByteArray(n * 2)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) {
                val v = buf[i]
                bb.putShort(v)
                val iv = v.toInt()
                if (iv == 0) zeroSamples++
                sumSq += iv.toDouble() * iv
                val a = abs(iv)
                if (a > peak) peak = a
            }
            raf.write(bytes, 0, n * 2)
            samples += n
            if (samples - headerAt >= rate * 2L) { writeHeader(); headerAt = samples }
            if (samples - rowAt >= rate * 10L) { saveRow(null); rowAt = samples }
        }

        private fun writeHeader() {
            val pos = raf.filePointer
            raf.seek(0)
            raf.write(Wav.header(samples * 2, rate))
            raf.seek(pos)
        }

        fun close(reason: String) {
            runCatching { writeHeader(); raf.fd.sync() }
            runCatching { raf.close() }
            saveRow(reason)
            store.log(
                "MICTEST_FILE_SAVED", "${file.name}: ${samples * 1000 / rate} ms, peak %.1f dBFS, threshold %.1f dBFS, %s".format(
                    db(peak.toDouble()), lastThresholdDb, reason),
                JSONObject().put("reason", reason).put("trigger", trigger).put("samples", samples).put("zeroSamples", zeroSamples)
                    .put("bytes", file.length()).put("peakDb", r1(db(peak.toDouble())))
                    .put("rmsDb", r1(if (samples > 0) db(sqrt(sumSq / samples)) else -120.0))
                    .put("thresholdDb", lastThresholdDb).put("triggerPeakDb", r1(triggerPeakDb)),
                interactionId = sessionId, recordingId = id
            )
            store.runOnWriter {
                val (cnt, freed) = store.enforceStorageLimit(app.prefs.storageLimitMb.toLong() * 1024 * 1024)
                if (cnt > 0) store.log("STORAGE_PRUNED", "Deleted $cnt oldest recordings ($freed bytes) to stay under ${app.prefs.storageLimitMb} MB")
            }
        }

        private fun saveRow(closeReason: String?) {
            val category = when {
                closeReason == null -> "RECORDING"
                samples == 0L -> "EMPTY"
                zeroSamples == samples -> "ALL_ZEROS"
                trigger == "RAW_BUTTON" -> "RAW"
                else -> "TRIGGERED"
            }
            val meta = JSONObject().put("session", sessionId).put("part", no).put("source", "MIC").put("sampleRate", rate)
                .put("trigger", trigger).put("sensitivity", sensitivityAtStart).put("thresholdDb", thresholdAtStart)
                .put("thresholdDbAtEnd", lastThresholdDb).put("triggerPeakDb", r1(triggerPeakDb))
                .put("preRollMs", preRollMs).put("hangoverMs", HANGOVER_MS).put("hysteresisDb", HYSTERESIS_DB)
                .put("continuationOf", continuationOf ?: JSONObject.NULL)
                .put("samples", samples).put("zeroSamples", zeroSamples)
                .put("rmsDb", r1(if (samples > 0) db(sqrt(sumSq / samples)) else -120.0)).put("peakDb", r1(db(peak.toDouble())))
                .put("allZero", samples > 0 && zeroSamples == samples).put("closeReason", closeReason ?: JSONObject.NULL)
                .put("note", "Unmodified samples exactly as delivered by AudioRecord (no gain, no filtering)")
            val row = DiagStore.RecordingRow(
                id, startTs, System.currentTimeMillis(), "MICTEST", category, file.name, file.length(),
                samples * 1000 / rate, rate, sessionId, null, null, null, meta.toString()
            )
            store.runOnWriter { store.insertRecording(row) }
        }
    }
}
