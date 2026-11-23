package com.sotto.app.data.media

import com.sotto.app.domain.model.FilePayload
import com.sotto.app.domain.model.MediaKind
import com.sotto.app.domain.model.MediaPayload
import com.sotto.app.domain.model.previewText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class FileDropTest {
    private fun encrypt(plain: ByteArray, key: ByteArray): ByteArray =
        ByteArrayOutputStream().also { SegmentedCrypto.encrypt(ByteArrayInputStream(plain), it, key) }.toByteArray()

    private fun decrypt(sealed: ByteArray, key: ByteArray): ByteArray =
        ByteArrayOutputStream().also { SegmentedCrypto.decrypt(ByteArrayInputStream(sealed), it, key) }.toByteArray()

    @Test
    fun `files of every size come back the same, at the size predicted`() {
        val key = SegmentedCrypto.newKey()
        val seg = SegmentedCrypto.SEGMENT
        for (size in listOf(0, 1, seg - 1, seg, seg + 1, 3 * seg, 3 * seg + 17)) {
            val plain = Random.nextBytes(size)
            val sealed = encrypt(plain, key)
            assertEquals("size $size", SegmentedCrypto.encryptedSize(size.toLong()), sealed.size.toLong())
            assertArrayEquals("size $size", plain, decrypt(sealed, key))
        }
    }

    @Test
    fun `a changed, cut, extended or reordered file doesn't decrypt`() {
        val key = SegmentedCrypto.newKey()
        val seg = SegmentedCrypto.SEGMENT + 16
        val sealed = encrypt(Random.nextBytes(3 * SegmentedCrypto.SEGMENT + 100), key)
        val h = SegmentedCrypto.HEADER

        assertThrows(Exception::class.java) { decrypt(sealed, SegmentedCrypto.newKey()) }
        assertThrows(Exception::class.java) { decrypt(sealed.copyOf().also { it[h + 5] = (it[h + 5] + 1).toByte() }, key) }
        // Cut at a segment boundary: the new last segment wasn't sealed as the last one.
        assertThrows(Exception::class.java) { decrypt(sealed.copyOf(h + 2 * seg), key) }
        assertThrows(Exception::class.java) { decrypt(sealed.copyOf(sealed.size - 1), key) }
        assertThrows(Exception::class.java) { decrypt(sealed + ByteArray(20), key) }
        val swapped = sealed.copyOf()
        System.arraycopy(sealed, h, swapped, h + seg, seg)
        System.arraycopy(sealed, h + seg, swapped, h, seg)
        assertThrows(Exception::class.java) { decrypt(swapped, key) }
        assertThrows(Exception::class.java) { decrypt(ByteArray(0), key) }
    }

    @Test
    fun `the file message carries what the receiver needs`() {
        val key = SegmentedCrypto.newKey()
        val id = "0123456789abcdef0123456789abcdef"
        val d = FilePayload.decode(
            FilePayload.encode(id, key, "Semester.mp4", "video/mp4", 12_345_678, "Från stugan", MediaPayload.Reply("r1", "Hej", false)),
        )!!
        assertEquals(id, d.id)
        assertArrayEquals(key, d.key)
        assertEquals("Semester.mp4", d.name)
        assertEquals("video/mp4", d.mime)
        assertEquals(12_345_678L, d.size)
        assertEquals("Från stugan", d.caption)
        assertEquals(MediaPayload.Reply("r1", "Hej", false), d.reply)
    }

    @Test
    fun `junk file messages are refused`() {
        fun file(json: String) = FilePayload.decode("\u0001sotto/file/v1:$json")
        val key = java.util.Base64.getEncoder().encodeToString(ByteArray(32))
        assertNull(file("{\"id\":\"../../etc\",\"key\":\"$key\",\"size\":1}"))
        assertNull(file("{\"id\":\"0123456789abcdef0123456789abcdef\",\"key\":\"AAAA\",\"size\":1}"))
        assertNull(file("{\"id\":\"0123456789abcdef0123456789abcdef\",\"key\":\"$key\",\"size\":-1}"))
        assertNull(file("{\"id\":\"0123456789abcdef0123456789abcdef\",\"key\":\"$key\"}"))
        assertEquals(
            "application/octet-stream",
            file("{\"id\":\"0123456789abcdef0123456789abcdef\",\"key\":\"$key\",\"size\":1,\"mime\":\"text/html; x\"}")!!.mime,
        )
    }

    @Test
    fun `names from another phone can't escape or mislead`() {
        assertEquals("_.._etc_passwd", FileNames.clean("/../etc/passwd"))
        assertEquals("invoice_fdp.exe", FileNames.clean("invoice‮fdp.exe"))
        assertEquals("file", FileNames.clean(" .. "))
        val long = FileNames.clean("a".repeat(500) + ".pdf")
        assertEquals(120, long.length)
        assertEquals(".pdf", long.takeLast(4))
    }

    @Test
    fun `a file is named in the chat list`() {
        assertEquals("📎 Semester.mp4", previewText(MediaKind.FILE, "", "Semester.mp4"))
        assertEquals("📎 Semester.mp4: Från stugan", previewText(MediaKind.FILE, "Från stugan", "Semester.mp4"))
        assertEquals("📎 File", previewText(MediaKind.FILE, ""))
    }

    @Test
    fun `Padme rounds sizes up by at most about 12 percent`() {
        assertEquals(1024L, Padme.padded(1000))
        assertEquals(0L, Padme.padded(0))
        assertEquals(7L, Padme.padded(7))
        for (n in listOf(100L, 4_095L, 65_537L, 1_000_000L, 7_546_436L, 104_000_000L)) {
            val p = Padme.padded(n)
            assertTrue("$n -> $p", p >= n && p <= n * 1.12 + 1)
            assertEquals("idempotent at $n", p, Padme.padded(p))
        }
        // Nearby sizes land on the same padded size: the store can't tell them apart.
        assertEquals(Padme.padded(7_546_436), Padme.padded(7_500_000))
    }

    @Test
    fun `a padded file decrypts back to its real size`() {
        val key = SegmentedCrypto.newKey()
        val plain = Random.nextBytes(150_001)
        val sealed = ByteArrayOutputStream().also { SegmentedCrypto.encrypt(PadmeInput(ByteArrayInputStream(plain)), it, key) }.toByteArray()
        assertEquals(SegmentedCrypto.encryptedSize(Padme.padded(plain.size.toLong())), sealed.size.toLong())
        val out = ByteArrayOutputStream()
        SegmentedCrypto.decrypt(ByteArrayInputStream(sealed), TruncatingOutput(out, plain.size.toLong()), key)
        assertArrayEquals(plain, out.toByteArray())
    }

    @Test
    fun `inline media is padded too, and still decodes`() {
        val photo = com.sotto.app.domain.model.MessageMedia(MediaKind.PHOTO, "image/jpeg", width = 10, height = 10)
        val a = MediaPayload.encode(photo, Random.nextBytes(50_000), "Hej")
        val b = MediaPayload.encode(photo, Random.nextBytes(50_500), "Hej")
        assertEquals(a.toByteArray().size, b.toByteArray().size)
        assertEquals(Padme.padded(a.toByteArray().size.toLong()), a.toByteArray().size.toLong())
        assertEquals("Hej", MediaPayload.decode(a)!!.caption)
        // Non-ASCII captions are counted in bytes.
        val c = MediaPayload.encode(photo, Random.nextBytes(50_000), "Fika på stugan ☕")
        assertEquals(Padme.padded(c.toByteArray().size.toLong()), c.toByteArray().size.toLong())
    }

    @Test
    fun `a file message carries its preview and length, and is padded`() {
        val key = SegmentedCrypto.newKey()
        val thumb = Random.nextBytes(12_000)
        val p = FilePayload.encode("0123456789abcdef0123456789abcdef", key, "Stugan.mp4", "video/mp4", 5_000_000, "", thumbnail = thumb, durationMs = 12_000)
        val d = FilePayload.decode(p)!!
        assertArrayEquals(thumb, d.thumbnail)
        assertEquals(12_000L, d.durationMs)
        assertEquals(Padme.padded(p.toByteArray().size.toLong()), p.toByteArray().size.toLong())
        // Too big a preview is left out rather than refused.
        val big = FilePayload.encode("0123456789abcdef0123456789abcdef", key, "a", "video/mp4", 1, "", thumbnail = ByteArray(FilePayload.THUMB_MAX_BYTES + 1))
        assertNull(FilePayload.decode(big)!!.thumbnail)
    }

    @Test
    fun `a file offered directly has no store id until an update names one`() {
        val key = SegmentedCrypto.newKey()
        val d = FilePayload.decode(FilePayload.encode("", key, "a.bin", "application/octet-stream", 10, "", direct = true))!!
        assertTrue(d.direct)
        assertEquals("", d.id)
        // Without "direct", an empty id is refused.
        assertNull(FilePayload.decode(FilePayload.encode("", key, "a.bin", "application/octet-stream", 10, "")))
        val u = com.sotto.app.domain.model.FileUpdatePayload.decode(
            com.sotto.app.domain.model.FileUpdatePayload.encode("relayid", "0123456789abcdef0123456789abcdef"),
        )!!
        assertEquals("relayid", u.file)
        assertNull(com.sotto.app.domain.model.FileUpdatePayload.decode(com.sotto.app.domain.model.FileUpdatePayload.encode("r", "../x")))
    }

    @Test
    fun `direct handshake messages round-trip and junk is dropped`() {
        val o = com.sotto.app.domain.model.DirectPayload.decode(com.sotto.app.domain.model.DirectPayload.encode("f1", com.sotto.app.domain.model.DirectPayload.Kind.OFFER, "v=0 sdp"))!!
        assertEquals(com.sotto.app.domain.model.DirectPayload.Kind.OFFER, o.kind)
        assertEquals("v=0 sdp", o.sdp)
        assertEquals(com.sotto.app.domain.model.DirectPayload.Kind.NO, com.sotto.app.domain.model.DirectPayload.decode(com.sotto.app.domain.model.DirectPayload.encode("f1", com.sotto.app.domain.model.DirectPayload.Kind.NO))!!.kind)
        assertNull(com.sotto.app.domain.model.DirectPayload.decode("\u0001sotto/direct/v1:{\"file\":\"f1\",\"kind\":\"steal\"}"))
        assertNull(com.sotto.app.domain.model.DirectPayload.decode("\u0001sotto/direct/v1:{\"kind\":\"offer\"}"))
    }
}
