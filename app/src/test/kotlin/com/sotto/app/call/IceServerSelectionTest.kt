package com.sotto.app.call

import com.sotto.app.data.remote.dto.IceServerDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IceServerSelectionTest {
    private val ownStun = IceServerDto(urls = "stun:203.0.113.7:3478")
    private val turn = IceServerDto(urls = "turn:203.0.113.7:3478?transport=udp", username = "1800000000:u1", credential = "c")
    private val googleStun = IceServerDto(urls = "stun:stun.l.google.com:19302")

    @Test
    fun `relay-only keeps TURN and drops every STUN server`() {
        assertEquals(listOf(turn), selectIceServers(listOf(ownStun, turn, googleStun), relayOnly = true))
    }

    @Test
    fun `direct keeps STUN on our own relay but never a third party's`() {
        assertEquals(listOf(ownStun, turn), selectIceServers(listOf(ownStun, turn, googleStun), relayOnly = false))
    }

    @Test
    fun `without a relay no STUN server is used at all`() {
        // Old servers without TURN list only Google's STUN server.
        assertTrue(selectIceServers(listOf(googleStun), relayOnly = false).isEmpty())
        assertTrue(selectIceServers(emptyList(), relayOnly = true).isEmpty())
    }

    @Test
    fun `turns URLs count as relays`() {
        val tls = IceServerDto(urls = "turns:relay.example.org:5349?transport=tcp")
        assertEquals(listOf(tls), selectIceServers(listOf(tls, googleStun), relayOnly = true))
        assertEquals(setOf("relay.example.org"), turnHosts(listOf(tls)))
    }

    @Test
    fun `hosts are read from stun and turn URLs`() {
        assertEquals("203.0.113.7", iceUrlHost("turn:203.0.113.7:3478?transport=udp"))
        assertEquals("203.0.113.7", iceUrlHost("stun:203.0.113.7"))
        assertEquals("2001:db8::7", iceUrlHost("turn:[2001:db8::7]:3478"))
        assertEquals("relay.example.org", iceUrlHost("stun:relay.example.org:3478"))
        assertNull(iceUrlHost("stun:"))
        assertNull(iceUrlHost("garbage"))
    }

    @Test
    fun `candidate type is read from the candidate line`() {
        assertEquals("host", candidateType("candidate:1 1 udp 2122260223 192.0.2.4 51000 typ host generation 0"))
        assertEquals("relay", candidateType("candidate:9 1 udp 41885439 203.0.113.7 50123 typ relay raddr 0.0.0.0 rport 0"))
        assertNull(candidateType("garbage"))
    }
}
