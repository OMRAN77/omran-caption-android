package com.omran.caption

data class VideoCue(val startMs: Long, val endMs: Long, val original: String, val translated: String)

object CueTimeline {
    fun at(cues: List<VideoCue>, positionMs: Long): VideoCue? =
        cues.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }
}
