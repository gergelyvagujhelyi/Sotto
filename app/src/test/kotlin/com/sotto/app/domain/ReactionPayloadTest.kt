package com.sotto.app.domain

import com.sotto.app.data.repository.reactionNotificationText
import com.sotto.app.R
import com.sotto.app.ui.text.UiText
import com.sotto.app.domain.model.Message
import com.sotto.app.domain.model.MessageState
import com.sotto.app.domain.model.ProfilePayload
import com.sotto.app.domain.model.ReactionPayload
import com.sotto.app.domain.model.ReplyPayload
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionPayloadTest {
    @Test
    fun `a reaction survives the round trip`() {
        val encoded = ReactionPayload.encode(to = "a1b2c3", emoji = "❤️", mine = true)
        assertTrue(ReactionPayload.isReaction(encoded))
        assertEquals(ReactionPayload.Decoded(to = "a1b2c3", emoji = "❤️", mine = true), ReactionPayload.decode(encoded))
    }

    @Test
    fun `taking a reaction back sends no emoji`() {
        assertEquals(
            ReactionPayload.Decoded(to = "a1b2c3", emoji = null, mine = false),
            ReactionPayload.decode(ReactionPayload.encode("a1b2c3", null, mine = false)),
        )
    }

    @Test
    fun `every offered emoji travels`() {
        (ReactionPayload.QUICK + ReactionPayload.MORE).forEach {
            assertEquals(it, it, ReactionPayload.decode(ReactionPayload.encode("id", it, false))?.emoji)
        }
    }

    @Test
    fun `skin tones, families, flags and keycaps are emoji`() {
        listOf("👍🏽", "👨‍👩‍👧", "🇸🇪", "🏴󠁧󠁢󠁳󠁣󠁴󠁿", "1️⃣", "1\u20E3", "#️⃣", "♥").forEach { assertTrue(it, ReactionPayload.isEmoji(it)) }
    }

    @Test
    fun `text is not a reaction`() {
        listOf("", "a", "ok", "1", "1\uFE0F", "12", "❤️ ❤️", "❤️\n", "\u0000", "😀".repeat(20)).forEach {
            assertFalse(it, ReactionPayload.isEmoji(it))
            assertNull(it, ReactionPayload.decode(ReactionPayload.encode("id", it, false).takeIf { _ -> it.isNotEmpty() } ?: "x"))
        }
    }

    @Test
    fun `chat text, replies and profiles are never taken for a reaction`() {
        listOf(
            "Hej",
            "{\"to\":\"x\",\"emoji\":\"❤️\"}",
            ReplyPayload.encode("x", "id", "q", false),
            ProfilePayload.encode("Maja"),
        ).forEach {
            assertFalse(it, ReactionPayload.isReaction(it))
            assertNull(it, ReactionPayload.decode(it))
        }
    }

    @Test
    fun `malformed reactions are dropped`() {
        val prefix = ReactionPayload.encode("id", "❤️", false).substringBefore("{")
        listOf(
            prefix,
            "$prefix{",
            "$prefix[]",
            "$prefix{\"emoji\":\"❤️\"}",
            "$prefix{\"to\":\"\",\"emoji\":\"❤️\"}",
            "$prefix{\"to\":\"${"a".repeat(65)}\",\"emoji\":\"❤️\"}",
            "$prefix{\"to\":\"id\"}",
            "$prefix{\"to\":\"id\",\"emoji\":5}",
        ).forEach { assertNull(it, ReactionPayload.decode(it)) }
    }

    @Test
    fun `only messages the contact has can be reacted to`() {
        fun message(state: MessageState, outgoing: Boolean, text: String = "Hej") = Message(
            id = "m", conversationId = "c", senderId = "s", recipientId = "r", plaintext = text,
            state = state, timestamp = Instant.fromEpochMilliseconds(0), isOutgoing = outgoing,
        )
        assertTrue(message(MessageState.SENT, outgoing = true).canReact)
        assertTrue(message(MessageState.SEEN, outgoing = true).canReact)
        assertTrue(message(MessageState.DELIVERED, outgoing = false).canReact)
        assertFalse(message(MessageState.SENDING, outgoing = true).canReact)
        assertFalse(message(MessageState.FAILED, outgoing = true).canReact)
        assertFalse(message(MessageState.DECRYPT_FAILED, outgoing = false).canReact)
        assertFalse(message(MessageState.REJECTED, outgoing = false).canReact)
    }

    @Test
    fun `the notification names the emoji and quotes the message`() {
        assertEquals(
            UiText.res(R.string.chat_reaction_notification_quote, "❤️", "See you at 6"),
            reactionNotificationText("❤️", "  See you\n at 6 "),
        )
        val long = (reactionNotificationText("👍", "a".repeat(100)) as UiText.Res).args[1] as String
        assertTrue(long, long.endsWith("…") && long.length == 60)
        assertEquals(UiText.res(R.string.chat_reaction_notification, "👍"), reactionNotificationText("👍", ""))
    }
}
