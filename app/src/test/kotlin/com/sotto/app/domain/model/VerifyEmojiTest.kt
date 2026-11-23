package com.sotto.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VerifyEmojiTest {

    @Test
    fun `64 different emoji with 64 different names, so each stands for 6 bits`() {
        assertEquals(64, VerifyEmoji.ALL.size)
        assertEquals(64, VerifyEmoji.ALL.map { it.symbol }.toSet().size)
        assertEquals(64, VerifyEmoji.ALL.map { it.name.lowercase() }.toSet().size)
    }

    @Test
    fun `seven emoji come from the first 42 bits, 6 bits each, in order`() {
        // 000000 000001 111111 100000 000000 000000 101111 (+ 6 unused bits)
        //   Dog    Cat    Pin    Hat    Dog    Dog    Key
        val digest = byteArrayOf(0x00, 0x1F, 0xE0.toByte(), 0x00, 0x0B, 0xC0.toByte()) + ByteArray(26)
        assertEquals(
            listOf("Dog", "Cat", "Pin", "Hat", "Dog", "Dog", "Key"),
            VerifyEmoji.fromDigest(digest).map { it.name },
        )
    }

    @Test
    fun `the list's order is fixed, since both phones map bits through it`() {
        assertEquals("Dog", VerifyEmoji.ALL.first().name)
        assertEquals("Key", VerifyEmoji.ALL[47].name)
        assertEquals("Pin", VerifyEmoji.ALL.last().name)
    }
}
