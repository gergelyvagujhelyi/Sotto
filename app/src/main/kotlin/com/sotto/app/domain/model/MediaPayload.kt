package com.sotto.app.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Base64

/** What kind of media a message carries. [wire] is its name inside [MediaPayload]. */
enum class MediaKind(val wire: String, val label: String) {
    VOICE("voice", "🎤 Voice message"),
    PHOTO("photo", "📷 Photo");

    companion object {
        fun fromWire(name: String?): MediaKind? = entries.firstOrNull { it.wire == name }
    }
}

/**
 * The media of a message, as the chat shows it: its kind and format, and for a voice
 * message its length, for a photo its size in pixels (0 when unknown). The bytes
 * themselves are on disk, encrypted (MediaStore).
 */
data class MessageMedia(
    val kind: MediaKind,
    val mime: String,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
)

/** What a chat list, notification or reply quote says for a message: its text, or for media its kind and caption. */
fun previewText(media: MediaKind?, caption: String): String = when {
    media == null -> caption
    caption.isBlank() -> media.label
    else -> "${media.label}: ${caption.trim()}"
}

/**
 * The plaintext of a message carrying a voice message or photo inline (ADR-29): the
 * media, base64, with its caption and, when it answers another message, the reply's
 * fields (as in [ReplyPayload]). Like the other payloads it starts with a control
 * character nobody can type and travels ratchet-encrypted, so the relay only sees an
 * ordinary, if larger, message.
 */
object MediaPayload {
    private const val PREFIX = "\u0001sotto/media/v1:"

    /**
     * The most media one message carries. The relay accepts 262,144 base64 characters
     * of ciphertext (about 196 KB); the media is base64 again inside the JSON, which
     * leaves about 147 KB. This keeps a margin for the caption and the reply's quote.
     */
    const val MAX_BYTES = 140_000

    /** The longest voice message: at 16 kbit/s it's about 120 KB. */
    const val MAX_VOICE_MS = 60_000L

    /** Longest caption carried (the composer's limit is lower). */
    private const val CAPTION_MAX = 4_000

    private const val ID_MAX = 64
    private val MIME = Regex("^(audio|image)/[a-z0-9.+-]{1,40}$")

    data class Reply(val to: String, val quote: String, val mine: Boolean)

    data class Decoded(
        val media: MessageMedia,
        val bytes: ByteArray,
        val caption: String,
        val reply: Reply?,
    ) {
        override fun equals(other: Any?) = other is Decoded && media == other.media &&
            bytes.contentEquals(other.bytes) && caption == other.caption && reply == other.reply
        override fun hashCode() = 31 * media.hashCode() + bytes.contentHashCode()
    }

    fun encode(media: MessageMedia, bytes: ByteArray, caption: String, reply: Reply? = null): String {
        require(bytes.size <= MAX_BYTES) { "Media too large: ${bytes.size} bytes" }
        return PREFIX + buildJsonObject {
            put("kind", media.kind.wire)
            put("mime", media.mime)
            if (media.durationMs > 0) put("ms", media.durationMs)
            if (media.width > 0) put("w", media.width)
            if (media.height > 0) put("h", media.height)
            put("caption", caption)
            put("data", Base64.getEncoder().encodeToString(bytes))
            if (reply != null) {
                put("to", reply.to)
                put("quote", ReplyPayload.quoteOf(reply.quote))
                put("mine", reply.mine)
            }
        }.toString()
    }

    fun isMedia(plaintext: String): Boolean = plaintext.startsWith(PREFIX)

    /** The media in [plaintext]; null when it isn't media, or is malformed or too large. */
    fun decode(plaintext: String): Decoded? {
        if (!isMedia(plaintext)) return null
        return runCatching {
            val obj = Json.parseToJsonElement(plaintext.removePrefix(PREFIX)) as JsonObject
            fun string(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun number(key: String) = (obj[key] as? JsonPrimitive)?.takeUnless { it.isString }
            val kind = MediaKind.fromWire(string("kind")) ?: return null
            val mime = string("mime")?.lowercase()?.takeIf { MIME.matches(it) } ?: return null
            if (kind == MediaKind.VOICE && !mime.startsWith("audio/")) return null
            if (kind == MediaKind.PHOTO && !mime.startsWith("image/")) return null
            val data = string("data") ?: return null
            // Checked before decoding, so a huge string isn't turned into a huge array.
            if (data.length > (MAX_BYTES + 2) / 3 * 4) return null
            val bytes = Base64.getDecoder().decode(data)
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
            val durationMs = (number("ms")?.longOrNull ?: 0L).coerceIn(0L, MAX_VOICE_MS * 2)
            val width = (number("w")?.intOrNull ?: 0).coerceIn(0, MAX_DIMENSION)
            val height = (number("h")?.intOrNull ?: 0).coerceIn(0, MAX_DIMENSION)
            val to = string("to")?.takeIf { it.isNotEmpty() && it.length <= ID_MAX }
            Decoded(
                media = MessageMedia(kind, mime, durationMs, width, height),
                bytes = bytes,
                caption = string("caption").orEmpty().take(CAPTION_MAX),
                reply = to?.let {
                    Reply(
                        to = it,
                        quote = ReplyPayload.quoteOf(string("quote").orEmpty()),
                        mine = (obj["mine"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    )
                },
            )
        }.getOrNull()
    }

    /** Pixel sizes beyond this are junk (the sender shrinks photos to about 1280). */
    private const val MAX_DIMENSION = 16_384
}

/**
 * A payload of a kind this app doesn't know yet: a newer Sotto sent something only it
 * can show. Shown as a note instead of the raw payload.
 */
object UnknownPayload {
    /** Every payload starts with this control character, which nobody can type. */
    private const val PREFIX = "\u0001"
    const val NOTE = "This message needs a newer Sotto. Update Sotto to see it."

    fun isUnknown(plaintext: String): Boolean = plaintext.startsWith(PREFIX)
}
