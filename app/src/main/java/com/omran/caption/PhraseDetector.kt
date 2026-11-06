package com.omran.caption

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/**
 * Splits the microphone stream (16-bit mono PCM) into spoken phrases.
 *
 * A phrase ends when the speaker pauses ([pauseMs]) or, during non-stop speech,
 * after [maxPhraseMs]. Pure silence is never emitted. Whole phrases are much
 * easier to transcribe and translate than fixed 1.2-second slices (which cut
 * words in half and showed only the last word on screen), and they need far
 * fewer requests, so Groq's per-minute limits are not hit.
 *
 * No Android APIs here, so the logic can be checked on a plain JVM.
 */
class PhraseDetector(
    private val sampleRate: Int,
    private val pauseMs: Int = 700,
    private val maxPhraseMs: Int = 5000,
    private val minPhraseMs: Int = 700,
    private val minSpeechMs: Int = 200,
    private val preRollMs: Int = 300,
) {
    private val bytesPerMs = sampleRate * 2 / 1000

    // Noise floor follows the quiet moments quickly, creeps up only slowly and
    // is capped low: in a very noisy room it then sends audio rather than
    // risk dropping a quiet speaker.
    private var noiseFloor = 0.002

    // Audio just before speech starts, so the first syllable is not cut off.
    private val preRoll = ArrayDeque<ByteArray>()
    private var preRollBytes = 0

    private val phrase = ByteArrayOutputStream()
    private var inPhrase = false
    private var phraseMs = 0
    private var speechMs = 0
    private var silenceMs = 0

    /** Milliseconds since speech was last heard (used to clear old captions). */
    var msSinceSpeech = 1_000_000
        private set

    /** Feeds one frame of PCM. Returns a finished phrase to send, or null. */
    fun feed(frame: ByteArray, len: Int): ByteArray? {
        if (len <= 0) return null
        val frameMs = len / bytesPerMs
        val level = rms(frame, len)
        noiseFloor += (level - noiseFloor) * (if (level < noiseFloor) 0.2 else 0.002)
        noiseFloor = noiseFloor.coerceIn(0.0005, 0.01)
        val speaking = level > maxOf(0.005, noiseFloor * 2.5)
        msSinceSpeech = if (speaking) 0 else msSinceSpeech + frameMs
        val chunk = frame.copyOf(len)

        if (!inPhrase) {
            preRoll.addLast(chunk)
            preRollBytes += len
            while (preRollBytes > preRollMs * bytesPerMs && preRoll.size > 1) {
                preRollBytes -= preRoll.removeFirst().size
            }
            if (!speaking) return null
            inPhrase = true
            phrase.reset()
            preRoll.forEach { phrase.write(it) }
            phraseMs = preRollBytes / bytesPerMs
            preRoll.clear()
            preRollBytes = 0
            speechMs = frameMs
            silenceMs = 0
            return null
        }

        phrase.write(chunk)
        phraseMs += frameMs
        if (speaking) {
            speechMs += frameMs
            silenceMs = 0
        } else {
            silenceMs += frameMs
        }
        val paused = silenceMs >= pauseMs && phraseMs >= minPhraseMs
        if (!paused && phraseMs < maxPhraseMs) return null

        val out = if (speechMs >= minSpeechMs) phrase.toByteArray() else null
        // Cut by the length limit while still talking: carry straight on with
        // the next phrase instead of waiting for speech to start again.
        inPhrase = !paused && silenceMs == 0
        phrase.reset()
        phraseMs = 0
        speechMs = 0
        silenceMs = 0
        return out
    }

    private fun rms(b: ByteArray, len: Int): Double {
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < len) {
            val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort().toDouble()
            sum += s * s
            n++
            i += 2
        }
        return if (n == 0) 0.0 else Math.sqrt(sum / n) / 32768.0
    }
}
