package com.omran.caption

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Splits the microphone stream (16-bit mono PCM) into spoken phrases.
 *
 * The app listens to the phone speaker, usually a short drama with music under
 * the voices. Music never goes quiet, so "wait for silence" alone never ended
 * a phrase: every phrase ran to the length limit, was cut mid-word, and the
 * caption came seconds after the words. Now a phrase ends at whichever is first:
 *  - a pause of [gapMs]: silence, or the voices dropping well under their own
 *    level of the last few seconds while the music carries on underneath;
 *  - [maxPhraseMs] of non-stop talk.
 * Either way the cut is made at the quietest moment (late in the pause, or in
 * the last [cutSearchMs] of non-stop talk: normally between two words), and
 * the audio after the cut starts the next phrase.
 *
 * Everything louder than the silence threshold is sent, music included (the
 * server decides what is speech); the rest of a phrase cut for length and a
 * one-syllable reply right after a pause are never dropped. Soft syllables up
 * to 0.7 s after speech (a fading last word, even under music) stay with their
 * line: a pause cut is never placed before them. Only audio under the silence
 * threshold with nothing louder after it is left out. When no voices can be
 * told apart from the background (steady music) phrases are as long as before
 * ([unclearMaxMs]); the same applies once the server has found no words in two
 * phrases in a row ([serverResult]), so a music-only stretch is not chopped
 * into short sends. Sends are kept to about [maxPerMinute] a minute (Groq's
 * free per-minute limits) by joining lines that come fast.
 *
 * No Android APIs here, so the logic can be checked on a plain JVM.
 */
class PhraseDetector(
    private val sampleRate: Int,
    private val gapMs: Int = 400,
    private val maxPhraseMs: Int = 3500,
    private val cutSearchMs: Int = 1000,
    private val minPhraseMs: Int = 1000,
    private val minSpeechMs: Int = 100,
    private val preRollMs: Int = 500,
    private val maxPerMinute: Int = 24,
    /** A pause this long is sent even when the per-minute budget is used up (at most one send over it). */
    private val holdMs: Int = 900,
    /** Longest phrase while no voices stand out from the background. */
    private val unclearMaxMs: Int = 5000,
) {
    private val bytesPerMs = sampleRate * 2 / 1000
    private val blockBytes = bytesPerMs * BLOCK_MS

    // ---- silence (unchanged): the noise floor follows quiet moments fast,
    // creeps up slowly and is capped low, so a quiet speaker in a noisy room is
    // still sent rather than dropped.
    private var noiseFloor = 0.002
    private var sinceSoundMs = 1_000_000   // since the last frame louder than silence

    // ---- soft sounds (a fading or whispered syllable) under the silence
    // threshold. Only judged when the background is steady (room noise): a
    // fluctuating one (quiet music) has no "soft" to tell apart.
    private var floor = 0.002              // like noiseFloor, but not capped
    private var bgPrevDb = Double.NaN
    private val bgJitter = DoubleArray(JITTER_N)
    private val bgJitterSorted = DoubleArray(JITTER_N)
    private var bgJitterPos = 0
    private var bgJitterCount = 0
    private var sinceSoftMs = 1_000_000    // since the last frame louder than silence or soft
    private var steady = false

    // ---- loudness relative to the last 3 s: the loud end (voices) and the
    // quiet end (music bed / noise between the words).
    private val dbHist = DoubleArray(HIST_FRAMES)
    private val dbSorted = DoubleArray(HIST_FRAMES)
    private var histPos = 0
    private var histCount = 0
    private var gapRunMs = 0      // pause frames in the current pause
    private var gapSpanMs = 0     // length of the current pause, short knocks included
    private var blipMs = 0
    // Quiet end of the last 3 s (music bed / noise between the words). Unlike
    // `floor` it does not creep up while someone talks, so a soft syllable just
    // after a line is still judged against the real background.
    private var bedLevel = 1.0

    // ---- per-minute budget (token bucket refilled by audio time)
    private var tokens = BURST

    // Audio just before speech starts, so the first syllable is not cut off.
    private val preRoll = ArrayDeque<ByteArray>()
    private var preRollBytes = 0

    private val phrase = ByteArrayOutputStream()
    private var inPhrase = false
    // After a pause cut the rest of the pause is kept as the start of the next
    // phrase; if nothing is heard for a while it becomes plain pre-roll again.
    private var afterCut = false
    private var afterCutMs = 0
    private var phraseBytes = 0
    private var speechBytes = 0   // louder than silence (music included)
    private var voiceBytes = 0    // standing out from the background (speech-like)
    private var tailBytes = 0     // soft sound up to 0.7 s after a loud one
    // The phrase carries on from the one before (the rest of a phrase cut for
    // length, or a reply right after a pause cut): never dropped, however
    // little sound is in it (a one-syllable "嗯" or the end of a word).
    private var continued = false
    // 10 ms blocks of the current phrase.
    private val blkEnergy = ArrayList<Double>()
    private val blkLen = ArrayList<Int>()
    private val blkFlags = ArrayList<Int>()

    // The server's verdict on recent phrases. After 2 replies in a row with no
    // words, loud music no longer counts as voices: short pauses do not end a
    // phrase and the old 5 s limit applies, until a reply has words again.
    // Nothing is dropped in this mode; phrases are only longer.
    @Volatile private var emptyReplies = 0

    /** Report a successful server reply: [heardWords] = it recognised speech in the phrase. */
    fun serverResult(heardWords: Boolean) {
        emptyReplies = if (heardWords) 0 else min(emptyReplies + 1, 100)
    }

    private fun musicOnly() = emptyReplies >= 2

    /** Milliseconds since speech was last heard (used to clear old captions). */
    var msSinceSpeech = 1_000_000
        private set

    /** Feeds one frame of PCM. Returns a finished phrase to send, or null. */
    fun feed(frame: ByteArray, len: Int): ByteArray? {
        if (len <= 0) return null
        val frameMs = len / bytesPerMs
        val level = rms(frame, 0, len)
        noiseFloor += (level - noiseFloor) * (if (level < noiseFloor) 0.2 else 0.002)
        noiseFloor = noiseFloor.coerceIn(0.0005, 0.01)
        val speaking = level > maxOf(0.005, noiseFloor * 2.5)
        floor += (level - floor) * (if (level < floor) 0.2 else 0.002)
        floor = max(floor, 0.0005)
        steady = steadyBackground(level, speaking)
        val rel = relativeLevel(level)
        val soft = !speaking && steady && level > max(floor * SOFT_FACTOR, MIN_SOFT)
        sinceSoundMs = if (speaking) 0 else sinceSoundMs + frameMs
        sinceSoftMs = if (speaking || soft) 0 else sinceSoftMs + frameMs
        val dip = rel == DIP
        var flags = 0
        if (speaking) flags = flags or F_SPEECH
        if (speaking && rel == VOICE) flags = flags or F_VOICE
        // A soft sound up to 0.7 s after speech (the old detector sent all of
        // it) is a tail of the line, whatever the background: under music too.
        val tailSoft = !speaking && level > max(min(floor, bedLevel) * SOFT_FACTOR, MIN_SOFT)
        if ((soft || tailSoft) && sinceSoundMs <= OLD_PAUSE_MS) flags = flags or F_TAIL
        val gapFrame = !speaking || dip
        msSinceSpeech = if (speaking && !dip) 0 else msSinceSpeech + frameMs
        if (gapFrame) {
            gapRunMs += frameMs
            gapSpanMs += frameMs
            blipMs = 0
        } else {
            // One short knock (a drum hit, a click) inside a pause does not end it.
            blipMs += frameMs
            if (blipMs > BLIP_MS) { gapRunMs = 0; gapSpanMs = 0 } else if (gapRunMs > 0) gapSpanMs += frameMs
        }
        if (maxPerMinute > 0) tokens = min(BURST, tokens + frameMs * maxPerMinute / 60000.0)
        val chunk = frame.copyOf(len)

        if (!inPhrase) {
            preRoll.addLast(chunk)
            preRollBytes += len
            while (preRollBytes > preRollMs * bytesPerMs && preRoll.size > 1) {
                preRollBytes -= preRoll.removeFirst().size
            }
            if (!speaking) return null
            inPhrase = true
            afterCut = false
            clearPhrase()
            while (preRoll.size > 1) append(preRoll.removeFirst(), 0)
            append(preRoll.removeFirst(), flags)
            preRollBytes = 0
            return null
        }

        append(chunk, flags)
        if (afterCut) {
            afterCutMs += frameMs
            if (speaking) {
                afterCut = false
                continued = true
            } else if (afterCutMs >= AFTER_CUT_KEEP_MS) {
                return endAfterCut()
            }
        }
        val phraseMs = phraseBytes / bytesPerMs

        // A pause after speech: send up to the quietest moment late in the pause.
        // A short pause only counts after something that stood out from the
        // background (else music alone would be chopped into short sends);
        // otherwise only the old 0.7 s of silence does. In a quiet room soft
        // sounds (a fading or whispered syllable) hold the cut back, up to 0.7 s.
        val pauseAfterSound = (voiceBytes >= MIN_VOICE_MS * bytesPerMs && !musicOnly()) ||
            (!speaking && sinceSoundMs >= OLD_PAUSE_MS)
        val softDone = speaking || sinceSoftMs >= SOFT_CLEAR_MS || sinceSoundMs >= OLD_PAUSE_MS
        if (!afterCut && gapFrame && gapRunMs >= gapMs && pauseAfterSound && softDone &&
            (maxPerMinute <= 0 || tokens >= 1.0 || (gapRunMs >= holdMs && tokens >= 0.0))
        ) {
            // Never inside or just before a soft tail (a fading last syllable):
            // it goes with its line, not into the rest that may be dropped.
            var tailEndMs = -1
            var acc = 0
            for (j in blkLen.indices) {
                acc += blkLen[j]
                if (blkFlags[j] and F_TAIL != 0) tailEndMs = acc / bytesPerMs
            }
            val from = maxOf(minPhraseMs, phraseMs - gapSpanMs + PAUSE_TAIL_MS, phraseMs - PAUSE_SEARCH_MS,
                if (tailEndMs >= 0) tailEndMs + TAIL_KEEP_MS else 0)
            // not in the last 100 ms: a soft first sound of the next word may be there
            val k = quietest(from, phraseMs - PAUSE_GUARD_MS, PAUSE_WINDOW_MS, true)
            if (k > 0 && bytesBefore(k, soundFlags()) >= minSound()) {
                val out = split(k)
                continued = false
                afterCut = true
                afterCutMs = phraseBytes / bytesPerMs
                return out
            }
        }
        // Nothing but silence and a click or two: as before, not sent.
        if (!afterCut && !(continued && (speechBytes > 0 || tailBytes > 0)) &&
            sinceSoundMs >= AFTER_CUT_KEEP_MS && speechBytes < minSpeechMs * bytesPerMs
        ) {
            toPreRoll()
            return null
        }
        // The short limit applies once voices were heard early enough that the
        // part sent will hold some of them; otherwise (music, or voices drowned
        // in it, or the server found only music lately) the old limit applies,
        // so music alone is not sent more often.
        if (phraseMs < maxPhraseMs) return null
        if (phraseMs < unclearMaxMs && (musicOnly() ||
            bytesBefore(blockAt(phraseMs - cutSearchMs), F_VOICE) < MIN_VOICE_MS * bytesPerMs)
        ) return null

        // Non-stop sound: cut at the quietest moment near the end, keep the rest.
        // In a quiet room silence is silence: take the latest gap, after any soft
        // syllable. Under music take the quietest (a gap, not a stop in a word).
        val k = quietest(phraseMs - cutSearchMs, phraseMs, TALK_WINDOW_MS, steady)
        val out = when {
            k > 0 && bytesBefore(k, soundFlags()) >= minSound() -> split(k)
            speechBytes >= minSound() -> split(blkLen.size)
            else -> { toPreRoll(); return null }
        }
        continued = true
        return out
    }

    /** Index of the block that starts at or after [ms] into the phrase. */
    private fun blockAt(ms: Int): Int {
        var acc = 0
        for (j in blkLen.indices) {
            if (acc >= ms * bytesPerMs) return j
            acc += blkLen[j]
        }
        return blkLen.size
    }

    private fun minSound() = if (continued) 1 else minSpeechMs * bytesPerMs

    // What counts as sound worth sending: for a continued phrase, soft syllables too.
    private fun soundFlags() = if (continued) F_SPEECH or F_TAIL else F_SPEECH

    /**
     * Nothing loud followed a pause cut. Sound left in it is still sent: soft
     * sounds the old detector sent (up to 0.7 s after speech), and anything over
     * the silence threshold (a soft word the cut was decided on); plain silence
     * is not.
     */
    private fun endAfterCut(): ByteArray? {
        if (speechBytes == 0 && tailBytes < TAIL_MIN_MS * bytesPerMs) {
            toPreRoll()
            return null
        }
        var last = 0
        for (j in blkFlags.indices) if (blkFlags[j] and (F_TAIL or F_SPEECH) != 0) last = j + 1
        val out = split(min(blkLen.size, last + TAIL_KEEP_MS / BLOCK_MS))
        toPreRoll()
        return out
    }

    /**
     * True when the background (frames within 6 dB of the floor) stays level
     * from frame to frame, like room noise; music keeps changing.
     */
    private fun steadyBackground(level: Double, speaking: Boolean): Boolean {
        val db = 20 * log10(max(level, 1e-5))
        if (speaking || level > floor * 2) {
            bgPrevDb = Double.NaN
        } else {
            if (!bgPrevDb.isNaN()) {
                bgJitter[bgJitterPos] = Math.abs(db - bgPrevDb)
                bgJitterPos = (bgJitterPos + 1) % JITTER_N
                if (bgJitterCount < JITTER_N) bgJitterCount++
            }
            bgPrevDb = db
        }
        if (bgJitterCount < JITTER_N / 4) return false
        for (i in 0 until bgJitterCount) bgJitterSorted[i] = bgJitter[i]
        java.util.Arrays.sort(bgJitterSorted, 0, bgJitterCount)
        return bgJitterSorted[bgJitterCount / 2] < STEADY_DB
    }

    private fun bytesBefore(k: Int, flag: Int): Int {
        var b = 0
        for (j in 0 until k) if (blkFlags[j] and flag != 0) b += blkLen[j]
        return b
    }

    /**
     * DIP when this frame is well under the loud end of the last 3 s (at the
     * quiet end and at least 6 dB under the loud end), VOICE when it is not,
     * UNCLEAR when the recent audio has no loud/quiet contrast to judge by
     * (steady music, or voices drowned in it).
     */
    private fun relativeLevel(level: Double): Int {
        val db = 20 * log10(max(level, 1e-5))
        dbHist[histPos] = db
        histPos = (histPos + 1) % HIST_FRAMES
        if (histCount < HIST_FRAMES) histCount++
        if (histCount < MIN_HIST_FRAMES) return UNCLEAR
        for (i in 0 until histCount) dbSorted[i] = dbHist[i]
        java.util.Arrays.sort(dbSorted, 0, histCount)
        val lo = dbSorted[(histCount - 1) * 10 / 100]
        val hi = dbSorted[(histCount - 1) * 90 / 100]
        bedLevel = Math.pow(10.0, lo / 20)
        if (hi - lo < CONTRAST_DB) return UNCLEAR
        return if (db < (hi + lo) / 2 && db < hi - DROP_DB) DIP else VOICE
    }

    private fun append(chunk: ByteArray, flags: Int) {
        phrase.write(chunk)
        phraseBytes += chunk.size
        if (flags and F_SPEECH != 0) speechBytes += chunk.size
        if (flags and F_VOICE != 0) voiceBytes += chunk.size
        if (flags and F_TAIL != 0) tailBytes += chunk.size
        var o = 0
        while (o < chunk.size) {
            val n = min(blockBytes, chunk.size - o)
            val r = rms(chunk, o, n)
            blkEnergy.add(r * r)
            blkLen.add(n)
            blkFlags.add(flags)
            o += n
        }
    }

    private fun clearPhrase() {
        phrase.reset()
        phraseBytes = 0
        speechBytes = 0
        voiceBytes = 0
        tailBytes = 0
        continued = false
        blkEnergy.clear()
        blkLen.clear()
        blkFlags.clear()
    }

    /**
     * Block index (cut before it) of the quietest [windowMs] stretch whose
     * centre lies between [fromMs] and [toMs] of the phrase, or -1. With
     * [preferLate] (in a pause) the latest of the stretches about as quiet
     * (within 1 dB) wins, so a soft last syllable stays with its phrase.
     * Without it (non-stop talk) the quietest wins: a gap between words is
     * longer and quieter than a stop inside a word.
     */
    private fun quietest(fromMs: Int, toMs: Int, windowMs: Int, preferLate: Boolean): Int {
        val n = blkLen.size
        val half = windowMs / BLOCK_MS / 2
        val score = DoubleArray(n + 1) { Double.MAX_VALUE }
        var bestScore = Double.MAX_VALUE
        var ms = 0
        for (k in 1..n) {
            ms += blkLen[k - 1] / bytesPerMs
            if (ms < fromMs) continue
            if (ms > toMs) break
            var s = 0.0
            var c = 0
            for (j in max(0, k - half) until min(n, k + half)) { s += blkEnergy[j]; c++ }
            score[k] = s / c
            bestScore = min(bestScore, score[k])
        }
        if (bestScore == Double.MAX_VALUE) return -1
        val limit = if (preferLate) bestScore * SAME_QUIET else bestScore
        for (k in n downTo 1) if (score[k] <= limit) return k
        return -1
    }

    /** Returns blocks [0, k) to send and keeps the rest as the start of the next phrase. */
    private fun split(k: Int): ByteArray {
        var cutBytes = 0
        for (j in 0 until k) {
            cutBytes += blkLen[j]
            val f = blkFlags[j]
            if (f and F_SPEECH != 0) speechBytes -= blkLen[j]
            if (f and F_VOICE != 0) voiceBytes -= blkLen[j]
            if (f and F_TAIL != 0) tailBytes -= blkLen[j]
        }
        val all = phrase.toByteArray()
        val out = all.copyOfRange(0, cutBytes)
        if (maxPerMinute > 0) tokens = max(-1.0, tokens - 1.0)
        phrase.reset()
        phrase.write(all, cutBytes, all.size - cutBytes)
        phraseBytes = all.size - cutBytes
        blkEnergy.subList(0, k).clear()
        blkLen.subList(0, k).clear()
        blkFlags.subList(0, k).clear()
        return out
    }

    /** Back to waiting for speech, keeping the usual pre-roll. */
    private fun toPreRoll() {
        val all = phrase.toByteArray()
        val keep = min(all.size, preRollMs * bytesPerMs)
        preRoll.clear()
        preRollBytes = 0
        var o = all.size - keep
        val step = bytesPerMs * 50
        while (o < all.size) {
            val n = min(step, all.size - o)
            preRoll.addLast(all.copyOfRange(o, o + n))
            preRollBytes += n
            o += n
        }
        clearPhrase()
        inPhrase = false
        afterCut = false
    }

    private fun rms(b: ByteArray, off: Int, len: Int): Double {
        var sum = 0.0
        var n = 0
        var i = off
        val end = off + len
        while (i + 1 < end) {
            val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort().toDouble()
            sum += s * s
            n++
            i += 2
        }
        return if (n == 0) 0.0 else Math.sqrt(sum / n) / 32768.0
    }

    private companion object {
        const val BLOCK_MS = 10
        const val PAUSE_WINDOW_MS = 80
        const val TALK_WINDOW_MS = 120
        const val SAME_QUIET = 1.26       // energy ratio counted as "as quiet" (1 dB)
        const val PAUSE_TAIL_MS = 100     // keep the fade of the last word before a pause cut
        const val PAUSE_SEARCH_MS = 300   // pause cut: quietest point in the pause's last 300 ms...
        const val PAUSE_GUARD_MS = 100    // ...but not in its very last 100 ms
        const val AFTER_CUT_KEEP_MS = 1200
        const val OLD_PAUSE_MS = 700      // the old detector sent everything up to 0.7 s after speech
        const val SOFT_FACTOR = 1.5       // "soft sound": 3.5 dB over the noise floor
        const val JITTER_N = 32
        const val STEADY_DB = 0.7         // median frame-to-frame change of the background
        const val MIN_SOFT = 0.0015
        const val SOFT_CLEAR_MS = 300
        const val TAIL_MIN_MS = 50
        const val TAIL_KEEP_MS = 200
        const val HIST_FRAMES = 60        // 3 s of 50 ms frames
        const val MIN_HIST_FRAMES = 20
        const val CONTRAST_DB = 6.0
        const val DROP_DB = 6.0
        const val BLIP_MS = 50
        const val MIN_VOICE_MS = 100
        const val BURST = 3.0
        const val UNCLEAR = 0
        const val DIP = 1
        const val VOICE = 2
        const val F_SPEECH = 1
        const val F_VOICE = 2
        const val F_TAIL = 4
    }
}
