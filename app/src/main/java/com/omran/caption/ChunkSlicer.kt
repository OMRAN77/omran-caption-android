package com.omran.caption

/**
 * Cuts the microphone stream (16-bit mono PCM) into short slices of about
 * 1.2 s, sent one after another without waiting for replies, as in the first
 * version of the app, whose captions kept up with the speech.
 *
 * The only difference from a fixed 1.2 s cut: each slice ends at a quiet
 * moment between [minMs] and [maxMs] (usually the gap between two words), so
 * fewer words are cut in half. Nothing is lost or repeated: the audio after a
 * cut starts the next slice. A slice that is plain silence is marked [Slice.silent]
 * so it is not sent.
 *
 * No Android APIs here, so it can be checked on a plain JVM.
 */
class ChunkSlicer(
    private val sampleRate: Int,
    @Volatile private var minMs: Int = 1100,
    @Volatile private var maxMs: Int = 1500,
    @Volatile private var silence: Double = 0.002,      // about -54 dBFS: nothing audible
    @Volatile private var quietRatio: Double = 0.5,     // a frame 6 dB under the slice's typical level
) {
    class Slice(val pcm: ByteArray, val silent: Boolean)

    /** New values from the server; used from the next frame on. */
    fun configure(minMs: Int, maxMs: Int, silence: Double, quietRatio: Double) {
        this.minMs = minMs
        this.maxMs = maxOf(maxMs, minMs + 100)
        this.silence = silence
        this.quietRatio = quietRatio
    }

    private val bytesPerMs = sampleRate * 2 / 1000
    private val frames = ArrayList<ByteArray>()
    private val levels = ArrayList<Double>()
    private var totalBytes = 0

    /** Feeds one read of PCM. Returns a finished slice, or null. */
    fun feed(buf: ByteArray, len: Int): Slice? {
        if (len <= 0) return null
        val frame = buf.copyOf(len)
        frames.add(frame)
        levels.add(rms(frame))
        totalBytes += len
        val ms = totalBytes / bytesPerMs
        val minMs = minMs
        val maxMs = maxOf(maxMs, minMs + 100)
        if (ms < minMs) return null

        // Past the minimum: cut right after a quiet frame (a gap between words).
        val level = levels.last()
        if (level < silence || level <= median() * quietRatio) return cut(frames.size)

        if (ms < maxMs) return null
        // No gap yet: cut after the quietest frame that ends past the minimum.
        var best = frames.size
        var bestLevel = Double.MAX_VALUE
        var acc = 0
        for (i in frames.indices) {
            acc += frames[i].size
            if (acc / bytesPerMs < minMs) continue
            if (levels[i] < bestLevel) { bestLevel = levels[i]; best = i + 1 }
        }
        return cut(best)
    }

    /** Sends frames [0, k) and keeps the rest for the next slice. */
    private fun cut(k: Int): Slice {
        var size = 0
        var loud = 0.0
        for (i in 0 until k) { size += frames[i].size; loud = maxOf(loud, levels[i]) }
        val out = ByteArray(size)
        var o = 0
        for (i in 0 until k) { System.arraycopy(frames[i], 0, out, o, frames[i].size); o += frames[i].size }
        frames.subList(0, k).clear()
        levels.subList(0, k).clear()
        totalBytes -= size
        return Slice(out, loud < silence)
    }

    private fun median(): Double {
        val s = levels.toDoubleArray()
        s.sort()
        return s[s.size / 2]
    }

    private fun rms(b: ByteArray): Double {
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < b.size) {
            val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort().toDouble()
            sum += v * v
            n++
            i += 2
        }
        return if (n == 0) 0.0 else Math.sqrt(sum / n) / 32768.0
    }
}
