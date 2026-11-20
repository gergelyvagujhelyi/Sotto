package com.sotto.app.call

import com.sotto.app.domain.model.CallPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallPathTest {
    private val relay = setOf("203.0.113.7")
    private val host = "candidate:1 1 udp 2122260223 192.168.1.20 51000 typ host generation 0 ufrag abcd"
    private val srflx = "candidate:2 1 udp 1686052607 198.51.100.4 40000 typ srflx raddr 192.168.1.20 rport 51000"
    private val relayed = "candidate:3 1 udp 41885439 203.0.113.7 50123 typ relay raddr 198.51.100.4 rport 40000"
    private val prflxFromRelay = "candidate:4 1 udp 1853824767 203.0.113.7 50999 typ prflx"
    private val prflxPeer = "candidate:5 1 udp 1853824767 198.51.100.9 41000 typ prflx"

    @Test
    fun `a relay candidate on either side is the relay`() {
        assertEquals(CallPath.RELAYED, classifyCallPath(relayed, relayed, relay))
        assertEquals(CallPath.RELAYED, classifyCallPath(host, relayed, relay))
        assertEquals(CallPath.RELAYED, classifyCallPath(relayed, srflx, relay))
    }

    @Test
    fun `a peer-reflexive candidate at the relay's address is the relay`() {
        assertEquals(CallPath.RELAYED, classifyCallPath(srflx, prflxFromRelay, relay))
    }

    @Test
    fun `host, server- and peer-reflexive pairs are direct`() {
        assertEquals(CallPath.DIRECT, classifyCallPath(host, host, relay))
        assertEquals(CallPath.DIRECT, classifyCallPath(srflx, srflx, relay))
        assertEquals(CallPath.DIRECT, classifyCallPath(host, prflxPeer, relay))
    }

    @Test
    fun `unreadable candidates give no answer`() {
        assertNull(classifyCallPath("garbage", host, relay))
        assertNull(classifyCallPath(host, "", relay))
    }

    @Test
    fun `the address is read with or without the candidate prefix`() {
        assertEquals("192.168.1.20", candidateAddress(host))
        assertEquals("192.168.1.20", candidateAddress("a=$host"))
        assertEquals("203.0.113.7", candidateAddress("3 1 udp 41885439 203.0.113.7 50123 typ relay"))
    }
}
