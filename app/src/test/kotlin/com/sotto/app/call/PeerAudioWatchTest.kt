package com.sotto.app.call

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerAudioWatchTest {

    @Test
    fun `arriving audio keeps the other phone present`() {
        val watch = PeerAudioWatch(startedAt = 0)
        assertEquals(0L, watch.silentFor(receivedBytes = 0, now = 2_000))
        assertEquals(0L, watch.silentFor(receivedBytes = 16_000, now = 4_000))
        assertEquals(0L, watch.silentFor(receivedBytes = 32_000, now = 6_000))
    }

    @Test
    fun `a counter that stops counts silence from the last audio`() {
        val watch = PeerAudioWatch(startedAt = 0)
        watch.silentFor(receivedBytes = 50_000, now = 2_000)
        assertEquals(2_000L, watch.silentFor(receivedBytes = 50_000, now = 4_000))
        assertEquals(12_000L, watch.silentFor(receivedBytes = 50_000, now = 14_000))
        // Audio again: present at once.
        assertEquals(0L, watch.silentFor(receivedBytes = 50_160, now = 16_000))
    }

    @Test
    fun `the verdict follows the silence`() {
        assertEquals(PeerVerdict.PRESENT, peerVerdict(0))
        assertEquals(PeerVerdict.PRESENT, peerVerdict(PEER_UNREACHABLE_MS - 1))
        assertEquals(PeerVerdict.UNREACHABLE, peerVerdict(PEER_UNREACHABLE_MS))
        assertEquals(PeerVerdict.UNREACHABLE, peerVerdict(PEER_GONE_MS - 1))
        assertEquals(PeerVerdict.GONE, peerVerdict(PEER_GONE_MS))
    }

    @Test
    fun `the ghost call of 2026-11-20 ends after a minute`() {
        // The other phone vanished; ICE kept flapping back to "connected", but no audio came.
        val watch = PeerAudioWatch(startedAt = 0)
        watch.silentFor(receivedBytes = 1_000_000, now = 0)
        val verdicts = (2_000L..70_000L step 2_000L).map { peerVerdict(watch.silentFor(1_000_000, it)) }
        assertEquals(PeerVerdict.UNREACHABLE, verdicts[4]) // 10 s
        assertEquals(PeerVerdict.GONE, verdicts.first { it == PeerVerdict.GONE })
        assertEquals(29, verdicts.indexOfFirst { it == PeerVerdict.GONE }) // 60 s
    }
}
