package com.omran.caption
import org.junit.Assert.*
import org.junit.Test
class CaptionTextTest {
    @Test fun showsOriginalAndTranslation() {
        assertEquals("Hello\n\nمرحبا", CaptionText.format("Hello", "مرحبا", ""))
    }
    @Test fun showsErrorsInsteadOfFakeTranslation() {
        assertEquals("Hello\n\n⚠ Failed", CaptionText.format("Hello", "Hello", "Failed"))
    }
    @Test fun silenceIsNotSent() {
        assertFalse(CaptionText.hasSound(ByteArray(38400)))
        assertFalse(CaptionText.hasSound(byteArrayOf()))
        assertTrue(CaptionText.hasSound(byteArrayOf(0, 100, 0, 100)))
    }
}
