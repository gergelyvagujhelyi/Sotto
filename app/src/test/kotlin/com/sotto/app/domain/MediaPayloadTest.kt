package com.sotto.app.domain

import com.sotto.app.data.media.MediaCrypto
import com.sotto.app.domain.model.MediaKind
import com.sotto.app.domain.model.MediaPayload
import com.sotto.app.domain.model.MessageMedia
import com.sotto.app.domain.model.ReplyPayload
import com.sotto.app.domain.model.UnknownPayload
import com.sotto.app.domain.model.previewUiText
import com.sotto.app.R
import com.sotto.app.ui.text.UiText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

class MediaPayloadTest {
    private val voice = MessageMedia(MediaKind.VOICE, "audio/ogg", durationMs = 4_200)
    private val photo = MessageMedia(MediaKind.PHOTO, "image/jpeg", width = 1280, height = 960)

    @Test
    fun `a voice message and a photo survive the round trip`() {
        val audio = Random.nextBytes(30_000)
        val v = MediaPayload.decode(MediaPayload.encode(voice, audio, ""))!!
        assertEquals(voice, v.media)
        assertArrayEquals(audio, v.bytes)
        assertEquals("", v.caption)
        assertNull(v.reply)

        val jpeg = Random.nextBytes(MediaPayload.MAX_BYTES)
        val p = MediaPayload.decode(MediaPayload.encode(photo, jpeg, "At the lake", MediaPayload.Reply("abc123", "Where are you?", mine = true)))!!
        assertEquals(photo, p.media)
        assertArrayEquals(jpeg, p.bytes)
        assertEquals("At the lake", p.caption)
        assertEquals(MediaPayload.Reply("abc123", "Where are you?", true), p.reply)
    }

    @Test
    fun `the largest media still fits in one relay message`() {
        // The relay takes 262,144 base64 characters of ciphertext; AES-GCM adds 16 bytes.
        val payload = MediaPayload.encode(
            photo,
            Random.nextBytes(MediaPayload.MAX_BYTES),
            "x".repeat(2_000),
            MediaPayload.Reply("f".repeat(32), "q".repeat(ReplyPayload.QUOTE_MAX), mine = false),
        )
        val ciphertext = payload.toByteArray(Charsets.UTF_8).size + 16
        val base64 = (ciphertext + 2) / 3 * 4
        assertTrue("$base64 base64 characters", base64 <= 262_144)
    }

    @Test
    fun `too much media is refused both ways`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaPayload.encode(photo, ByteArray(MediaPayload.MAX_BYTES + 1), "")
        }
        val big = Base64.getEncoder().encodeToString(ByteArray(MediaPayload.MAX_BYTES + 1))
        val forged = "\u0001sotto/media/v1:{\"kind\":\"photo\",\"mime\":\"image/jpeg\",\"caption\":\"\",\"data\":\"$big\"}"
        assertNull(MediaPayload.decode(forged))
    }

    @Test
    fun `junk is not media`() {
        fun media(json: String) = MediaPayload.decode("\u0001sotto/media/v1:$json")
        val data = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        assertNull(media("not json"))
        assertNull(media("{\"kind\":\"video\",\"mime\":\"video/mp4\",\"data\":\"$data\"}"))
        // A voice message must be audio and a photo an image.
        assertNull(media("{\"kind\":\"voice\",\"mime\":\"image/jpeg\",\"data\":\"$data\"}"))
        assertNull(media("{\"kind\":\"photo\",\"mime\":\"text/html\",\"data\":\"$data\"}"))
        assertNull(media("{\"kind\":\"photo\",\"mime\":\"image/../../x\",\"data\":\"$data\"}"))
        assertNull(media("{\"kind\":\"photo\",\"mime\":\"image/jpeg\",\"data\":\"\"}"))
        assertNull(media("{\"kind\":\"photo\",\"mime\":\"image/jpeg\"}"))
        assertNotNull(media("{\"kind\":\"photo\",\"mime\":\"image/jpeg\",\"data\":\"$data\"}"))
        assertFalse(MediaPayload.isMedia("Hello"))
    }

    @Test
    fun `sizes from the sender are kept within bounds`() {
        val data = Base64.getEncoder().encodeToString(byteArrayOf(1))
        val d = MediaPayload.decode(
            "\u0001sotto/media/v1:{\"kind\":\"photo\",\"mime\":\"image/jpeg\",\"w\":-5,\"h\":99999999,\"ms\":-1,\"data\":\"$data\"}",
        )!!
        assertEquals(0, d.media.width)
        assertEquals(16_384, d.media.height)
        assertEquals(0L, d.media.durationMs)
    }

    @Test
    fun `chats, notifications and quotes name the media`() {
        assertEquals(UiText.res(R.string.chat_media_voice), previewUiText(MediaKind.VOICE, ""))
        assertEquals(
            UiText.res(R.string.chat_media_with_caption, UiText.res(R.string.chat_media_photo), "At the lake"),
            previewUiText(MediaKind.PHOTO, " At the lake "),
        )
        assertEquals(UiText.Plain("Hi"), previewUiText(null, "Hi"))
    }

    @Test
    fun `a payload from a newer Sotto is a note, never raw text`() {
        assertTrue(UnknownPayload.isUnknown("\u0001sotto/sticker/v1:{}"))
        assertTrue(UnknownPayload.isUnknown("\u0001sotto/media/v2:{}"))
        assertFalse(UnknownPayload.isUnknown("Hello"))
    }

    @Test
    fun `stored media decrypts only with its key and for its own message`() {
        val key = MediaCrypto.newKey()
        val bytes = Random.nextBytes(5_000)
        val aad = "sotto/media-file/v1:m1".toByteArray()
        val sealed = MediaCrypto.seal(key, bytes, aad)
        assertArrayEquals(bytes, MediaCrypto.open(key, sealed, aad))
        assertThrows(Exception::class.java) { MediaCrypto.open(MediaCrypto.newKey(), sealed, aad) }
        assertThrows(Exception::class.java) { MediaCrypto.open(key, sealed, "sotto/media-file/v1:m2".toByteArray()) }
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { MediaCrypto.open(key, sealed, aad) }
    }

    @Test
    fun `an HD photo names its full-size copy, and a bad one is ignored`() {
        val key = MediaCrypto.newKey()
        val hd = MediaPayload.Hd("0123456789abcdef0123456789abcdef", key, 3_400_000)
        val d = MediaPayload.decode(MediaPayload.encode(photo, byteArrayOf(1, 2, 3), "", hd = hd))!!
        assertEquals(hd, d.hd)
        assertTrue(d.media.hd)
        assertEquals(3_400_000L, d.media.size)

        val data = Base64.getEncoder().encodeToString(byteArrayOf(1))
        val k = Base64.getEncoder().encodeToString(key)
        fun with(hdJson: String, kind: String = "photo", mime: String = "image/jpeg") =
            MediaPayload.decode("\u0001sotto/media/v1:{\"kind\":\"$kind\",\"mime\":\"$mime\",\"data\":\"$data\",\"hd\":$hdJson}")!!
        assertNull(with("{\"id\":\"../x\",\"key\":\"$k\",\"size\":5}").hd)
        assertNull(with("{\"id\":\"0123456789abcdef0123456789abcdef\",\"key\":\"AAAA\",\"size\":5}").hd)
        assertNull(with("{\"id\":\"0123456789abcdef0123456789abcdef\",\"key\":\"$k\",\"size\":0}").hd)
        // Only photos have an HD copy.
        assertNull(with("{\"id\":\"0123456789abcdef0123456789abcdef\",\"key\":\"$k\",\"size\":5}", "voice", "audio/ogg").hd)
    }
}
