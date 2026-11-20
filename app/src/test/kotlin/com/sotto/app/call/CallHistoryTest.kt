package com.sotto.app.call

import com.sotto.app.domain.model.CallStatus
import com.sotto.app.domain.model.MessageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallHistoryTest {

    @Test
    fun `a call made without internet is a failed call, not an unanswered one`() {
        // The test call of 2026-11-20 15:16 from the Note 9 in airplane mode.
        val state = callEventState(isOutgoing = true, wasConnected = false, endStatus = CallStatus.NO_NETWORK)
        assertEquals(MessageState.CALL_FAILED, state)
        assertEquals("Call failed", callEventPreview(state, CallStatus.NO_NETWORK))
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
            assertEquals(status.name, preview, callEventPreview(state, status))
        }
    }

    @Test
    fun `a call that connected is a voice call however it ended`() {
        listOf(CallStatus.ENDED, CallStatus.FAILED, CallStatus.PEER_GONE, CallStatus.NO_NETWORK).forEach {
            assertEquals(it.name, MessageState.CALL_OUTGOING, callEventState(isOutgoing = true, wasConnected = true, endStatus = it))
            assertEquals(it.name, MessageState.CALL_INCOMING, callEventState(isOutgoing = false, wasConnected = true, endStatus = it))
        }
        assertEquals("Voice call", callEventPreview(MessageState.CALL_OUTGOING, CallStatus.ENDED))
    }

    @Test
    fun `incoming calls that never connected stay missed`() {
        listOf(CallStatus.ENDED, CallStatus.FAILED, CallStatus.NO_RELAY, CallStatus.REJECTED).forEach {
            assertEquals(it.name, MessageState.CALL_MISSED, callEventState(isOutgoing = false, wasConnected = false, endStatus = it))
        }
        assertEquals("Declined call", callEventPreview(MessageState.CALL_MISSED, CallStatus.REJECTED))
        assertEquals("Missed call", callEventPreview(MessageState.CALL_MISSED, CallStatus.ENDED))
    }
}
