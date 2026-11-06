package com.omran.caption

import org.json.JSONObject

/**
 * Tuning values read from the server (api/config.js) each time captions start,
 * so they can be changed there without installing a new APK. Every value is
 * clamped to a safe range; anything missing or broken keeps the built-in
 * default (the same numbers the server sends today).
 */
data class RemoteSettings(
    val sliceMinMs: Int = 1100,
    val sliceMaxMs: Int = 1500,
    val silenceRms: Double = 0.002,
    val quietRatio: Double = 0.5,
    val emptyBeforeClear: Int = 3,
    val contextLines: Int = 2,
    // Speech detector: a slice is sent only with at least minSpeechMs of
    // speech (Silero probability >= vadThreshold) in it.
    val vadEnabled: Boolean = true,
    val vadThreshold: Double = 0.35,
    val minSpeechMs: Int = 128,
) {
    companion object {
        /** Parses the server's JSON; returns defaults for anything missing or out of range. */
        fun fromJson(text: String?): RemoteSettings {
            val d = RemoteSettings()
            if (text.isNullOrBlank()) return d
            return try {
                val j = JSONObject(text)
                val min = j.optInt("sliceMinMs", d.sliceMinMs).coerceIn(300, 5000)
                RemoteSettings(
                    sliceMinMs = min,
                    sliceMaxMs = j.optInt("sliceMaxMs", d.sliceMaxMs).coerceIn(min + 100, 8000),
                    silenceRms = j.optDouble("silenceRms", d.silenceRms).let { if (it.isNaN()) d.silenceRms else it.coerceIn(0.0, 0.05) },
                    quietRatio = j.optDouble("quietRatio", d.quietRatio).let { if (it.isNaN()) d.quietRatio else it.coerceIn(0.05, 1.0) },
                    emptyBeforeClear = j.optInt("emptyBeforeClear", d.emptyBeforeClear).coerceIn(1, 20),
                    contextLines = j.optInt("contextLines", d.contextLines).coerceIn(0, 5),
                    vadEnabled = j.optBoolean("vadEnabled", d.vadEnabled),
                    vadThreshold = j.optDouble("vadThreshold", d.vadThreshold).let { if (it.isNaN()) d.vadThreshold else it.coerceIn(0.05, 0.95) },
                    minSpeechMs = j.optInt("minSpeechMs", d.minSpeechMs).coerceIn(0, 1000),
                )
            } catch (e: Exception) {
                d
            }
        }
    }
}
