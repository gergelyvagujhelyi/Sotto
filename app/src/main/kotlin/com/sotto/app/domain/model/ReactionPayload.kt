package com.sotto.app.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A reaction to a message (an emoji, as on WhatsApp), or taking one back. Like
 * [ReplyPayload] it starts with a control character nobody can type and travels
 * ratchet-encrypted, so the relay sees an ordinary message. It is never shown as a
 * message: the receiver puts the emoji on the one it names.
 *
 * The message is named the way a reply names its original: by the relay's id for it
 * (ReceiptManager.relayMessageId). [Decoded.mine] is from the sender's side: true when
 * the sender wrote the message, so for the receiver it's one of theirs. An empty emoji
 * takes the sender's reaction back.
 */
object ReactionPayload {
    private const val PREFIX = "\u0001sotto/reaction/v1:"

    /** Ids are 32 hex characters (relay) or a UUID (local); anything far longer is junk. */
    private const val ID_MAX = 64

    /** The longest emoji (a family, a flag with its tags) is well under this many chars. */
    const val EMOJI_MAX = 32

    /** The quick choices, in WhatsApp's order. */
    val QUICK = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

    /** Behind "+": the quick ones and more. */
    val MORE = listOf(
        "👍", "❤️", "😂", "😮", "😢", "🙏", "😍", "🥰", "😘", "😊",
        "😁", "🤣", "😅", "😉", "😎", "🤩", "🥳", "😇", "🤗", "🤔",
        "🙄", "😏", "😬", "😴", "🥺", "😭", "😡", "🤯", "😱", "🤢",
        "👎", "👏", "🙌", "👌", "✌️", "🤞", "💪", "👋", "🤝", "🫶",
        "💯", "🔥", "✨", "🎉", "💔", "💕", "💖", "⭐", "✅", "❌",
        "🌹", "🌸", "☀️", "🌈", "🍻", "☕", "🍕", "🎂", "🐶", "🐱",
    )

    data class Decoded(val to: String, val emoji: String?, val mine: Boolean)

    /** [emoji] null takes the reaction back. */
    fun encode(to: String, emoji: String?, mine: Boolean): String =
        PREFIX + buildJsonObject {
            put("to", to)
            put("emoji", emoji.orEmpty())
            put("mine", mine)
        }.toString()

    fun isReaction(plaintext: String): Boolean = plaintext.startsWith(PREFIX)

    /** The reaction in [plaintext]; null when it isn't one, or is malformed. */
    fun decode(plaintext: String): Decoded? {
        if (!isReaction(plaintext)) return null
        return runCatching {
            val obj = Json.parseToJsonElement(plaintext.removePrefix(PREFIX)) as JsonObject
            fun string(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val to = string("to")?.takeIf { it.isNotEmpty() && it.length <= ID_MAX } ?: return null
            val emoji = string("emoji") ?: return null
            if (emoji.isNotEmpty() && !isEmoji(emoji)) return null
            Decoded(
                to = to,
                emoji = emoji.ifEmpty { null },
                mine = (obj["mine"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }.getOrNull()
    }

    /**
     * Whether [text] can be shown as a reaction: short, one line, no spaces or control
     * characters, and not plain letters or digits. Not a full emoji check: it keeps out
     * text, not every odd symbol.
     */
    fun isEmoji(text: String): Boolean {
        if (text.isEmpty() || text.length > EMOJI_MAX) return false
        var i = 0
        var sawSymbol = false
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.isISOControl(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> return false
                Character.isLetterOrDigit(cp) && cp !in KEYCAP_BASES -> return false
                cp == KEYCAP -> sawSymbol = true
                cp in KEYCAP_BASES || cp == ZWJ || cp in VARIATION_SELECTORS || cp in TAGS || cp in SKIN_TONES -> Unit
                else -> sawSymbol = true
            }
        }
        return sawSymbol
    }

    private const val ZWJ = 0x200D
    private const val KEYCAP = 0x20E3
    private val VARIATION_SELECTORS = 0xFE00..0xFE0F
    private val TAGS = 0xE0020..0xE007F
    private val SKIN_TONES = 0x1F3FB..0x1F3FF
    /** "1️⃣" starts with a digit; "#️⃣" and "*️⃣" are symbols anyway. */
    private val KEYCAP_BASES = '0'.code..'9'.code
}
