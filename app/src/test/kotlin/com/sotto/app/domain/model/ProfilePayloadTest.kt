package com.sotto.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePayloadTest {

    @Test
    fun `a name survives the round trip`() {
        val payload = ProfilePayload.encode("Elsa Lund")
        assertTrue(ProfilePayload.isProfile(payload))
        assertEquals("Elsa Lund", ProfilePayload.decodeName(payload))
    }

    @Test
    fun `quotes, emoji and accents survive the round trip`() {
        val name = "Zoë \"Z\" 👩‍💻 Ångström \\ backslash"
        assertEquals(name, ProfilePayload.decodeName(ProfilePayload.encode(name)))
    }

    @Test
    fun `chat text is never taken for a profile`() {
        assertFalse(ProfilePayload.isProfile("Hello"))
        assertFalse(ProfilePayload.isProfile("chatcontroll/profile/v1:{\"name\":\"Mallory\"}"))
        assertNull(ProfilePayload.decodeName("Hello"))
    }

    @Test
    fun `malformed payloads carry no name`() {
        val prefix = ProfilePayload.encode("x").substringBefore("{")
        assertNull(ProfilePayload.decodeName(prefix + "{not json"))
        assertNull(ProfilePayload.decodeName(prefix + "{\"name\":42}"))
        assertNull(ProfilePayload.decodeName(prefix + "{\"name\":null}"))
        assertNull(ProfilePayload.decodeName(prefix + "{}"))
        assertNull(ProfilePayload.decodeName(prefix + "[\"Mallory\"]"))
    }
}
