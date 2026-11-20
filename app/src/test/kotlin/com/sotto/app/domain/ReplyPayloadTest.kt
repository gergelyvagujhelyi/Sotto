package com.sotto.app.domain

import com.sotto.app.domain.model.ProfilePayload
import com.sotto.app.domain.model.ReplyPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyPayloadTest {
    @Test
    fun `a reply survives the round trip`() {
        val encoded = ReplyPayload.encode("Yes, at seven", to = "a1b2c3", quote = "Fika later?", mine = false)
        assertTrue(ReplyPayload.isReply(encoded))
        assertEquals(
            ReplyPayload.Decoded(text = "Yes, at seven", to = "a1b2c3", quote = "Fika later?", mine = false),
            ReplyPayload.decode(encoded),
        )
        assertEquals(true, ReplyPayload.decode(ReplyPayload.encode("x", "id", "q", mine = true))?.mine)
    }

    @Test
    fun `quotes, newlines and emoji in the text are kept`() {
        val text = "He said \"hej\"\nthen 👋 {\"to\":\"x\"}"
        assertEquals(text, ReplyPayload.decode(ReplyPayload.encode(text, "id", text, false))?.text)
    }

    @Test
    fun `chat text is never taken for a reply`() {
        assertFalse(ReplyPayload.isReply("sotto/reply/v1:{\"text\":\"x\",\"to\":\"y\"}"))
        assertNull(ReplyPayload.decode("plain message"))
        assertFalse(ReplyPayload.isReply(ProfilePayload.encode("Astrid")))
    }

    @Test
    fun `malformed replies are refused`() {
        assertNull(ReplyPayload.decode("\u0001sotto/reply/v1:not json"))
        assertNull(ReplyPayload.decode("\u0001sotto/reply/v1:{\"to\":\"y\"}"))
        assertNull(ReplyPayload.decode("\u0001sotto/reply/v1:{\"text\":\"x\"}"))
        assertNull(ReplyPayload.decode("\u0001sotto/reply/v1:{\"text\":\"x\",\"to\":\"\"}"))
        assertNull(ReplyPayload.decode("\u0001sotto/reply/v1:{\"text\":\"x\",\"to\":\"${"a".repeat(65)}\"}"))
        assertNull(ReplyPayload.decode("\u0001sotto/reply/v1:[1,2]"))
    }

    @Test
    fun `a long quote is cut, and a received one is cut too`() {
        val long = "a".repeat(500)
        val quote = ReplyPayload.quoteOf(long)
        assertEquals(ReplyPayload.QUOTE_MAX + 1, quote.length)
        assertTrue(quote.endsWith("…"))
        val forged = "\u0001sotto/reply/v1:{\"text\":\"x\",\"to\":\"y\",\"quote\":\"$long\"}"
        assertEquals(quote, ReplyPayload.decode(forged)?.quote)
    }

    @Test
    fun `a cut never splits an emoji`() {
        val text = "a".repeat(ReplyPayload.QUOTE_MAX - 1) + "😀😀"
        val quote = ReplyPayload.quoteOf(text)
        assertFalse(Character.isHighSurrogate(quote[quote.length - 2]))
        assertEquals("a".repeat(ReplyPayload.QUOTE_MAX - 1) + "…", quote)
    }
}
