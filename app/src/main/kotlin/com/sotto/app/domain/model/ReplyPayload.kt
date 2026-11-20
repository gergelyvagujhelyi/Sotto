package com.sotto.app.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a message replies to, as this phone sees it: [messageId] is the original's id here
 * (when this phone has it), [quote] the start of its text, and [toOwn] whether the user
 * wrote the original. The quote travels with the reply, so it shows even when the
 * original is gone (deleted, or disappeared).
 */
data class ReplyRef(val messageId: String, val quote: String, val toOwn: Boolean)

/**
 * The plaintext of a reply: the text, plus which message it answers. Like [ProfilePayload]
 * it starts with a control character nobody can type, and travels ratchet-encrypted, so
 * the relay can't tell a reply from any other message.
 *
 * The original is named by the relay's id for it (ReceiptManager.relayMessageId), the one
 * id both phones know. [Decoded.mine] is from the sender's side: true when the sender
 * wrote the original, so for the receiver it's one of theirs.
 */
object ReplyPayload {
    private const val PREFIX = "\u0001sotto/reply/v1:"

    /** How much of the original travels with a reply; the bubble shows two lines of it. */
    const val QUOTE_MAX = 200

    /** Ids are 32 hex characters (relay) or a UUID (local); anything far longer is junk. */
    private const val ID_MAX = 64

    data class Decoded(val text: String, val to: String, val quote: String, val mine: Boolean)

    fun encode(text: String, to: String, quote: String, mine: Boolean): String =
        PREFIX + buildJsonObject {
            put("text", text)
            put("to", to)
            put("quote", quoteOf(quote))
            put("mine", mine)
        }.toString()

    fun isReply(plaintext: String): Boolean = plaintext.startsWith(PREFIX)

    /** The reply in [plaintext]; null when it isn't one, or is malformed. */
    fun decode(plaintext: String): Decoded? {
        if (!isReply(plaintext)) return null
        return runCatching {
            val obj = Json.parseToJsonElement(plaintext.removePrefix(PREFIX)) as JsonObject
            fun string(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val text = string("text") ?: return null
            val to = string("to")?.takeIf { it.isNotEmpty() && it.length <= ID_MAX } ?: return null
            Decoded(
                text = text,
                to = to,
                quote = quoteOf(string("quote").orEmpty()),
                mine = (obj["mine"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }.getOrNull()
    }

    /** The start of [text] to quote: at most [QUOTE_MAX] characters, never half an emoji. */
    fun quoteOf(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length <= QUOTE_MAX) return trimmed
        var end = QUOTE_MAX
        if (Character.isHighSurrogate(trimmed[end - 1])) end--
        return trimmed.substring(0, end).trimEnd() + "…"
    }
}
