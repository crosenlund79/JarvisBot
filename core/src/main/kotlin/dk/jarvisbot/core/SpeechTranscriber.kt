package dk.jarvisbot.core

import kotlin.math.max
import kotlin.math.min

/**
 * Transcribes one saved recording: resample to 16 kHz, find speech with Silero VAD, cut it into chunks of at most
 * [MAX_CHUNK_S] seconds (at quiet points), and run Whisper on each chunk. Audio without detected speech is not sent to
 * Whisper at all (Whisper tends to invent text for pure noise), and is reported as "no speech".
 */
class SpeechTranscriber(private val whisper: () -> WhisperModel, private val vad: SileroVad, private val vadThreshold: Float = 0.5f) {
    /** [whisper] is only requested when speech was found, so it can be loaded lazily. */
    constructor(model: WhisperModel, vad: SileroVad, vadThreshold: Float = 0.5f) : this({ model }, vad, vadThreshold)

    companion object {
        const val MAX_CHUNK_S = 20
        private const val FRAME = 640 // 40 ms at 16 kHz (as used by openWakeWord)
        private const val PAD_FRAMES = 8 // 320 ms kept before / after detected speech
        private const val MERGE_GAP_FRAMES = 25 // pauses up to 1 s stay in the same segment
        private const val MAX_CHUNK_FRAMES = MAX_CHUNK_S * 1000 / 40
        /**
         * Frames quieter than this never count as speech. Silero VAD ignores level and can report "speech" for
         * near-silent digital noise; real speech on a phone microphone is around -50..-30 dBFS.
         */
        const val MIN_SPEECH_DBFS = -70.0
    }

    class Chunk(val startMs: Long, val endMs: Long, val result: WhisperModel.Result)

    class Transcript(
        val audioMs: Long,
        val speechMs: Long,
        /** Highest speech probability among frames above [MIN_SPEECH_DBFS]. */
        val maxSpeechProb: Float,
        val chunks: List<Chunk>,
        val processingMs: Long,
        /** Level (RMS, dBFS) of the loudest 40 ms frame. */
        val maxLevelDb: Double = 0.0,
    ) {
        val text: String get() = chunks.map { it.result.text }.filter { it.isNotBlank() }.joinToString(" ")
        val hasText: Boolean get() = chunks.any { it.result.text.isNotBlank() }

        /** Overall language: weighted by chunk duration (chunks with text only). */
        val language: String?
            get() = languageProbs.maxByOrNull { it.value }?.key

        val languageProbs: Map<String, Double>
            get() {
                val m = LinkedHashMap<String, Double>()
                var total = 0.0
                for (c in chunks) {
                    if (c.result.text.isBlank()) continue
                    val w = (c.endMs - c.startMs).toDouble()
                    total += w
                    c.result.languageProbs.forEach { (k, v) -> m[k] = (m[k] ?: 0.0) + v * w }
                }
                return if (total > 0) m.mapValues { it.value / total } else emptyMap()
            }
    }

    fun transcribe(pcm: ShortArray, sampleRate: Int, languages: List<String>): Transcript {
        val t0 = System.currentTimeMillis()
        val audio = Resampler.to16k(pcm, sampleRate)
        val audioMs = audio.size / 16L
        val n = audio.size / FRAME
        // Speech probability per 40 ms frame.
        val probs = FloatArray(n)
        var maxLevel = -120.0
        vad.reset()
        val buf = ShortArray(FRAME)
        for (f in 0 until n) {
            var e = 0.0
            for (j in 0 until FRAME) {
                val v = audio[f * FRAME + j]
                e += v * v
                buf[j] = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            val db = 10 * kotlin.math.log10(e / FRAME + 1e-12)
            if (db > maxLevel) maxLevel = db
            val p = vad.process(buf, 0, FRAME) // always run, so the detector's state follows the audio
            probs[f] = if (db >= MIN_SPEECH_DBFS) p else 0f
        }
        val maxProb = probs.maxOrNull() ?: 0f
        val speechFrames = probs.count { it >= vadThreshold }
        if (speechFrames == 0) return Transcript(audioMs, 0, maxProb, emptyList(), System.currentTimeMillis() - t0, maxLevel)

        // Segments of speech, padded and merged across short pauses.
        val segs = ArrayList<IntArray>()
        var f = 0
        while (f < n) {
            if (probs[f] < vadThreshold) { f++; continue }
            var e = f
            while (e < n && probs[e] >= vadThreshold) e++
            val s = max(0, f - PAD_FRAMES)
            val end = min(n, e + PAD_FRAMES)
            val last = segs.lastOrNull()
            if (last != null && s - last[1] <= MERGE_GAP_FRAMES) last[1] = max(last[1], end) else segs.add(intArrayOf(s, end))
            f = e
        }
        // Chunks of at most MAX_CHUNK_S seconds; long speech is split at the quietest point (lowest speech probability).
        val chunks = ArrayList<IntArray>()
        for (seg in segs) {
            var s = seg[0]
            while (seg[1] - s > MAX_CHUNK_FRAMES) {
                var cut = s + MAX_CHUNK_FRAMES
                var best = Float.MAX_VALUE
                for (i in s + MAX_CHUNK_FRAMES / 2 until s + MAX_CHUNK_FRAMES) if (probs[i] < best) { best = probs[i]; cut = i }
                chunks.add(intArrayOf(s, cut))
                s = cut
            }
            val prev = chunks.lastOrNull()
            if (prev != null && seg[1] - prev[0] <= MAX_CHUNK_FRAMES && prev[1] <= s) prev[1] = seg[1] else chunks.add(intArrayOf(s, seg[1]))
        }
        val out = ArrayList<Chunk>()
        for (c in chunks) {
            val from = c[0] * FRAME
            val to = min(audio.size, c[1] * FRAME)
            val r = whisper().transcribe(audio.copyOfRange(from, to), languages)
            out.add(Chunk(from / 16L, to / 16L, r))
        }
        return Transcript(audioMs, speechFrames * 40L, maxProb, out, System.currentTimeMillis() - t0, maxLevel)
    }
}
