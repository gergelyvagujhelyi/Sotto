package com.sotto.app.crypto.ratchet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SecretBytesTest {
    @Test
    fun `everything written reads back, through growth of the buffer`() {
        val big = ByteArray(5000) { it.toByte() }
        val bytes = SecretBytesWriter(initialSize = 8)
            .byte(7).bool(true).bool(false).int(-5).int(Int.MAX_VALUE).long(Long.MIN_VALUE)
            .bytes(big).bytes(null).bytes(ByteArray(0)).string("hej").string(null).strings(listOf("a", "bé"))
            .toByteArray()
        val r = SecretBytesReader(bytes)
        assertEquals(7, r.byte())
        assertTrue(r.bool()); assertFalse(r.bool())
        assertEquals(-5, r.int()); assertEquals(Int.MAX_VALUE, r.int()); assertEquals(Long.MIN_VALUE, r.long())
        assertArrayEquals(big, r.bytes()); assertNull(r.bytesOrNull()); assertArrayEquals(ByteArray(0), r.bytes())
        assertEquals("hej", r.string()); assertNull(r.stringOrNull()); assertEquals(listOf("a", "bé"), r.strings())
        r.end()
    }

    @Test
    fun `truncated or padded input is refused, not misread`() {
        val bytes = SecretBytesWriter().bytes(ByteArray(10) { 1 }).int(3).toByteArray()
        for (bad in listOf(bytes.copyOf(bytes.size - 1), bytes.copyOf(6), bytes + 0)) {
            try {
                val r = SecretBytesReader(bad)
                r.bytes(); r.int(); r.end()
                fail("must be refused")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `a length that runs past the end is refused`() {
        val lying = SecretBytesWriter().int(1_000_000).toByteArray()
        try {
            SecretBytesReader(lying).bytes()
            fail("must be refused")
        } catch (_: IllegalArgumentException) {
        }
    }
}
