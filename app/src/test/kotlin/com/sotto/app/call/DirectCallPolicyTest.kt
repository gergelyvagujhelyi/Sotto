package com.sotto.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectCallPolicyTest {
    @Test
    fun `only an earlier contact the user added or accepted is trusted`() {
        assertTrue(isTrustedPeer(existedBeforeCall = true, verified = true, conversationApproved = false))
        assertTrue(isTrustedPeer(existedBeforeCall = true, verified = false, conversationApproved = true))
        assertFalse(isTrustedPeer(existedBeforeCall = true, verified = false, conversationApproved = false))
        // A stranger calling out of the blue, even one who'd become a contact on accept.
        assertFalse(isTrustedPeer(existedBeforeCall = false, verified = true, conversationApproved = true))
    }

    @Test
    fun `the caller proposes only with its setting on and a trusted callee`() {
        assertTrue(offerDirect(settingOn = true, trusted = true))
        assertFalse(offerDirect(settingOn = true, trusted = false))
        assertFalse(offerDirect(settingOn = false, trusted = true))
    }

    @Test
    fun `the callee agrees only with its own setting on, trusting the caller, when proposed`() {
        for (setting in listOf(true, false)) for (trusted in listOf(true, false)) for (offered in listOf(true, false)) {
            assertEquals(setting && trusted && offered, answerDirect(setting, trusted, offered))
        }
    }

    @Test
    fun `with a relay everyone starts relay-only except a callee that agreed`() {
        for (debug in listOf(true, false)) {
            assertEquals(Transport.RELAY, initialTransport(hasTurn = true, isCaller = true, direct = true, debug = debug))
            assertEquals(Transport.RELAY, initialTransport(hasTurn = true, isCaller = true, direct = false, debug = debug))
            assertEquals(Transport.ALL, initialTransport(hasTurn = true, isCaller = false, direct = true, debug = debug))
            assertEquals(Transport.RELAY, initialTransport(hasTurn = true, isCaller = false, direct = false, debug = debug))
        }
    }

    @Test
    fun `without a relay a release build calls only with consent, and the caller waits for the answer`() {
        assertEquals(Transport.RELAY, initialTransport(hasTurn = false, isCaller = true, direct = true, debug = false))
        assertEquals(Transport.NO_RELAY, initialTransport(hasTurn = false, isCaller = true, direct = false, debug = false))
        assertEquals(Transport.ALL, initialTransport(hasTurn = false, isCaller = false, direct = true, debug = false))
        assertEquals(Transport.NO_RELAY, initialTransport(hasTurn = false, isCaller = false, direct = false, debug = false))
    }

    @Test
    fun `debug builds without a relay connect directly`() {
        assertEquals(Transport.ALL, initialTransport(hasTurn = false, isCaller = true, direct = false, debug = true))
        assertEquals(Transport.ALL, initialTransport(hasTurn = false, isCaller = false, direct = false, debug = true))
    }
}
