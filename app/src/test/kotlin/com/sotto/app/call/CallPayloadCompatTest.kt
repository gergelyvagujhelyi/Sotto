package com.sotto.app.call

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test

/** Apps of different versions must understand each other's call payloads. */
class CallPayloadCompatTest {
    /** What apps up to 0.5.7 decode. */
    @Serializable
    private data class OldSdpPayload(val sdp: String)

    @Test
    fun `an older app's offer decodes with no flags`() {
        val payload = CallPayloadJson.decodeFromString<SdpPayload>("""{"sdp":"v=0"}""")
        assertEquals(SdpPayload("v=0"), payload)
        assertEquals(0, payload.caps)
        assertEquals(0, payload.direct)
    }

    @Test
    fun `flags are only written when set`() {
        assertEquals("""{"sdp":"v=0"}""", CallPayloadJson.encodeToString(SdpPayload("v=0")))
        assertEquals("""{"sdp":"v=0","direct":1}""", CallPayloadJson.encodeToString(SdpPayload("v=0", direct = 1)))
    }

    @Test
    fun `an older app reads a payload with flags`() {
        val json = CallPayloadJson.encodeToString(SdpPayload("v=0", caps = 1, direct = 1))
        assertEquals(OldSdpPayload("v=0"), CallPayloadJson.decodeFromString<OldSdpPayload>(json))
    }
}
