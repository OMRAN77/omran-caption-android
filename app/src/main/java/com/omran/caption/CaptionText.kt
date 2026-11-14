package com.omran.caption

object CaptionText {
    fun format(original: String, translated: String, error: String): String {
        val result = if (error.isNotBlank()) "⚠ $error" else translated
        return listOf(original.trim(), result.trim()).filter { it.isNotEmpty() }.distinct().joinToString("\n\n")
    }
    fun hasSound(pcm: ByteArray): Boolean {
        if (pcm.size < 2) return false
        var sum = 0.0
        for (i in 0 until pcm.size - 1 step 2) {
            val value = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toDouble() / 32768
            sum += value * value
        }
        return sum / (pcm.size / 2) > 0.000001
    }
}
