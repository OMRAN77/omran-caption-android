package com.omran.caption

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Finds speech in the microphone stream, so a slice holding only silence,
 * room noise or music is not sent: Whisper invents text for those ("شكراً
 * لك" on the home screen, whole made-up lines, words for instrumental music).
 *
 * Fed the same 16 kHz PCM as ChunkSlicer, in the same order; [speechMs] tells
 * how much speech lies between two stream positions (in samples).
 * No Android APIs here, so it can be checked on a plain JVM.
 */
abstract class SpeechDetector : AutoCloseable {
    // Recent decisions (about the last 30 s): where each judged piece ends, its
    // length and whether it was speech.
    private val ends = LongArray(RING)
    private val sizes = IntArray(RING)
    private val speech = BooleanArray(RING)
    private var head = 0
    private var count = 0

    /** Samples fed so far. */
    protected var position = 0L

    /** Feeds PCM16 little-endian mono at 16 kHz. */
    abstract fun feed(pcm: ByteArray, len: Int)

    protected fun record(size: Int, isSpeech: Boolean) {
        ends[head] = position
        sizes[head] = size
        speech[head] = isSpeech
        head = (head + 1) % RING
        if (count < RING) count++
    }

    /** Milliseconds of speech between stream positions [from, to) (samples). */
    fun speechMs(from: Long, to: Long): Int {
        var samples = 0L
        for (k in 0 until count) {
            val i = (head - 1 - k + RING) % RING
            val end = ends[i]
            if (end <= from) break // newest first: everything older is before the range
            val start = end - sizes[i]
            if (!speech[i] || start >= to) continue
            samples += minOf(end, to) - maxOf(start, from)
        }
        return (samples * 1000 / SAMPLE_RATE).toInt()
    }

    override fun close() {}

    companion object {
        const val SAMPLE_RATE = 16000
        private const val RING = 1024

        fun level(pcm: ByteArray, off: Int, len: Int): Double {
            var sum = 0.0
            var n = 0
            var i = off
            while (i + 1 < off + len) {
                val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble()
                sum += v * v
                n++
                i += 2
            }
            return if (n == 0) 0.0 else Math.sqrt(sum / n) / 32768.0
        }
    }
}

/**
 * Silero VAD (silero_vad.onnx, MIT licence): a small neural network that tells
 * speech from silence, noise and music, run on the phone every 32 ms. On real
 * recordings it kept every slice of speech, also with music 6 dB under it, and
 * let through about 1% of music-only slices and no silence or noise.
 */
class SileroVad private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val threshold: () -> Float,
) : SpeechDetector() {
    // [64 samples of context from the previous window | 512 new samples], as
    // the reference implementation (silero_vad OnnxWrapper) feeds the model.
    private val x = FloatArray(CONTEXT + WINDOW)
    private val state = FloatArray(2 * 128)
    private var filled = 0
    private val sr = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong())), longArrayOf())

    /** Probability of speech in the last window (for tests). */
    var lastProb = 0f
        private set

    override fun feed(pcm: ByteArray, len: Int) {
        var i = 0
        while (i + 1 < len) {
            x[CONTEXT + filled] = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort() / 32768f
            filled++
            position++
            i += 2
            if (filled == WINDOW) {
                // If the model ever fails, count the window as speech: the slice
                // is then sent as before rather than silently dropped.
                lastProb = try { infer() } catch (e: Exception) { 1f }
                record(WINDOW, lastProb >= threshold())
                System.arraycopy(x, WINDOW, x, 0, CONTEXT)
                filled = 0
            }
        }
    }

    private fun infer(): Float {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, (CONTEXT + WINDOW).toLong())).use { input ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { st ->
                session.run(mapOf("input" to input, "state" to st, "sr" to sr)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val prob = (out.get(0).value as Array<FloatArray>)[0][0]
                    (out.get(1) as OnnxTensor).floatBuffer.get(state)
                    return prob
                }
            }
        }
    }

    override fun close() {
        try { sr.close() } catch (e: Exception) {}
        try { session.close() } catch (e: Exception) {}
    }

    companion object {
        private const val WINDOW = 512
        private const val CONTEXT = 64

        /** The detector, or null if the model or the ONNX runtime cannot load on this phone. */
        fun create(model: ByteArray, threshold: () -> Float): SileroVad? = try {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            SileroVad(env, env.createSession(model, opts), threshold)
        } catch (t: Throwable) { // also UnsatisfiedLinkError from the native library
            null
        }
    }
}

/**
 * Fallback when Silero cannot run: speech = clearly louder than the room's
 * noise floor (as the earlier detector judged it). Stops silence and steady
 * noise; music still passes.
 */
class EnergyGate : SpeechDetector() {
    private var floor = 0.002

    override fun feed(pcm: ByteArray, len: Int) {
        val n = len / 2
        if (n == 0) return
        val level = level(pcm, 0, len)
        // Start from the room as it sounds now, so steady noise is not taken
        // for speech while the floor would still be creeping up to it.
        if (position == 0L) floor = level
        floor += (level - floor) * (if (level < floor) 0.2 else 0.002)
        floor = floor.coerceIn(0.0005, 0.01)
        position += n
        record(n, level > maxOf(0.005, floor * 2.5))
    }
}
