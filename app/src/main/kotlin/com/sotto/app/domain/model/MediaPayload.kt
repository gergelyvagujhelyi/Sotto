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
    PHOTO("photo", "📷 Photo"),
    /** Any file, sent through the relay's object store rather than inline (FilePayload). */
    FILE("file", "📎 File");

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
    /** A file's name and size in bytes, and where its transfer is. */
    val name: String = "",
    val size: Long = 0,
    val fileState: FileState? = null,
    /** A photo that also has a full-size copy in the store ([size], [fileState] are that copy's). */
    val hd: Boolean = false,
    /** A file's small JPEG preview (a video frame, a picture), when it came with one. */
    val thumbnail: ByteArray? = null,
)

/** Where a file (MediaKind.FILE) is: on its way up, waiting to be fetched, here, or gone. */
enum class FileState(val wire: String) {
    /** Sender: encrypting and uploading. */
    UPLOADING("uploading"),
    /** Sender: on the relay's store (or already fetched); this phone has its own copy. */
    SENT("sent"),
    /** Receiver: on the relay's store, not downloaded yet. */
    REMOTE("remote"),
    /** Receiver: a download is queued or under way (a background job). */
    DOWNLOADING("downloading"),
    /**
     * A file offered phone to phone: the sender's message is out and the file is on its
     * way directly, or waits for the store as the fallback.
     */
    DIRECT("direct"),
    /** On this phone, ready to open. */
    READY("ready"),
    /** Receiver: the store no longer has it (expired or deleted) and it was never downloaded. */
    GONE("gone");

    companion object {
        fun fromWire(name: String?): FileState? = entries.firstOrNull { it.wire == name }
    }
}

/** What a chat list, notification or reply quote says for a message: its text, or for media its kind and caption. */
fun previewText(media: MediaKind?, caption: String, fileName: String = ""): String {
    val label = if (media == MediaKind.FILE && fileName.isNotBlank()) "📎 $fileName" else media?.label
    return when {
        label == null -> caption
        caption.isBlank() -> label
        else -> "$label: ${caption.trim()}"
    }
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

    /** A photo's full-size copy in the relay's store: its id there, its key, and its size in bytes. */
    class Hd(val id: String, val key: ByteArray, val size: Long) {
        override fun equals(other: Any?) = other is Hd && id == other.id && key.contentEquals(other.key) && size == other.size
        override fun hashCode() = id.hashCode()
    }

    data class Decoded(
        val media: MessageMedia,
        val bytes: ByteArray,
        val caption: String,
        val reply: Reply?,
        val hd: Hd? = null,
    ) {
        override fun equals(other: Any?) = other is Decoded && media == other.media &&
            bytes.contentEquals(other.bytes) && caption == other.caption && reply == other.reply
        override fun hashCode() = 31 * media.hashCode() + bytes.contentHashCode()
    }

    fun encode(media: MessageMedia, bytes: ByteArray, caption: String, reply: Reply? = null, hd: Hd? = null): String {
        require(bytes.size <= MAX_BYTES) { "Media too large: ${bytes.size} bytes" }
        val bare = body(media, bytes, caption, reply, hd, pad = "")
        // Padded (Padmé) so a message's size says little about how long the voice message
        // is or how big the photo; the filler is plain ASCII, one byte per character.
        val bareBytes = (PREFIX + bare).toByteArray(Charsets.UTF_8).size
        val target = com.sotto.app.data.media.Padme.padded(bareBytes.toLong()).toInt()
        return PREFIX + body(media, bytes, caption, reply, hd, pad = "0".repeat((target - bareBytes).coerceAtLeast(0)))
    }

    private fun body(media: MessageMedia, bytes: ByteArray, caption: String, reply: Reply?, hd: Hd?, pad: String): String =
        buildJsonObject {
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
            if (hd != null) {
                put("hd", buildJsonObject {
                    put("id", hd.id)
                    put("key", Base64.getEncoder().encodeToString(hd.key))
                    put("size", hd.size)
                })
            }
            put("pad", pad)
        }.toString()

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
            // Only a photo has an HD copy; a malformed one is ignored, the photo still shows.
            val hd = (obj["hd"] as? JsonObject)?.takeIf { kind == MediaKind.PHOTO }?.let { h ->
                fun hs(key: String) = (h[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val id = hs("id")?.takeIf { HD_ID.matches(it) } ?: return@let null
                val key = hs("key")?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }?.takeIf { it.size == 32 } ?: return@let null
                val size = (h["size"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 1..HD_MAX_BYTES } ?: return@let null
                Hd(id, key, size)
            }
            Decoded(
                hd = hd,
                media = MessageMedia(kind, mime, durationMs, width, height, size = hd?.size ?: 0, hd = hd != null),
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

    private val HD_ID = Regex("^[0-9a-f]{32}$")
    /** The largest HD photo: a 4096 px JPEG is a few MB. */
    const val HD_MAX_BYTES = 30L * 1024 * 1024

    /** Pixel sizes beyond this are junk (the sender shrinks photos to about 1280). */
    private const val MAX_DIMENSION = 16_384
}

/**
 * A file sent through the relay's object store (ADR-29): which object ([id], as the relay
 * named it), the key it's encrypted under, and its name, type and size. Like the other
 * payloads it travels ratchet-encrypted; the relay only ever sees the ciphertext object.
 */
object FilePayload {
    private const val PREFIX = "\u0001sotto/file/v1:"
    private val ID = Regex("^[0-9a-f]{32}$")
    private val MIME = Regex("^[a-z0-9.+-]{1,40}/[a-z0-9.+-]{1,80}$")
    const val MAX_SIZE = 2L * 1024 * 1024 * 1024

    data class Decoded(
        val id: String,
        val key: ByteArray,
        val name: String,
        val mime: String,
        val size: Long,
        val caption: String,
        val reply: MediaPayload.Reply?,
        /** A small JPEG preview (a video's frame, a picture shrunk), and a video's length. */
        val thumbnail: ByteArray? = null,
        val durationMs: Long = 0,
        /** The sender offers it phone to phone first; [id] is then empty until a FileUpdate names it. */
        val direct: Boolean = false,
    ) {
        override fun equals(other: Any?) = other is Decoded && id == other.id && key.contentEquals(other.key) &&
            name == other.name && mime == other.mime && size == other.size && caption == other.caption && reply == other.reply
        override fun hashCode() = id.hashCode()
    }

    /** The largest preview carried: a few KB of JPEG. */
    const val THUMB_MAX_BYTES = 24_000

    fun encode(
        id: String,
        key: ByteArray,
        name: String,
        mime: String,
        size: Long,
        caption: String,
        reply: MediaPayload.Reply? = null,
        thumbnail: ByteArray? = null,
        durationMs: Long = 0,
        direct: Boolean = false,
    ): String {
        val bare = body(id, key, name, mime, size, caption, reply, thumbnail, durationMs, direct, pad = "")
        // Padded like inline media, so the message's size says little about the name or preview.
        val bytes = (PREFIX + bare).toByteArray(Charsets.UTF_8).size
        val target = com.sotto.app.data.media.Padme.padded(bytes.toLong()).toInt()
        return PREFIX + body(id, key, name, mime, size, caption, reply, thumbnail, durationMs, direct, "0".repeat((target - bytes).coerceAtLeast(0)))
    }

    private fun body(
        id: String, key: ByteArray, name: String, mime: String, size: Long, caption: String,
        reply: MediaPayload.Reply?, thumbnail: ByteArray?, durationMs: Long, direct: Boolean, pad: String,
    ): String =
        buildJsonObject {
            put("id", id)
            put("key", Base64.getEncoder().encodeToString(key))
            put("name", name)
            put("mime", mime)
            put("size", size)
            put("caption", caption)
            if (reply != null) {
                put("to", reply.to)
                put("quote", ReplyPayload.quoteOf(reply.quote))
                put("mine", reply.mine)
            }
            if (thumbnail != null && thumbnail.size <= THUMB_MAX_BYTES) put("thumb", Base64.getEncoder().encodeToString(thumbnail))
            if (durationMs > 0) put("ms", durationMs)
            if (direct) put("direct", true)
            put("pad", pad)
        }.toString()

    fun isFile(plaintext: String): Boolean = plaintext.startsWith(PREFIX)

    fun decode(plaintext: String): Decoded? {
        if (!isFile(plaintext)) return null
        return runCatching {
            val obj = Json.parseToJsonElement(plaintext.removePrefix(PREFIX)) as JsonObject
            fun string(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val direct = (obj["direct"] as? JsonPrimitive)?.booleanOrNull == true
            // A file offered phone to phone has no id in the store (yet).
            val id = string("id")?.takeIf { ID.matches(it) || (direct && it.isEmpty()) } ?: return null
            val key = string("key")?.let { Base64.getDecoder().decode(it) }?.takeIf { it.size == 32 } ?: return null
            val size = (obj["size"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..MAX_SIZE } ?: return null
            val mime = string("mime")?.lowercase()?.takeIf { MIME.matches(it) } ?: "application/octet-stream"
            val to = string("to")?.takeIf { it.isNotEmpty() && it.length <= 64 }
            val thumb = string("thumb")?.takeIf { it.length <= (THUMB_MAX_BYTES + 2) / 3 * 4 }
                ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() && it.size <= THUMB_MAX_BYTES }
            Decoded(
                direct = direct,
                thumbnail = thumb,
                durationMs = ((obj["ms"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: 0L).coerceIn(0L, 48L * 3600 * 1000),
                id = id,
                key = key,
                name = com.sotto.app.data.media.FileNames.clean(string("name").orEmpty()),
                mime = mime,
                size = size,
                caption = string("caption").orEmpty().take(4_000),
                reply = to?.let {
                    MediaPayload.Reply(it, ReplyPayload.quoteOf(string("quote").orEmpty()), (obj["mine"] as? JsonPrimitive)?.booleanOrNull ?: false)
                },
            )
        }.getOrNull()
    }
}

/**
 * The handshake of a direct file transfer (ADR-29), sent like a reaction: ratchet-encrypted,
 * signed, stored by neither phone. [file] names the file message by the relay's id for it.
 * The receiver sends an OFFER (its connection description) or NO (it won't or can't go
 * direct); the sender answers with an ANSWER; the receiver confirms with DONE once it has
 * checked the file. Descriptions carry IP addresses, so only consenting, trusted contacts
 * exchange them (as for direct calls, ADR-17).
 */
object DirectPayload {
    private const val PREFIX = "\u0001sotto/direct/v1:"
    enum class Kind { OFFER, ANSWER, DONE, NO }
    data class Decoded(val file: String, val kind: Kind, val sdp: String)

    fun encode(file: String, kind: Kind, sdp: String = ""): String =
        PREFIX + buildJsonObject {
            put("file", file)
            put("kind", kind.name.lowercase())
            if (sdp.isNotEmpty()) put("sdp", sdp)
        }.toString()

    fun isDirect(plaintext: String) = plaintext.startsWith(PREFIX)

    fun decode(plaintext: String): Decoded? {
        if (!isDirect(plaintext)) return null
        return runCatching {
            val obj = Json.parseToJsonElement(plaintext.removePrefix(PREFIX)) as JsonObject
            fun string(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val file = string("file")?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return null
            val kind = Kind.entries.firstOrNull { it.name.lowercase() == string("kind") } ?: return null
            Decoded(file, kind, string("sdp").orEmpty().take(20_000))
        }.getOrNull()
    }
}

/** After a direct transfer didn't happen: the file is in the store after all, under [id]. */
object FileUpdatePayload {
    private const val PREFIX = "\u0001sotto/file-update/v1:"
    private val ID = Regex("^[0-9a-f]{32}$")
    data class Decoded(val file: String, val id: String)

    fun encode(file: String, id: String): String = PREFIX + buildJsonObject {
        put("file", file)
        put("id", id)
    }.toString()

    fun isUpdate(plaintext: String) = plaintext.startsWith(PREFIX)

    fun decode(plaintext: String): Decoded? {
        if (!isUpdate(plaintext)) return null
        return runCatching {
            val obj = Json.parseToJsonElement(plaintext.removePrefix(PREFIX)) as JsonObject
            fun string(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            Decoded(
                file = string("file")?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return null,
                id = string("id")?.takeIf { ID.matches(it) } ?: return null,
            )
        }.getOrNull()
    }
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
