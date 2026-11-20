package com.sotto.app.ui.chat

import com.sotto.app.domain.model.Message
import com.sotto.app.domain.model.MessageState
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatArrivalsTest {
    private fun message(id: String, text: String = "hi", outgoing: Boolean = false, state: MessageState = MessageState.DELIVERED) =
        Message(
            id = id,
            conversationId = "c",
            senderId = if (outgoing) "me" else "them",
            recipientId = if (outgoing) "them" else "me",
            plaintext = text,
            state = state,
            timestamp = Instant.fromEpochMilliseconds(0),
            isOutgoing = outgoing,
        )

    @Test
    fun `only messages not seen before arrive, each once`() {
        val seen = mutableSetOf("a")
        val first = newArrivals(listOf(message("a"), message("b")), seen, flightText = null)
        assertEquals(listOf("b"), first.animate)
        val again = newArrivals(listOf(message("a"), message("b")), seen, flightText = null)
        assertTrue(again.animate.isEmpty())
    }

    @Test
    fun `the message just sent belongs to the flight, received ones rise`() {
        val found = newArrivals(
            listOf(message("in", "hey"), message("out", "On my way", outgoing = true)),
            mutableSetOf(),
            flightText = "On my way",
        )
        assertEquals("out", found.flightId)
        assertEquals(listOf("in"), found.animate)
    }

    @Test
    fun `a received message with the same text never takes the flight`() {
        val found = newArrivals(listOf(message("in", "ok")), mutableSetOf(), flightText = "ok")
        assertNull(found.flightId)
        assertEquals(listOf("in"), found.animate)
    }

    @Test
    fun `call and key events never animate`() {
        val found = newArrivals(
            listOf(message("call", state = MessageState.CALL_MISSED), message("key", state = MessageState.KEY_ROTATED_REMOTE)),
            mutableSetOf(),
            flightText = null,
        )
        assertTrue(found.animate.isEmpty())
    }

    @Test
    fun `of a batch fetched at once only the newest few animate`() {
        val batch = (1..8).map { message("m$it") }
        assertEquals(listOf("m6", "m7", "m8"), newArrivals(batch, mutableSetOf(), flightText = null).animate)
    }
}
