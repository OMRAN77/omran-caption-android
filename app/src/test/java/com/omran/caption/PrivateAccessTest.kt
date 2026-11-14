package com.omran.caption

import org.junit.Assert.*
import org.junit.Test

class PrivateAccessTest {
    @Test fun rejectsInvalidHeaderValues() {
        listOf("", "short", "a".repeat(257), "a".repeat(32) + "\n",
            "a".repeat(32) + " ", "ع".repeat(32)).forEach {
            assertFalse(PrivateAccess.valid(it))
        }
    }
    @Test fun storesAndClearsOnlySessionState() {
        PrivateAccess.clear()
        assertEquals("", PrivateAccess.get())
        val fixture = "test-only-".repeat(5)
        assertTrue(PrivateAccess.set(fixture))
        assertEquals(fixture, PrivateAccess.get())
        PrivateAccess.clear()
        assertEquals("", PrivateAccess.get())
    }
}
