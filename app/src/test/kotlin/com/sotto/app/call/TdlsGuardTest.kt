package com.sotto.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class TdlsGuardTest {
    private val calls = mutableListOf<Pair<String, Boolean>>()
    private val guard = TdlsGuard { address, enabled -> calls += address.hostAddress!! to enabled }

    @Test
    fun `only private IPv4 addresses are local network peers`() {
        listOf("10.0.0.7", "172.16.0.1", "172.31.255.254", "192.168.1.23").forEach {
            assertTrue(it, isLocalNetworkAddress(it))
        }
        listOf(
            "8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.0.1", "100.64.0.1", // public, or carrier NAT
            "192.168.1", "192.168.1.256", "192.168.01.1x", "fe80::1", "fd00::1", "relay.example.com", "",
        ).forEach { assertFalse(it, isLocalNetworkAddress(it)) }
    }

    @Test
    fun `a local peer is turned off once, and back on when the call ends`() {
        assertTrue(guard.keepOff("192.168.1.23"))
        assertFalse("already off", guard.keepOff("192.168.1.23"))
        assertEquals(listOf("192.168.1.23" to false), calls)

        guard.restoreAll()
        assertEquals(listOf("192.168.1.23" to false, "192.168.1.23" to true), calls)

        // The next call starts afresh.
        assertTrue(guard.keepOff("192.168.1.23"))
    }

    @Test
    fun `addresses outside the local network are left alone`() {
        assertFalse(guard.keepOff("203.0.113.9"))
        guard.restoreAll()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a refusal from the system isn't remembered or restored`() {
        val refusing = TdlsGuard { _: InetAddress, _: Boolean -> throw SecurityException("no permission") }
        assertFalse(refusing.keepOff("10.0.0.7"))
        refusing.restoreAll() // nothing to restore, and it doesn't throw
    }
}
