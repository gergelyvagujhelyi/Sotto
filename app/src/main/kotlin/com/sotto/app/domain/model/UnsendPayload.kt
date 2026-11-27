package com.sotto.app.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Taking back a message we sent (ADR-36). Like [ReactionPayload] it starts with a control
 * character nobody can type and travels ratchet-encrypted, so the relay sees an ordinary
 * message. It is never shown as a message: the receiver empties the one it names and keeps
 * only a note that it was unsent.
 *
 * Only the sender's own message can be unsent, so it's named by the relay's id for it
 * (ReceiptManager.relayMessageId of the sender's local id): the id the receiver stores it under.
 */
object UnsendPayload {
    private const val PREFIX = "\u0001sotto/unsend/v1:"

    /** Ids are 32 hex characters (relay); anything far longer is junk. */
    private const val ID_MAX = 64

    fun encode(to: String): String = PREFIX + buildJsonObject { put("to", to) }.toString()

    fun isUnsend(plaintext: String): Boolean = plaintext.startsWith(PREFIX)

    /** The id of the message [plaintext] unsends; null when it isn't an unsend, or is malformed. */
    fun decode(plaintext: String): String? {
        if (!isUnsend(plaintext)) return null
        return runCatching {
            val obj = Json.parseToJsonElement(plaintext.removePrefix(PREFIX)) as JsonObject
            (obj["to"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotEmpty() && it.length <= ID_MAX }
        }.getOrNull()
    }

    /**
     * What a reply's stored quote becomes when the message it quotes is unsent: the quote's
     * text goes too. Starts with a control character, so no real quote is ever this.
     */
    const val UNSENT_QUOTE = "\u0001unsent"

    /** A stored quote as it goes to other phones: [UNSENT_QUOTE] is this phone's own, and goes as none. */
    fun wireQuote(quote: String?): String = quote?.takeIf { it != UNSENT_QUOTE }.orEmpty()
}
