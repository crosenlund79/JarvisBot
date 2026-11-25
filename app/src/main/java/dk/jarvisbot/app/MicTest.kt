package dk.jarvisbot.app

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.os.SystemClock
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
    @Volatile var files = 0
}

/**
 * Microphone experiment: the simplest possible capture.
 * One AudioRecord on the standard MIC source; every sample Android delivers is written, unconditionally and
 * unmodified, to rolling 60 s WAV files. No VAD, no wake word, no thresholds, no ONNX models are involved.
 * Diagnostics go to the normal event log (types MICTEST_*), linked by session id (interaction_id column).
 */
class MicTest(private val app: JarvisApp, private val onStateChange: () -> Unit) {
    companion object {
        private val RATES = intArrayOf(44100, 48000, 16000) // 44.1 kHz is the only rate Android guarantees
        private const val PART_SECONDS = 60
        private const val STATUS_INTERVAL_MS = 5000L
        private const val NO_AUDIO_MS = 2000L
        private const val RESTART_AFTER_MS = 6000L
        private val fileFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US) }
        fun db(v: Double) = if (v <= 0.0) -120.0 else max(-120.0, 20.0 * log10(v / 32768.0))
        fun r1(v: Double) = Math.round(v * 10.0) / 10.0
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
    private var part: WavPart? = null
    private var partNo = 0
    private val lock = Any()

    // session totals (audio thread; read under lock for the summary)
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

    // status window (guarded by lock)
    private var winStart = 0L
    private var winSamples = 0L
    private var winZero = 0L
    private var winSumSq = 0.0
    private var winPeak = 0
    private var winReads = 0L
    private var winEmptyReads = 0L

    fun start(resumedAfterRestart: Boolean = false) {
        running = true
        MicStatus.running = true
        MicStatus.sessionId = sessionId
        MicStatus.startTs = System.currentTimeMillis()
        MicStatus.samples = 0; MicStatus.files = 0; MicStatus.sampleRate = 0
        MicStatus.levelDb = -120.0; MicStatus.peakDb = -120.0; MicStatus.maxPeakDb = -120.0
        setState("STARTING", "Starting microphone…")
        val am = app.getSystemService(AudioManager::class.java)
        store.log(
            "MICTEST_SESSION_START", if (resumedAfterRestart) "resumed by Android after the app was restarted" else "user pressed Start",
            JSONObject().put("session", sessionId).put("resumedAfterRestart", resumedAfterRestart)
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
            synchronized(lock) { resetWindow(micOpenTs) }
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
            store.log(
                if (reason == "STOP") "MICTEST_MIC_CLOSED" else "MICTEST_MIC_INTERRUPTED", reason,
                JSONObject().put("session", sessionId).put("reason", reason), interactionId = sessionId
            )
            if (running) {
                setState("STARTING", "Microphone interrupted ($reason) – reopening…")
                sleepWhileRunning(500)
            }
        }
        synchronized(lock) { part?.close("SESSION_STOPPED"); part = null }
        logSummary()
        MicStatus.running = false
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
        synchronized(lock) {
            var p = part
            if (p == null || p.rate != rate || p.samples >= rate.toLong() * PART_SECONDS) {
                p?.close(if (p.rate != rate) "SAMPLE_RATE_CHANGED" else "PART_FULL")
                p = WavPart(++partNo, rate, now - n * 1000L / rate)
                part = p
            }
            p.write(buf, n, zeros, sumSq, peak)
            winSamples += n; winZero += zeros; winSumSq += sumSq; winPeak = max(winPeak, peak)
        }
        totalSamples += n
        totalZero += zeros
        maxPeak = max(maxPeak, peak)
        MicStatus.samples = totalSamples
        MicStatus.lastSampleTs = now
        MicStatus.levelDb = db(sqrt(sumSq / n))
        MicStatus.peakDb = db(peak.toDouble())
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
    }

    private fun logStatus(now: Long) {
        val o = JSONObject()
        val msg: String
        synchronized(lock) {
            val windowMs = now - winStart
            val rate = MicStatus.sampleRate
            val micOpen = micOpenTs > 0
            val expected = if (micOpen && rate > 0) rate.toLong() * windowMs / 1000 else 0L
            val rms = if (winSamples > 0) db(sqrt(winSumSq / winSamples)) else -120.0
            val pk = db(winPeak.toDouble())
            val allZero = winSamples > 0 && winZero == winSamples
            val arriving = winSamples > 0
            o.put("session", sessionId).put("windowMs", windowMs).put("micOpen", micOpen).put("sampleRate", rate)
                .put("samplesArriving", arriving).put("samples", winSamples).put("expectedSamples", expected)
                .put("deliveredPct", if (expected > 0) Math.round(winSamples * 1000.0 / expected) / 10.0 else JSONObject.NULL)
                .put("rmsDb", r1(rms)).put("peakDb", r1(pk)).put("zeroSamples", winZero).put("allZero", allZero)
                .put("reads", winReads).put("emptyReads", winEmptyReads).put("readErrorsTotal", readErrors)
                .put("recordingState", rec?.recordingState ?: -1).put("silencedByAndroid", LiveStatus.micSilencedByOs ?: JSONObject.NULL)
                .put("otherRecordingClients", LiveStatus.otherRecordingClients)
                .put("screenOn", runCatching { Diagnostics.power(app).optBoolean("screenInteractive") }.getOrNull())
                .put("file", part?.file?.name)
            msg = when {
                !micOpen -> "microphone NOT open"
                !arriving -> "NO audio samples arriving"
                allZero -> "samples arriving but ALL ZERO (silenced)"
                else -> "audio arriving: avg %.1f dBFS, peak %.1f dBFS, %d samples (%s%% of expected)".format(
                    rms, pk, winSamples, o.opt("deliveredPct"))
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
        val verdict = when {
            micOpens == 0 -> "THE MICROPHONE COULD NOT BE OPENED"
            totalSamples == 0L -> "NO AUDIO SAMPLES WERE RECEIVED FROM ANDROID"
            totalZero == totalSamples -> "Android delivered audio samples, but ALL of them were zero (microphone silenced/blocked)"
            maxDb < -60 -> "Audio received, but extremely quiet (loudest %.1f dBFS)".format(maxDb)
            else -> "Audio received (loudest %.1f dBFS)".format(maxDb)
        }
        store.log(
            "MICTEST_SESSION_STOP", verdict,
            JSONObject().put("session", sessionId).put("durationMs", now - MicStatus.startTs).put("micOpenMs", micOpenMsTotal)
                .put("sampleRate", rate).put("samples", totalSamples).put("expectedSamples", expected)
                .put("deliveredPct", if (expected > 0) Math.round(totalSamples * 1000.0 / expected) / 10.0 else JSONObject.NULL)
                .put("zeroSamples", totalZero).put("allZero", totalSamples > 0 && totalZero == totalSamples)
                .put("loudestPeakDb", r1(maxDb)).put("files", partNo).put("micOpens", micOpens)
                .put("reads", totalReads).put("emptyReads", emptyReads).put("readErrors", readErrors),
            interactionId = sessionId, sync = true
        )
        setState(if (micOpens == 0 || totalSamples == 0L) "FAILED" else "STOPPED", verdict)
    }

    // ------------------------------------------------------------------ WAV parts

    /** One WAV file. The header is rewritten every ~2 s so the file stays playable even if the app is killed. */
    private inner class WavPart(val no: Int, val rate: Int, val startTs: Long) {
        val id = "$sessionId-p" + "%03d".format(no)
        val file = File(store.audioDir, fileFmt.get()!!.format(Date(startTs)) + "_mictest_" + sessionId.takeLast(6) + "_p" + "%03d".format(no) + ".wav")
        private val raf = RandomAccessFile(file, "rw")
        var samples = 0L
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
            MicStatus.files = no
        }

        fun write(buf: ShortArray, n: Int, zeros: Int, sq: Double, pk: Int) {
            if (bytes.size < n * 2) bytes = ByteArray(n * 2)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) bb.putShort(buf[i])
            raf.write(bytes, 0, n * 2)
            samples += n; zeroSamples += zeros; sumSq += sq; peak = max(peak, pk)
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
                "MICTEST_FILE_SAVED", "${file.name}: ${samples * 1000 / rate} ms, peak %.1f dBFS%s".format(
                    db(peak.toDouble()), if (samples > 0 && zeroSamples == samples) ", ALL ZEROS" else ""),
                JSONObject().put("reason", reason).put("samples", samples).put("zeroSamples", zeroSamples).put("bytes", file.length()),
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
                else -> "AUDIO"
            }
            val meta = JSONObject().put("session", sessionId).put("part", no).put("source", "MIC").put("sampleRate", rate)
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
