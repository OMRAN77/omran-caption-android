package com.omran.caption

import java.io.ByteArrayOutputStream

/** Wraps raw 16-bit mono PCM bytes in a minimal WAV header. */
object PcmToWav {
    fun wrap(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val totalDataLen = pcm.size + 36
        val byteRate = sampleRate * 2

        fun writeString(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeIntLE(v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
            out.write((v shr 16) and 0xff)
            out.write((v shr 24) and 0xff)
        }
        fun writeShortLE(v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
        }

        writeString("RIFF")
        writeIntLE(totalDataLen)
        writeString("WAVE")
        writeString("fmt ")
        writeIntLE(16)
        writeShortLE(1) // PCM
        writeShortLE(1) // mono
        writeIntLE(sampleRate)
        writeIntLE(byteRate)
        writeShortLE(2) // block align
        writeShortLE(16) // bits per sample
        writeString("data")
        writeIntLE(pcm.size)
        out.write(pcm)

        return out.toByteArray()
    }
}
