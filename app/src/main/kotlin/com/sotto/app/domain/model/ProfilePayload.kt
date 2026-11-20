package com.sotto.app.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The plaintext of a message that carries the sender's chosen name instead of chat text.
 * It travels like any other message, ratchet-encrypted, so the relay never sees the name.
 * The prefix starts with a control character nobody can type, so chat text is never
 * mistaken for it.
 */
object ProfilePayload {
    private const val PREFIX = "\u0001chatcontroll/profile/v1:"

    fun encode(name: String): String = PREFIX + buildJsonObject { put("name", name) }.toString()

    fun isProfile(plaintext: String): Boolean = plaintext.startsWith(PREFIX)

    /** The name a profile payload carries, not yet sanitised; null when it's malformed. */
    fun decodeName(plaintext: String): String? {
        if (!isProfile(plaintext)) return null
        return runCatching {
            val name = Json.parseToJsonElement(plaintext.removePrefix(PREFIX)).jsonObject["name"]
            (name as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.getOrNull()
    }
}
