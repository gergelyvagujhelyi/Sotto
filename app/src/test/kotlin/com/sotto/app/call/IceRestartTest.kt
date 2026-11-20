package com.sotto.app.call

import com.sotto.app.data.remote.dto.IceServerDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IceRestartTest {
    private fun candidate(gen: Int, n: Int = 0) = IceCandidateDto("0", 0, "candidate:$n 1 udp 1 203.0.113.7 5000$n typ relay", gen)

    // --- IceCandidateGate ---

    @Test
    fun `candidates wait for the first description, then apply`() {
        val gate = IceCandidateGate()
        assertFalse(gate.offer(candidate(0, 1)))
        assertFalse(gate.offer(candidate(0, 2)))
        assertEquals(listOf(candidate(0, 1), candidate(0, 2)), gate.onRemoteApplied(0))
        assertTrue(gate.offer(candidate(0, 3)))
    }

    @Test
    fun `a newer generation waits for its description, an older one is dropped`() {
        val gate = IceCandidateGate()
        gate.onRemoteApplied(0)
        assertFalse(gate.offer(candidate(1)))   // restart 1's candidate before its answer
        assertTrue(gate.offer(candidate(0)))    // still the current generation
        assertEquals(listOf(candidate(1)), gate.onRemoteApplied(1))
        assertFalse(gate.offer(candidate(0)))   // late, from before the restart
        assertTrue(gate.offer(candidate(1)))
    }

    @Test
    fun `applying a generation drops waiting candidates of older ones`() {
        val gate = IceCandidateGate()
        gate.onRemoteApplied(0)
        gate.offer(candidate(1))
        gate.offer(candidate(2))
        assertEquals(listOf(candidate(2)), gate.onRemoteApplied(2))
        assertEquals(emptyList<IceCandidateDto>(), gate.onRemoteApplied(3))
    }

    @Test
    fun `the applied generation never goes back`() {
        val gate = IceCandidateGate()
        gate.onRemoteApplied(2)
        assertEquals(emptyList<IceCandidateDto>(), gate.onRemoteApplied(1))
        assertEquals(2, gate.appliedGen)
    }

    @Test
    fun `waiting candidates are bounded`() {
        val gate = IceCandidateGate(maxQueued = 3)
        repeat(10) { gate.offer(candidate(0, it)) }
        assertEquals(3, gate.onRemoteApplied(0).size)
    }

    // --- RestartNegotiator ---

    @Test
    fun `the caller numbers restarts and is throttled`() {
        val caller = RestartNegotiator(isCaller = true)
        assertEquals(1, caller.beginRestart(now = 10_000))
        assertNull(caller.beginRestart(now = 10_000 + RestartNegotiator.MIN_INTERVAL_MS - 1))
        assertEquals(2, caller.beginRestart(now = 10_000 + RestartNegotiator.MIN_INTERVAL_MS))
    }

    @Test
    fun `only the answer to the pending restart counts, once`() {
        val caller = RestartNegotiator(isCaller = true)
        caller.beginRestart(0)
        caller.beginRestart(RestartNegotiator.MIN_INTERVAL_MS)
        assertFalse(caller.onAnswer(1))          // answers the superseded restart
        assertTrue(caller.onAnswer(2))
        assertFalse(caller.onAnswer(2))          // again: already answered
        assertFalse(caller.isPending)
    }

    @Test
    fun `an unanswered offer is sent again with growing gaps, a bounded number of times`() {
        val caller = RestartNegotiator(isCaller = true)
        caller.beginRestart(0)
        var due = RestartNegotiator.backoff(0)
        assertFalse(caller.retransmitDue(due - 1))
        assertTrue(caller.retransmitDue(due))
        due += RestartNegotiator.backoff(1)
        assertTrue(RestartNegotiator.backoff(1) > RestartNegotiator.backoff(0))
        assertFalse(caller.retransmitDue(due - 1))
        assertTrue(caller.retransmitDue(due))
        var now = due
        var sent = 2
        while (caller.isRetransmitting) {
            now += 60_000
            if (caller.retransmitDue(now)) sent++
        }
        assertEquals(RestartNegotiator.MAX_RETRANSMITS, sent)
        assertFalse(caller.retransmitDue(now + 60_000))
    }

    @Test
    fun `no retransmits once answered`() {
        val caller = RestartNegotiator(isCaller = true)
        caller.beginRestart(0)
        caller.onAnswer(1)
        assertFalse(caller.retransmitDue(100_000))
        assertFalse(caller.isRetransmitting)
    }

    @Test
    fun `the callee applies new offers, answers repeats again and ignores old ones`() {
        val callee = RestartNegotiator(isCaller = false)
        assertEquals(RestartNegotiator.OfferAction.IGNORE, callee.onOffer(0))
        assertEquals(RestartNegotiator.OfferAction.APPLY, callee.onOffer(1))
        // Not applied yet (it failed, say): the same offer is applied again.
        assertEquals(RestartNegotiator.OfferAction.APPLY, callee.onOffer(1))
        callee.offerApplied(1)
        assertEquals(RestartNegotiator.OfferAction.RESEND_ANSWER, callee.onOffer(1))
        assertEquals(RestartNegotiator.OfferAction.APPLY, callee.onOffer(3))
        callee.offerApplied(3)
        assertEquals(RestartNegotiator.OfferAction.IGNORE, callee.onOffer(2))
        callee.offerApplied(2)   // a late one can't move it back
        assertEquals(3, callee.currentGen)
    }

    // --- TURN credentials ---

    private val turn = IceServerDto("turn:203.0.113.7:3478?transport=udp", username = "1800003600:user", credential = "c")

    @Test
    fun `credential expiry is read from the TURN username`() {
        assertEquals(1_800_003_600L, turnCredentialExpiry(listOf(IceServerDto("stun:203.0.113.7:3478"), turn)))
        assertNull(turnCredentialExpiry(listOf(IceServerDto("stun:203.0.113.7:3478"))))
        assertNull(turnCredentialExpiry(listOf(turn.copy(username = "no-timestamp"))))
        val later = turn.copy(username = "1800009000:user")
        assertEquals(1_800_003_600L, turnCredentialExpiry(listOf(later, turn)))
    }

    @Test
    fun `credentials expire soon only within the margin`() {
        assertFalse(turnCredentialExpiresSoon(listOf(turn), nowSec = 1_800_000_000, marginSec = 900))
        assertTrue(turnCredentialExpiresSoon(listOf(turn), nowSec = 1_800_003_000, marginSec = 900))
        assertTrue(turnCredentialExpiresSoon(listOf(turn), nowSec = 1_800_004_000, marginSec = 900))
        assertFalse(turnCredentialExpiresSoon(emptyList(), nowSec = 1_800_004_000, marginSec = 900))
    }

    // --- payloads ---

    /** What apps up to 0.5.7 decode. */
    @Serializable
    private data class OldIceCandidateDto(val sdpMid: String, val sdpMLineIndex: Int, val sdp: String)

    @Test
    fun `first-generation candidates look the same as before`() {
        val json = CallPayloadJson.encodeToString(IceCandidateDto("0", 0, "candidate:x"))
        assertEquals("""{"sdpMid":"0","sdpMLineIndex":0,"sdp":"candidate:x"}""", json)
        assertEquals(0, CallPayloadJson.decodeFromString<IceCandidateDto>(json).gen)
    }

    @Test
    fun `restart candidates carry their generation, which older apps ignore`() {
        val json = CallPayloadJson.encodeToString(IceCandidateDto("0", 0, "candidate:x", gen = 2))
        assertEquals(2, CallPayloadJson.decodeFromString<IceCandidateDto>(json).gen)
        assertEquals(OldIceCandidateDto("0", 0, "candidate:x"), CallPayloadJson.decodeFromString<OldIceCandidateDto>(json))
    }

    @Test
    fun `restart payloads round-trip, a request without sdp`() {
        val request = IceRestartPayload(IceRestartPayload.REQUEST, 3)
        assertEquals("""{"kind":"request","gen":3}""", CallPayloadJson.encodeToString(request))
        val offer = IceRestartPayload(IceRestartPayload.OFFER, 4, "v=0")
        assertEquals(offer, CallPayloadJson.decodeFromString<IceRestartPayload>(CallPayloadJson.encodeToString(offer)))
    }

    @Test
    fun `restart support is a capability bit`() {
        assertTrue(supportsIceRestart(CAPS_ICE_RESTART))
        assertTrue(supportsIceRestart(CAPS_ICE_RESTART or 4))
        assertFalse(supportsIceRestart(0))
    }

    @Test
    fun `renewal timing scales with the credentials' lifetime`() {
        // The relay's usual hour: renew 10 min before, refetch with under 15 min left (as before).
        assertEquals(600L, turnRenewLeadSec(3600))
        assertEquals(900L, turnRenewMarginSec(3600))
        // A one-minute test lifetime: renew 10 s before, refetch with under 15 s left.
        assertEquals(10L, turnRenewLeadSec(60))
        assertEquals(15L, turnRenewMarginSec(60))
        for (lifetime in listOf(30L, 60L, 120L, 600L, 3600L, 7200L)) {
            // The restart made to renew them finds them due...
            assertTrue(turnRenewMarginSec(lifetime) > turnRenewLeadSec(lifetime))
            // ...and comes well after the fetch, never in a loop.
            assertTrue(lifetime - turnRenewLeadSec(lifetime) >= lifetime / 2)
        }
    }
}
