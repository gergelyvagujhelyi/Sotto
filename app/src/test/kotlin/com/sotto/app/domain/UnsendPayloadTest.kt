package com.sotto.app.domain

import com.sotto.app.domain.model.ReactionPayload
import com.sotto.app.domain.model.ReplyPayload
import com.sotto.app.domain.model.UnknownPayload
import com.sotto.app.domain.model.UnsendPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsendPayloadTest {

    @Test
    fun `an unsend names the message it takes back`() {
        val encoded = UnsendPayload.encode("0123456789abcdef0123456789abcdef")
        assertTrue(UnsendPayload.isUnsend(encoded))
        assertEquals("0123456789abcdef0123456789abcdef", UnsendPayload.decode(encoded))
    }

    @Test
    fun `it's a payload older apps show as needing a newer Sotto, never as text`() {
        val encoded = UnsendPayload.encode("id")
        assertTrue(UnknownPayload.isUnknown(encoded))
        assertFalse(ReactionPayload.isReaction(encoded))
        assertFalse(ReplyPayload.isReply(encoded))
    }

    @Test
    fun `text and malformed unsends are not taken`() {
        val prefix = UnsendPayload.encode("x").substringBefore("{")
        listOf(
            "Unsend that!",
            ReactionPayload.encode("id", "❤️", mine = true),
            prefix,
            "$prefix{}",
            "$prefix{\"to\":\"\"}",
            "$prefix{\"to\":42}",
            "$prefix{\"to\":\"${"a".repeat(65)}\"}",
            "$prefix[\"id\"]",
        ).forEach { assertNull(it, UnsendPayload.decode(it)) }
    }

    @Test
    fun `the unsent quote can't be a real one`() {
        assertTrue(UnsendPayload.UNSENT_QUOTE.startsWith("\u0001"))
        assertFalse(ReplyPayload.quoteOf("Unsent message") == UnsendPayload.UNSENT_QUOTE)
    }
}
