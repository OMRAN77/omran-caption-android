package com.omran.caption

import org.junit.Assert.*
import org.junit.Test

class CueTimelineTest {
    private val first = VideoCue(1000, 2000, "Hello", "مرحبا")
    private val second = VideoCue(3000, 4500, "Thank you", "شكرا")
    private val cues = listOf(first, second)
    @Test fun followsPlaybackNotNetworkArrival() {
        assertNull(CueTimeline.at(cues, 999))
        assertEquals(first, CueTimeline.at(cues, 1000))
        assertNull(CueTimeline.at(cues, 2000))
        assertNull(CueTimeline.at(cues, 2500))
        assertEquals(second, CueTimeline.at(cues, 3200))
    }
    @Test fun seekBackAndPauseKeepCorrectCue() {
        assertEquals(second, CueTimeline.at(cues, 4000))
        assertEquals(first, CueTimeline.at(cues, 1500))
        assertEquals(first, CueTimeline.at(cues, 1500))
        assertNull(CueTimeline.at(cues, 99999))
    }
    @Test fun silenceAndEmptyVideoDoNotInventWords() {
        assertNull(CueTimeline.at(emptyList(), 1000))
        assertFalse(CaptionText.hasSound(ByteArray(16000)))
    }
}
