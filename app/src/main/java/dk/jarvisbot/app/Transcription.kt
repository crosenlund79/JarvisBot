package dk.jarvisbot.app

import ai.onnxruntime.OrtEnvironment
import android.os.Process
import dk.jarvisbot.core.SileroVad
import dk.jarvisbot.core.SpeechTranscriber
import dk.jarvisbot.core.Wav
import dk.jarvisbot.core.WhisperMel
import dk.jarvisbot.core.WhisperModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline transcription of saved recordings (Danish / English) into one human-readable text file per day.
 *
 * - Runs on one low-priority background thread; the microphone capture is never touched.
 * - Mode OFF / AUTO (each completed recording, newest first) / EXPORT (only when a diagnostic ZIP is made).
 * - Results live in the `transcripts` table (one row per recording and audio variant, so reprocessing never
 *   duplicates); the daily files are rendered from it, in recording order, by the recording's own date.
 * - Daily files are written locally (filesDir/transcripts) and copied to the sync folder under a temporary name,
 *   then renamed, so a sync app never sees a half-written file.
 */
object Transcription {
    const val ENGINE = "Whisper small (OpenAI, multilingual) as int8 ONNX, running offline on the phone"
    const val ENGINE_ID = "whisper-small-int8 (sherpa-onnx export, jarvis decoder v1)"
    val LANGUAGES = listOf("da", "en")
    val MODES = listOf("OFF" to "Off", "AUTO" to "Automatic (each new recording)", "EXPORT" to "During export only")
    private const val MODEL_DIR = "models/whisper"
    private const val RELEASE_AFTER_IDLE_MS = 5 * 60_000L
    private const val SYNC_INTERVAL_MS = 2 * 60_000L
    private const val VARIANT_RAW = "RAW"
    private const val VARIANT_ENHANCED = "ENHANCED"

    private val app get() = JarvisApp.instance
    private val jobs = LinkedBlockingQueue<Runnable>()
    private val KICK = Runnable {}
    private val started = AtomicBoolean(false)
    private val dayFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    private val timeFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.US) }
    private val stampFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }

    /** What is being transcribed right now (for the UI), or null. */
    @Volatile var current: String? = null
        private set
    /** Set when the recognizer could not be loaded; automatic processing pauses until the mode is changed or the app restarts. */
    @Volatile var engineProblem: String? = null
        private set
    @Volatile var doneThisRun = 0
        private set

    // ---- worker-thread state
    private var engine: Engine? = null
    private var lastUse = 0L
    private val dirtyDays = LinkedHashSet<String>()
    private var lastSync = 0L

    val dir: File get() = File(app.filesDir, "transcripts").apply { mkdirs() }
    fun mode(): String = app.prefs.transcriptionMode
    fun dayOf(ts: Long): String = dayFmt.get()!!.format(Date(ts))
    fun fileName(day: String) = "${day}_Transcription.txt"

    fun start() {
        if (started.compareAndSet(false, true)) Thread({ loop() }, "jarvis-transcribe").apply { isDaemon = true }.start()
        kick()
    }

    private fun kick() { jobs.offer(KICK) }

    fun onRecordingSaved(r: DiagStore.RecordingRow) {
        if (mode() == "AUTO" && eligible(r)) kick()
    }

    fun modeChanged() {
        app.store.log("TRANSCRIPTION_MODE", mode())
        engineProblem = null
        kick()
    }

    /** New sync folder: copy all daily files there. */
    fun publishAllDays() {
        start()
        jobs.offer(Runnable { dirtyDays.addAll(app.store.transcriptDays()); flush(force = true) })
    }

    /** After "delete all": local daily files go too (copies in the sync folder are kept). */
    fun clearLocalFiles() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun eligible(r: DiagStore.RecordingRow) = r.category != "RECORDING" && r.category != "EMPTY"

    // ------------------------------------------------------------------ export support (called on the export thread)

    /** Transcribes recordings whose original audio has no transcription yet (or failed before). Returns a problem, if any. */
    fun prepareExport(recs: List<DiagStore.RecordingRow>, progress: (String) -> Unit): String? {
        if (mode() == "OFF") return null
        val store = app.store
        val todo = recs.filter { eligible(it) && store.transcript(it.id, VARIANT_RAW).let { t -> t == null || t.status == "ERROR" } }
        todo.forEachIndexed { i, r ->
            progress("Transcribing ${i + 1} / ${todo.size} (offline speech recognition)…")
            try {
                onWorker { transcribe(r, VARIANT_RAW, null, SyncFolder.baseName(r) + "_raw.wav", null) }
            } catch (e: EngineUnavailable) {
                return "transcription unavailable: ${e.message}"
            }
        }
        return null
    }

    /** Transcribes the voice-enhanced version computed for an export (only if not done before). */
    fun transcribeEnhanced(r: DiagStore.RecordingRow, samples: ShortArray, sampleRate: Int, enhancedName: String, method: String) {
        if (mode() == "OFF") return
        val old = app.store.transcript(r.id, VARIANT_ENHANCED)
        if (old != null && old.status != "ERROR" && old.audioName == enhancedName) return
        try {
            onWorker { transcribe(r, VARIANT_ENHANCED, samples to sampleRate, enhancedName, JSONObject().put("enhancementMethod", method)) }
        } catch (_: EngineUnavailable) {
        }
    }

    /** All daily files (name to content) for the ZIP; also refreshes the local files and the sync-folder copies. */
    fun dailyFilesForExport(): List<Pair<String, String>> {
        runCatching { onWorker { flush(force = true) } }
        return app.store.transcriptDays().sorted().map { fileName(it) to render(it) }
    }

    private fun <T> onWorker(block: () -> T): T {
        start()
        val task = FutureTask(block)
        jobs.offer(task)
        try {
            return task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    // ------------------------------------------------------------------ worker

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) // ONNX Runtime's worker threads inherit this
        var busy = true
        while (true) {
            try {
                // Busy: look for more work at once. Idle: wake only for new work, or to release the model / sync a file.
                val job = when {
                    busy -> jobs.poll()
                    engine != null || dirtyDays.isNotEmpty() -> jobs.poll(30L, TimeUnit.SECONDS)
                    else -> jobs.take()
                }
                if (job != null && job !== KICK) { job.run(); busy = true; continue }
                if (mode() == "AUTO" && engineProblem == null) {
                    val r = app.store.nextUntranscribed()
                    if (r != null) {
                        try {
                            transcribe(r, VARIANT_RAW, null, SyncFolder.baseName(r) + "_raw.wav", null)
                        } catch (_: EngineUnavailable) {
                        }
                        busy = true
                        flush(force = false)
                        continue
                    }
                }
                busy = false
                flush(force = true)
                if (engine != null && System.currentTimeMillis() - lastUse > RELEASE_AFTER_IDLE_MS) releaseEngine("idle")
            } catch (t: Throwable) {
                busy = false
                runCatching { app.store.log("TRANSCRIPTION_ERROR", t.toString(), JSONObject().put("stack", t.stackTraceToString().take(4000))) }
            }
        }
    }

    private class EngineUnavailable(msg: String) : Exception(msg)

    /** Voice detector (small, loaded at once) + Whisper (about 255 MB, loaded only when a recording contains speech). */
    private class Engine(private val app: JarvisApp, private val threads: Int) : AutoCloseable {
        private val env = OrtEnvironment.getEnvironment()
        private var whisper: WhisperModel? = null
        private val vad: SileroVad = SileroVad(env, app.assets.open("models/silero_vad.onnx").use { it.readBytes() })
        val transcriber = SpeechTranscriber({ whisper() }, vad, app.prefs.vadThreshold)
        val whisperLoaded get() = whisper != null

        private fun whisper(): WhisperModel {
            whisper?.let { return it }
            val t0 = System.currentTimeMillis()
            val was = current
            current = "loading the speech recognizer…"
            try {
                val mel = WhisperMel(WhisperMel.loadFilters(app.assets.open("$MODEL_DIR/mel_filters_80.bin").use { it.readBytes() }))
                val tokens = app.assets.open("$MODEL_DIR/small-tokens.txt").bufferedReader().use { it.readLines() }
                val w = WhisperModel(env, asset(app, "$MODEL_DIR/small-encoder.int8.onnx"), asset(app, "$MODEL_DIR/small-decoder.jarvis.onnx"),
                    tokens, threads, mel)
                whisper = w
                app.store.log("TRANSCRIPTION_ENGINE_LOADED", "${System.currentTimeMillis() - t0} ms, $threads threads",
                    JSONObject().put("engine", ENGINE).put("engineId", ENGINE_ID).put("threads", threads).put("loadMs", System.currentTimeMillis() - t0))
                return w
            } catch (t: Throwable) {
                engineProblem = t.toString()
                app.store.log("TRANSCRIPTION_ENGINE_FAILED", t.toString(), JSONObject().put("stack", t.stackTraceToString().take(4000)))
                throw EngineUnavailable(t.toString())
            } finally {
                current = was
            }
        }

        /** Memory-maps an uncompressed asset straight from the APK (no copy on disk); falls back to reading it. */
        private fun asset(app: JarvisApp, path: String): ByteBuffer = try {
            app.assets.openFd(path).use { afd ->
                FileInputStream(afd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        } catch (e: IOException) {
            val bytes = app.assets.open(path).use { it.readBytes() }
            ByteBuffer.allocateDirect(bytes.size).put(bytes).also { it.flip() }
        }

        override fun close() {
            runCatching { whisper?.close() }
            runCatching { vad.close() }
        }
    }

    private fun engine(): Engine {
        engine?.let { return it }
        val threads = Runtime.getRuntime().availableProcessors().let { (it / 2).coerceIn(2, 4) }
        try {
            return Engine(app, threads).also { engine = it }
        } catch (t: Throwable) {
            engineProblem = t.toString()
            app.store.log("TRANSCRIPTION_ENGINE_FAILED", t.toString(), JSONObject().put("stack", t.stackTraceToString().take(4000)))
            throw EngineUnavailable(t.toString())
        }
    }

    private fun releaseEngine(why: String) {
        val e = engine ?: return
        val hadWhisper = e.whisperLoaded
        e.close()
        engine = null
        if (hadWhisper) app.store.log("TRANSCRIPTION_ENGINE_RELEASED", why)
    }

    private fun transcribe(r: DiagStore.RecordingRow, variant: String, audio: Pair<ShortArray, Int>?, audioName: String, extra: JSONObject?) {
        val store = app.store
        val day = dayOf(r.tsStart)
        current = (if (variant == VARIANT_ENHANCED) "voice-enhanced " else "") + "recording from $day ${timeFmt.get()!!.format(Date(r.tsStart))}"
        val t0 = System.currentTimeMillis()
        try {
            val row = try {
                val (pcm, sr) = audio ?: store.fileFor(r).let { f ->
                    if (!f.exists()) throw FileNotFoundException("audio file no longer on the phone: ${r.file}")
                    Wav.read(f)
                }
                val t = engine().transcriber.transcribe(pcm, sr, LANGUAGES)
                val info = infoJson(t, sr)
                extra?.keys()?.forEach { k -> info.put(k, extra.get(k)) }
                DiagStore.TranscriptRow(
                    r.id, variant, day, r.tsStart, r.tsEnd, audioName, r.kind, r.category, r.durationMs,
                    if (t.hasText) "SPEECH" else "NO_SPEECH", if (t.hasText) t.language else null, t.text, info.toString(),
                    ENGINE_ID, System.currentTimeMillis()
                )
            } catch (e: EngineUnavailable) {
                throw e
            } catch (t: Throwable) {
                DiagStore.TranscriptRow(
                    r.id, variant, day, r.tsStart, r.tsEnd, audioName, r.kind, r.category, r.durationMs, "ERROR", null, null,
                    JSONObject().put("error", t.toString()).toString(), ENGINE_ID, System.currentTimeMillis()
                )
            }
            store.upsertTranscript(row)
            store.log(
                "TRANSCRIBED", "${variant.lowercase()} $audioName: ${row.status}${row.language?.let { " ($it)" } ?: ""}",
                JSONObject().put("variant", variant).put("status", row.status).put("language", row.language)
                    .put("ms", System.currentTimeMillis() - t0).put("audioMs", r.durationMs).put("day", day),
                r.interactionId, r.id
            )
            dirtyDays.add(day)
            doneThisRun++
        } finally {
            lastUse = System.currentTimeMillis()
            current = null
        }
    }

    private fun r3(v: Double) = Math.round(v * 1000) / 1000.0

    private fun infoJson(t: SpeechTranscriber.Transcript, sr: Int): JSONObject {
        val chunks = JSONArray()
        for (c in t.chunks) {
            val r = c.result
            chunks.put(JSONObject().put("startMs", c.startMs).put("endMs", c.endMs).put("text", r.text).put("language", r.language)
                .put("languageProbabilities", JSONObject(r.languageProbs.mapValues { r3(it.value) }))
                .put("topLanguage", r.topLanguage).put("topLanguageProbability", r3(r.topLanguageProb))
                .put("noSpeechProbability", r3(r.noSpeechProb)).put("avgLogProb", r3(r.avgLogProb))
                .put("compressionRatio", r3(r.compressionRatio)).put("stop", r.stop).put("tokens", r.tokens)
                .put("encoderMs", r.encoderMs).put("decoderMs", r.decoderMs))
        }
        return JSONObject().put("engine", ENGINE).put("engineId", ENGINE_ID).put("languagesAllowed", JSONArray(LANGUAGES))
            .put("sampleRate", sr).put("audioMs", t.audioMs).put("speechMs", t.speechMs)
            .put("maxSpeechProbability", r3(t.maxSpeechProb.toDouble())).put("maxLevelDbfs", Math.round(t.maxLevelDb * 10) / 10.0)
            .put("processingMs", t.processingMs)
            .put("languageProbabilities", JSONObject(t.languageProbs.mapValues { r3(it.value) }))
            .put("chunks", chunks).put("notes", JSONArray(notes(t)))
    }

    private fun pct(v: Double) = "${Math.round(v * 100)} %"
    private fun offset(ms: Long) = "+%d:%04.1f".format(ms / 60000, (ms % 60000) / 1000.0)

    /** Uncertainty / problems, in plain words (Whisper's own thresholds where it has them). */
    private fun notes(t: SpeechTranscriber.Transcript): List<String> {
        val n = ArrayList<String>()
        if (t.chunks.isEmpty()) {
            n.add(if (t.maxLevelDb < SpeechTranscriber.MIN_SPEECH_DBFS)
                "the audio is too quiet to contain speech (loudest moment %.0f dBFS); it was not sent to the recognizer".format(t.maxLevelDb)
            else "the voice detector found no speech (highest speech probability ${pct(t.maxSpeechProb.toDouble())}); the audio was not sent to the recognizer")
            return n
        }
        if (!t.hasText) n.add("the recognizer returned no words")
        for (c in t.chunks) {
            val r = c.result
            val at = if (t.chunks.size > 1) "${offset(c.startMs)}: " else ""
            if (r.text.isBlank()) continue
            if (r.avgLogProb < -1.0) n.add(at + "low confidence (average log-probability %.2f)".format(r.avgLogProb))
            if (r.noSpeechProb > 0.6) n.add(at + "the recognizer thinks this may not be speech (no-speech probability ${pct(r.noSpeechProb)})")
            if (r.stop == "REPETITION" || r.compressionRatio > 2.4) n.add(at + "repetitive output – possibly invented by the recognizer")
            if (r.stop == "MAX_TOKENS") n.add(at + "text cut off at the length limit")
            val p = r.languageProbs[r.language] ?: 1.0
            if (p < 0.75) n.add(at + "language uncertain (" + r.languageProbs.entries.joinToString(", ") { "${it.key} ${pct(it.value)}" } + ")")
            if (r.topLanguage !in LANGUAGES && r.topLanguageProb > 0.5)
                n.add(at + "sounded most like '${r.topLanguage}' (${pct(r.topLanguageProb)}); transcribed as ${langName(r.language)}")
        }
        return n
    }

    private fun langName(code: String?) = when (code) { "da" -> "Danish"; "en" -> "English"; null -> "–"; else -> code }

    // ------------------------------------------------------------------ daily files

    /** Writes the changed daily files and copies them to the sync folder (at most every 2 min while busy). */
    private fun flush(force: Boolean) {
        if (dirtyDays.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSync < SYNC_INTERVAL_MS) return
        for (day in dirtyDays.toList()) {
            val f = File(dir, fileName(day))
            val tmp = File(dir, ".${f.name}.tmp")
            tmp.writeText(render(day), Charsets.UTF_8)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            SyncFolder.publishTranscript(f)
        }
        dirtyDays.clear()
        lastSync = now
    }

    private fun kindLabel(t: DiagStore.TranscriptRow): String = when {
        t.kind == "MICTEST" -> when (t.category) {
            "RAW" -> "raw microphone test"
            "INTERRUPTED" -> "listening recording (interrupted)"
            "ALL_ZEROS" -> "listening recording (all zeros – mic silenced)"
            else -> "listening recording"
        }
        else -> t.kind.lowercase() + (t.category?.let { "/" + it.lowercase() } ?: "")
    }

    /** Human-readable daily transcription, rendered from the database (chronological by recording start). */
    fun render(day: String): String {
        val rows = app.store.transcriptsForDay(day)
        val raws = rows.filter { it.variant == VARIANT_RAW }
        val sb = StringBuilder()
        val date = runCatching { dayFmt.get()!!.parse(day) }.getOrNull()
        val weekday = date?.let { SimpleDateFormat("EEEE", Locale.ENGLISH).format(it) } ?: ""
        sb.append("Jarvis – transcription of saved recordings – $weekday $day\n")
        sb.append("Recognizer: $ENGINE · languages: Danish, English\n")
        sb.append("The text is exactly what the recognizer produced: not summarised, interpreted or corrected.\n")
        sb.append("Times are the phone's local time. Audio file names match the files in the sync folder and in diagnostic ZIP exports.\n")
        sb.append("Updated ${stampFmt.get()!!.format(Date())} · ${raws.size} recording(s): ${raws.count { it.status == "SPEECH" }} with recognised speech, " +
            "${raws.count { it.status == "NO_SPEECH" }} without, ${raws.count { it.status == "ERROR" }} with errors\n")
        sb.append("=".repeat(78)).append('\n')
        val byRec = LinkedHashMap<String, MutableList<DiagStore.TranscriptRow>>()
        for (t in rows) byRec.getOrPut(t.recordingId) { ArrayList() }.add(t)
        for ((_, list) in byRec) {
            val raw = list.firstOrNull { it.variant == VARIANT_RAW }
            val enh = list.firstOrNull { it.variant == VARIANT_ENHANCED }
            val b = raw ?: enh ?: continue
            val tf = timeFmt.get()!!
            sb.append('\n').append("[${tf.format(Date(b.tsStart))} – ${tf.format(Date(b.tsEnd))}]  ")
                .append(raw?.audioName ?: b.audioName.replace("_enhanced.wav", "_raw.wav"))
                .append("  (%.1f s, %s)\n".format(b.durationMs / 1000.0, kindLabel(b)))
            if (raw != null) appendResult(sb, raw, "") else sb.append("  Original audio: not transcribed yet\n")
            if (enh != null) {
                sb.append("  Voice-enhanced audio: ${enh.audioName}\n")
                appendResult(sb, enh, "  ")
            }
        }
        if (byRec.isEmpty()) sb.append("\n(no transcriptions yet)\n")
        return sb.toString()
    }

    private fun appendResult(sb: StringBuilder, t: DiagStore.TranscriptRow, indent: String) {
        val info = t.info?.let { runCatching { JSONObject(it) }.getOrNull() }
        val p = indent + "  "
        when (t.status) {
            "ERROR" -> {
                sb.append(p).append("Language:   –\n")
                sb.append(p).append("Recognized: [RECOGNITION ERROR] ").append(info?.optString("error") ?: "unknown error").append('\n')
                return
            }
            "NO_SPEECH" -> {
                sb.append(p).append("Language:   –\n")
                sb.append(p).append("Recognized: [NO SPEECH RECOGNIZED]\n")
            }
            else -> {
                val probs = info?.optJSONObject("languageProbabilities")
                val probTxt = probs?.keys()?.asSequence()?.joinToString(", ") { "$it ${pct(probs.optDouble(it))}" } ?: ""
                sb.append(p).append("Language:   ${langName(t.language)}").append(if (probTxt.isNotEmpty()) " ($probTxt)" else "").append('\n')
                val chunks = info?.optJSONArray("chunks")
                if (chunks != null && chunks.length() > 1) {
                    sb.append(p).append("Recognized:\n")
                    for (i in 0 until chunks.length()) {
                        val c = chunks.getJSONObject(i)
                        val text = c.optString("text").ifBlank { "[no words]" }
                        sb.append(p).append("  ").append(offset(c.optLong("startMs"))).append("  ").append(text).append('\n')
                    }
                } else sb.append(p).append("Recognized: ").append(t.text ?: "").append('\n')
            }
        }
        val notes = info?.optJSONArray("notes")
        if (notes != null && notes.length() > 0) {
            for (i in 0 until notes.length()) sb.append(p).append(if (i == 0) "Notes:      " else "            ").append(notes.getString(i)).append('\n')
        }
    }

    // ------------------------------------------------------------------ UI

    fun statusText(): String {
        val store = app.store
        val m = mode()
        val c = store.transcriptCounts(dayOf(System.currentTimeMillis()))
        val sb = StringBuilder(when (m) {
            "OFF" -> "Off: nothing is transcribed."
            "AUTO" -> "Automatic: each completed recording is transcribed in the background (offline)."
            else -> "Only when you export a ZIP (offline)."
        })
        sb.append("\nToday: ${c[0]} recording(s) transcribed – ${c[1]} with speech, ${c[2]} without")
        if (c[3] > 0) sb.append(", ${c[3]} error(s)")
        if (m == "AUTO") store.countUntranscribed().let { if (it > 0) sb.append("\nWaiting: $it recording(s)") }
        current?.let { sb.append("\nNow: $it") }
        engineProblem?.let { sb.append("\n⚠ The speech recognizer could not start: $it") }
        return sb.toString()
    }
}
