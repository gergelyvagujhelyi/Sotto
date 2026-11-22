package com.sotto.app.data.media

import com.sotto.app.domain.model.FilePayload
import com.sotto.app.domain.model.MediaKind
import com.sotto.app.domain.model.MediaPayload
import com.sotto.app.domain.model.previewText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
}
