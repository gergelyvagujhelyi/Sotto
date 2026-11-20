package com.sotto.app.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayConfigTest {
    @Test
    fun `addresses are normalised to https at the host root`() {
        assertEquals("https://relay.example.com", normalizeRelayUrl("relay.example.com"))
        assertEquals("https://relay.example.com", normalizeRelayUrl("  https://Relay.Example.com/  "))
        assertEquals("https://relay.example.com:8443", normalizeRelayUrl("https://relay.example.com:8443"))
    }

    @Test
    fun `anything that can't be a relay at a host root is refused`() {
        assertNull(normalizeRelayUrl(""))
        assertNull(normalizeRelayUrl("not a host"))
        assertNull(normalizeRelayUrl("http://relay.example.com")) // plain http
        assertNull(normalizeRelayUrl("ftp://relay.example.com"))
        assertNull(normalizeRelayUrl("https://relay.example.com/chat")) // requests go to /v1/...
        assertNull(normalizeRelayUrl("https://relay.example.com/?x=1"))
        assertNull(normalizeRelayUrl("https://user@relay.example.com"))
    }

    @Test
    fun `plain http only for the allowed debug hosts`() {
        val debug = setOf("10.0.2.2")
        assertEquals("http://10.0.2.2:8000", normalizeRelayUrl("http://10.0.2.2:8000", debug))
        assertNull(normalizeRelayUrl("http://192.168.1.5:8000", debug))
    }

    @Test
    fun `an account from before the setting keeps the build's relay, a fresh install gets none`() {
        val owner = "https://api.example.org"
        assertEquals(owner, relayForExistingIdentity(hasIdentity = true, stored = null, legacy = owner))
        assertNull(relayForExistingIdentity(hasIdentity = false, stored = null, legacy = owner))
        assertNull(relayForExistingIdentity(hasIdentity = true, stored = "https://other.example", legacy = owner))
        assertNull(relayForExistingIdentity(hasIdentity = true, stored = null, legacy = ""))
    }

    @Test
    fun `push only through the owner's relay`() {
        val owner = "https://api.example.org"
        assertTrue(pushAllowed(owner, owner))
        assertFalse(pushAllowed("https://relay.example.com", owner))
        assertFalse(pushAllowed(null, owner))
        assertFalse(pushAllowed(owner, ""))
    }
}
