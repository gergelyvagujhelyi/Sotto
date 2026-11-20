package com.sotto.app.call

import com.sotto.app.domain.model.CallStatus
import com.sotto.app.domain.model.MessageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallHistoryTest {

    @Test
    fun `a call made without internet is a failed call, not an unanswered one`() {
        // The test call of 2026-11-20 15:16 from the Note 9 in airplane mode.
        val state = callEventState(isOutgoing = true, wasConnected = false, endStatus = CallStatus.NO_NETWORK)
        assertEquals(MessageState.CALL_FAILED, state)
        assertEquals("Call failed", callEventPreview(state, CallStatus.NO_NETWORK, wasConnected = false))
        assertTrue(state.isCallEvent)
    }

    @Test
    fun `outgoing calls that fail before connecting are failed calls`() {
        listOf(CallStatus.FAILED, CallStatus.NO_RELAY, CallStatus.NO_NETWORK).forEach {
            assertEquals(it.name, MessageState.CALL_FAILED, callEventState(isOutgoing = true, wasConnected = false, endStatus = it))
        }
    }

    @Test
    fun `calls the other side didn't take stay unanswered`() {
        mapOf(
            CallStatus.ENDED to "Outgoing call", // cancelled before they answered
            CallStatus.REJECTED to "Call declined",
            CallStatus.BUSY to "Busy",
            CallStatus.UNAVAILABLE to "No answer",
        ).forEach { (status, preview) ->
            val state = callEventState(isOutgoing = true, wasConnected = false, endStatus = status)
            assertEquals(status.name, MessageState.CALL_OUTGOING_MISSED, state)
            assertEquals(status.name, preview, callEventPreview(state, status, wasConnected = false))
        }
    }

    @Test
    fun `a call that connected is a voice call however it ended`() {
        listOf(CallStatus.ENDED, CallStatus.FAILED, CallStatus.PEER_GONE, CallStatus.NO_NETWORK).forEach {
            assertEquals(it.name, MessageState.CALL_OUTGOING, callEventState(isOutgoing = true, wasConnected = true, endStatus = it))
            assertEquals(it.name, MessageState.CALL_INCOMING, callEventState(isOutgoing = false, wasConnected = true, endStatus = it))
        }
        assertEquals("Voice call", callEventPreview(MessageState.CALL_OUTGOING, CallStatus.ENDED, wasConnected = true))
    }

    @Test
    fun `a call you answered that couldn't connect is a failed call, with no missed-call notification`() {
        listOf(CallStatus.FAILED, CallStatus.NO_RELAY, CallStatus.NO_NETWORK).forEach {
            val state = callEventState(isOutgoing = false, wasConnected = false, endStatus = it, answered = true)
            assertEquals(it.name, MessageState.CALL_FAILED, state)
            assertEquals(it.name, "Call failed", callEventPreview(state, it, wasConnected = false))
            assertFalse(it.name, notifiesMissedCall(state, it))
        }
    }

    @Test
    fun `a call you answered that was hung up before it connected is an incoming call, not missed`() {
        val state = callEventState(isOutgoing = false, wasConnected = false, endStatus = CallStatus.ENDED, answered = true)
        assertEquals(MessageState.CALL_INCOMING, state)
        assertFalse(notifiesMissedCall(state, CallStatus.ENDED))
        // The chat list says so too: it said "Voice call" in 0.6.6, as if you had talked.
        assertEquals("Incoming call", callEventPreview(state, CallStatus.ENDED, wasConnected = false))
        assertEquals("Voice call", callEventPreview(MessageState.CALL_INCOMING, CallStatus.ENDED, wasConnected = true))
    }

    @Test
    fun `only calls you neither answered nor declined notify as missed`() {
        assertTrue(notifiesMissedCall(callEventState(false, false, CallStatus.ENDED), CallStatus.ENDED))
        assertTrue(notifiesMissedCall(callEventState(false, false, CallStatus.UNAVAILABLE), CallStatus.UNAVAILABLE))
        assertFalse(notifiesMissedCall(callEventState(false, false, CallStatus.REJECTED), CallStatus.REJECTED))
        assertFalse(notifiesMissedCall(callEventState(false, true, CallStatus.ENDED), CallStatus.ENDED))
        assertFalse(notifiesMissedCall(callEventState(true, false, CallStatus.ENDED), CallStatus.ENDED))
    }

    @Test
    fun `incoming calls that never connected stay missed`() {
        listOf(CallStatus.ENDED, CallStatus.FAILED, CallStatus.NO_RELAY, CallStatus.REJECTED).forEach {
            assertEquals(it.name, MessageState.CALL_MISSED, callEventState(isOutgoing = false, wasConnected = false, endStatus = it))
        }
        assertEquals("Declined call", callEventPreview(MessageState.CALL_MISSED, CallStatus.REJECTED, wasConnected = false))
        assertEquals("Missed call", callEventPreview(MessageState.CALL_MISSED, CallStatus.ENDED, wasConnected = false))
    }
}
