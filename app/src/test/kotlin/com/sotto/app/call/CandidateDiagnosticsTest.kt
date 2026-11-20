package com.sotto.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CandidateDiagnosticsTest {
    @Test
    fun `the address family is read without exposing the address`() {
        assertEquals("IPv4", candidateFamily("candidate:1 1 udp 2122260223 192.168.1.20 51000 typ host"))
        assertEquals("IPv6", candidateFamily("candidate:2 1 udp 2122262783 2001:db8::20 51001 typ host"))
        assertEquals("mDNS", candidateFamily("candidate:3 1 udp 2122260223 4b8f1c2e-77aa.local 51002 typ host"))
        assertNull(candidateFamily("garbage"))
    }

    @Test
    fun `pairs are counted by state, direct and relayed apart`() {
        val relay = setOf("203.0.113.7")
        val pairs = listOf(
            CandidatePairState("host", "srflx", "198.51.100.9", "failed"),
            CandidatePairState("srflx", "srflx", "198.51.100.9", "failed"),
            CandidatePairState("srflx", "prflx", "198.51.100.10", "in-progress"),
            CandidatePairState("relay", "srflx", "198.51.100.9", "succeeded"),
            // A peer-reflexive candidate at the relay's own address is relayed too.
            CandidatePairState("srflx", "prflx", "203.0.113.7", "failed"),
        )
        assertEquals(
            "direct pairs: 2 failed, 1 in-progress; relay pairs: 1 failed, 1 succeeded",
            summarizeCandidatePairs(pairs, relay),
        )
    }

    @Test
    fun `a lost direct path shows which side stopped hearing the other, without addresses`() {
        val relay = setOf("203.0.113.7")
        val pairs = listOf(
            CandidatePairState(
                "host", "host", "192.168.1.21", "in-progress",
                requestsSent = 12, responsesReceived = 3, requestsReceived = 9, responsesSent = 9,
                packetsSent = 230, packetsReceived = 180,
            ),
            CandidatePairState("srflx", "relay", "203.0.113.7", "succeeded", requestsSent = 5, responsesReceived = 5),
        )
        val line = describeDirectPairs(pairs, relay)
        assertEquals(
            "host->host in-progress: checks 12 sent, 3 answered; 9 received, 9 answered; packets 230 out, 180 in",
            line,
        )
        assertEquals(false, line.contains("192.168"))
        assertEquals("no direct pairs", describeDirectPairs(pairs.drop(1), relay))
    }

    @Test
    fun `no pairs of a kind reads as none`() {
        assertEquals("direct pairs: none; relay pairs: none", summarizeCandidatePairs(emptyList(), emptySet()))
    }
}
