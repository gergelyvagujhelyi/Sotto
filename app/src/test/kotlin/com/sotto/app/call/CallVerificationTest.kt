package com.sotto.app.call

import com.sotto.app.call.CallVerification.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallVerificationTest {

    private val fpA = ByteArray(32) { 1 }
    private val fpB = ByteArray(32) { 2 }
    private val fpM = ByteArray(32) { 3 }

    /** Delivers every message both ways until nothing more is sent. */
    private fun run(a: CallVerification, b: CallVerification, firstA: List<CallVerifyPayload>, firstB: List<CallVerifyPayload>) {
        var toB = firstA
        var toA = firstB
        while (toA.isNotEmpty() || toB.isNotEmpty()) {
            val nextToA = toB.flatMap { b.onPeer(it) }
            val nextToB = toA.flatMap { a.onPeer(it) }
            toA = nextToA
            toB = nextToB
        }
    }

    @Test
    fun `both phones get the same six digits`() {
        val caller = CallVerification("call-1", isCaller = true, myFingerprint = fpA, peerFingerprint = fpB)
        val callee = CallVerification("call-1", isCaller = false, myFingerprint = fpB, peerFingerprint = fpA)

        val fromCaller = caller.start()
        assertEquals(Phase.WAITING, caller.phase)
        val answer = fromCaller.flatMap { callee.onPeer(it) }
        assertEquals(Phase.ASKED, callee.phase)
        assertTrue("nothing is revealed before the user asks", answer.isEmpty())

        run(caller, callee, emptyList(), callee.start())

        assertEquals(Phase.CODE, caller.phase)
        assertEquals(Phase.CODE, callee.phase)
        assertEquals(caller.code, callee.code)
        assertTrue(caller.code!!.matches(Regex("\\d{3} \\d{3}")))
    }

    @Test
    fun `both asking at once works too`() {
        val caller = CallVerification("c", isCaller = true, myFingerprint = fpA, peerFingerprint = fpB)
        val callee = CallVerification("c", isCaller = false, myFingerprint = fpB, peerFingerprint = fpA)
        run(caller, callee, caller.start(), callee.start())
        assertEquals(Phase.CODE, caller.phase)
        assertEquals(caller.code, callee.code)
    }

    @Test
    fun `a phone never reveals before it has the other's commitment`() {
        val caller = CallVerification("c", isCaller = true, myFingerprint = fpA, peerFingerprint = fpB)
        val sent = caller.start()
        assertEquals(listOf(CallVerifyPayload.COMMIT), sent.map { it.kind })
    }

    @Test
    fun `someone in the middle who passes everything through still gets different codes`() {
        // Each phone was given the attacker's keys for the other, so each sees another pair.
        val caller = CallVerification("c", isCaller = true, myFingerprint = fpA, peerFingerprint = fpM)
        val callee = CallVerification("c", isCaller = false, myFingerprint = fpB, peerFingerprint = fpM)
        run(caller, callee, caller.start(), callee.start())
        assertEquals(Phase.CODE, caller.phase)
        assertEquals(Phase.CODE, callee.phase)
        assertNotEquals(caller.code, callee.code)
    }

    @Test
    fun `a reveal that doesn't match its commitment breaks the check`() {
        val caller = CallVerification("c", isCaller = true, myFingerprint = fpA, peerFingerprint = fpB)
        caller.start()
        caller.onPeer(CallVerifyPayload(CallVerifyPayload.COMMIT, "ab".repeat(32)))
        caller.onPeer(CallVerifyPayload(CallVerifyPayload.REVEAL, "cd".repeat(32)))
        assertEquals(Phase.BROKEN, caller.phase)
        assertNull(caller.code)
    }

    @Test
    fun `a second commitment, or a reveal without one, is ignored`() {
        val callee = CallVerification("c", isCaller = false, myFingerprint = fpB, peerFingerprint = fpA)
        assertTrue(callee.onPeer(CallVerifyPayload(CallVerifyPayload.REVEAL, "cd".repeat(32))).isEmpty())
        assertEquals(Phase.IDLE, callee.phase)

        val caller = CallVerification("c", isCaller = true, myFingerprint = fpA, peerFingerprint = fpB)
        callee.onPeer(caller.start().single())
        callee.start() // commit + reveal
        val again = callee.onPeer(CallVerifyPayload(CallVerifyPayload.COMMIT, "00".repeat(32)))
        assertTrue(again.isEmpty())
    }

    @Test
    fun `junk values are ignored`() {
        val callee = CallVerification("c", isCaller = false, myFingerprint = fpB, peerFingerprint = fpA)
        assertTrue(callee.onPeer(CallVerifyPayload(CallVerifyPayload.COMMIT, "not hex")).isEmpty())
        assertTrue(callee.onPeer(CallVerifyPayload(CallVerifyPayload.COMMIT, "abcd")).isEmpty())
        assertEquals(Phase.IDLE, callee.phase)
    }
}
