package dk.jarvisbot.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Circular buffer of 16-bit PCM with absolute sample positions. Single-threaded use. */
class PcmRingBuffer(val capacity: Int) {
    private val data = ShortArray(capacity)
    private var pos = 0
    var totalWritten = 0L
        private set

    fun write(samples: ShortArray, offset: Int = 0, length: Int = samples.size) {
        for (i in 0 until length) {
            data[pos] = samples[offset + i]
            pos++
            if (pos == capacity) pos = 0
        }
        totalWritten += length
    }

    /** Copies up to the last [n] samples (fewer if not yet available). */
    fun copyLast(n: Int): ShortArray {
        val count = minOf(n.toLong(), totalWritten, capacity.toLong()).toInt()
        val out = ShortArray(count)
        var src = pos - count
        if (src < 0) src += capacity
        for (i in 0 until count) {
            out[i] = data[src]
            src++
            if (src == capacity) src = 0
        }
        return out
    }
}

/** Growable short array. */
class ShortArrayBuilder(initial: Int = 16000) {
    private var buf = ShortArray(initial)
    var size = 0
        private set

    fun add(samples: ShortArray, offset: Int = 0, length: Int = samples.size) {
        ensure(size + length)
        System.arraycopy(samples, offset, buf, size, length)
        size += length
    }

    private fun ensure(n: Int) {
        if (n <= buf.size) return
        var c = buf.size * 2
        while (c < n) c *= 2
        buf = buf.copyOf(c)
    }

    fun toArray(): ShortArray = buf.copyOf(size)
}

object AudioMath {
    /** RMS level in dBFS (full scale = 32768); returns -120 for digital silence. */
    fun rmsDbfs(samples: ShortArray, offset: Int = 0, length: Int = samples.size): Double {
        if (length == 0) return -120.0
        var sum = 0.0
        for (i in offset until offset + length) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        val rms = sqrt(sum / length)
        return if (rms <= 0.0) -120.0 else max(-120.0, 20.0 * log10(rms / 32768.0))
    }

    fun peakDbfs(peakAbs: Int): Double =
        if (peakAbs <= 0) -120.0 else max(-120.0, 20.0 * log10(peakAbs / 32768.0))
}

object Wav {
    const val HEADER_BYTES = 44

    fun header(dataBytes: Long, sampleRate: Int, channels: Int = 1, bits: Int = 16): ByteArray {
        val bb = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = sampleRate * channels * bits / 8
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt((36 + dataBytes).coerceAtMost(0xFFFFFFFFL).toInt())
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1) // PCM
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort((channels * bits / 8).toShort())
        bb.putShort(bits.toShort())
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataBytes.coerceAtMost(0xFFFFFFFFL).toInt())
        return bb.array()
    }

    fun write(file: File, samples: ShortArray, sampleRate: Int) {
        file.parentFile?.mkdirs()
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) bytes.putShort(s)
        file.outputStream().use { os ->
            os.write(header(samples.size * 2L, sampleRate))
            os.write(bytes.array())
            os.fd.sync()
        }
    }

    /** Reads a canonical 16-bit mono PCM WAV (as written by [write]); returns samples and sample rate. */
    fun read(file: File): Pair<ShortArray, Int> {
        RandomAccessFile(file, "r").use { raf ->
            val all = ByteArray(raf.length().toInt())
            raf.readFully(all)
            return read(all)
        }
    }

    /** Same as [read] for a whole WAV file already in memory. */
    fun read(all: ByteArray): Pair<ShortArray, Int> {
        run {
            val bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
            require(String(all, 0, 4, Charsets.US_ASCII) == "RIFF") { "not a RIFF file" }
            var p = 12
            var sampleRate = 16000
            var dataOff = -1
            var dataLen = 0
            while (p + 8 <= all.size) {
                val id = String(all, p, 4, Charsets.US_ASCII)
                val len = bb.getInt(p + 4)
                if (id == "fmt ") sampleRate = bb.getInt(p + 12)
                if (id == "data") {
                    dataOff = p + 8; dataLen = minOf(len, all.size - dataOff); break
                }
                p += 8 + len
            }
            require(dataOff >= 0) { "no data chunk" }
            val out = ShortArray(dataLen / 2)
            for (i in out.indices) out[i] = bb.getShort(dataOff + i * 2)
            return out to sampleRate
        }
    }
}

/** Removes a leading wake phrase ("hey jarvis", "hej jarvis", "jarvis", common mis-hearings) from a transcript. */
object WakePhrase {
    private val greeting = setOf("hey", "hej", "hi", "hay", "he", "okay", "ok", "a", "hei", "haj", "hallo")
    private val jarvisLike = setOf(
        "jarvis", "jarvis's", "jervis", "javis", "jarvi", "jarvas", "jarves", "charvis", "travis",
        "davis", "garvis", "harvis", "service", "jarviss", "jarwis", "yarvis", "jalvis", "javas",
    )

    fun strip(text: String?): String? {
        if (text == null) return null
        val words = text.trim().split(Regex("\\s+"))
        fun norm(w: String) = w.lowercase().trim { !it.isLetterOrDigit() && it != '\'' }
        val limit = minOf(3, words.size)
        for (i in 0 until limit) {
            val w = norm(words[i])
            if (w in jarvisLike) {
                val before = words.subList(0, i).all { norm(it) in greeting }
                if (before) {
                    return words.drop(i + 1).joinToString(" ").trim().trimStart(',', '.', '!', '?', ' ')
                }
            }
        }
        return text.trim()
    }
}
