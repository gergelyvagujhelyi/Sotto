package com.sotto.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallQualityTest {
    @Test
    fun `HD only when both phones want it`() {
        val high = qualityLevel(true)
        val standard = qualityLevel(false)
        assertEquals(CallQuality.HIGH, agreedQuality(high, high))
        assertEquals(CallQuality.STANDARD, agreedQuality(high, standard))
        assertEquals(CallQuality.STANDARD, agreedQuality(standard, high))
        assertEquals(CallQuality.STANDARD, agreedQuality(high, 0)) // an older app says nothing
    }

    @Test
    fun `a peer can't raise this phone above its own choice`() {
        assertEquals(CallQuality.STANDARD, agreedQuality(CallQuality.STANDARD, 7))
        assertEquals(CallQuality.HIGH, agreedQuality(CallQuality.HIGH, 7)) // a newer app's higher level
        assertEquals(CallQuality.STANDARD, agreedQuality(CallQuality.HIGH, -3)) // garbage
    }

    @Test
    fun `only HD changes the bitrate`() {
        assertNull(opusBitrate(CallQuality.STANDARD))
        assertEquals(48_000, opusBitrate(CallQuality.HIGH))
    }

    private val answer = listOf(
        "v=0",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111 63 9",
        "a=rtpmap:111 opus/48000/2",
        "a=rtcp-fb:111 transport-cc",
        "a=fmtp:111 minptime=10;useinbandfec=1",
        "a=rtpmap:63 red/48000/2",
        "a=fmtp:63 111/111",
        "a=rtpmap:9 G722/8000",
        "",
    ).joinToString("\r\n")

    @Test
    fun `the bitrate joins Opus's own parameters, and nothing else changes`() {
        val changed = withOpusBitrate(answer, 48_000)
        assertTrue(changed.contains("\r\na=fmtp:111 minptime=10;useinbandfec=1;maxaveragebitrate=48000\r\n"))
        assertTrue(changed.contains("\r\na=fmtp:63 111/111\r\n")) // RED isn't Opus
        assertEquals(answer.replace(";useinbandfec=1\r\n", ";useinbandfec=1;maxaveragebitrate=48000\r\n"), changed)
        assertTrue(changed.endsWith("\r\n"))
    }

    @Test
    fun `an existing bitrate is replaced, and applying it twice changes nothing more`() {
        val once = withOpusBitrate(answer.replace("useinbandfec=1", "maxaveragebitrate=20000;useinbandfec=1"), 48_000)
        assertTrue(once.contains("a=fmtp:111 minptime=10;useinbandfec=1;maxaveragebitrate=48000"))
        assertFalse(once.contains("20000"))
        assertEquals(once, withOpusBitrate(once, 48_000))
    }

    @Test
    fun `Opus without parameters gets a line of its own`() {
        val bare = "m=audio 9 RTP/SAVPF 111\na=rtpmap:111 OPUS/48000/2\na=sendrecv\n"
        assertEquals(
            "m=audio 9 RTP/SAVPF 111\na=rtpmap:111 OPUS/48000/2\na=fmtp:111 maxaveragebitrate=48000\na=sendrecv\n",
            withOpusBitrate(bare, 48_000),
        )
    }

    @Test
    fun `older and newer payloads read each other`() {
        val old = CallPayloadJson.decodeFromString(SdpPayload.serializer(), "{\"sdp\":\"x\",\"caps\":1}")
        assertEquals(CallQuality.STANDARD, old.quality)
        val hd = CallPayloadJson.encodeToString(SdpPayload.serializer(), SdpPayload("x", quality = CallQuality.HIGH))
        assertTrue(hd.contains("\"quality\":1"))
        val standard = CallPayloadJson.encodeToString(SdpPayload.serializer(), SdpPayload("x"))
        assertFalse(standard.contains("quality")) // left out, as older apps expect nothing new
    }
}
