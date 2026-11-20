package com.sotto.app.call

import com.sotto.app.domain.model.CallStatus
import com.sotto.app.domain.model.MessageState

/** End states of an outgoing call that never got going: nobody declined or missed it. */
private val CALL_FAILURES = setOf(CallStatus.FAILED, CallStatus.NO_RELAY, CallStatus.NO_NETWORK)

/**
 * The chat-history entry for a call that ended with [endStatus].
 *
 * An outgoing call that failed before it connected is "Call failed", not "Unanswered call":
 * with no internet it never left the phone, and the other phone never rang.
 */
internal fun callEventState(isOutgoing: Boolean, wasConnected: Boolean, endStatus: CallStatus): MessageState = when {
    isOutgoing && wasConnected -> MessageState.CALL_OUTGOING
    isOutgoing && endStatus in CALL_FAILURES -> MessageState.CALL_FAILED
    isOutgoing -> MessageState.CALL_OUTGOING_MISSED
    wasConnected -> MessageState.CALL_INCOMING
    else -> MessageState.CALL_MISSED
}

/** The chat list's preview of that entry. */
internal fun callEventPreview(state: MessageState, endStatus: CallStatus): String = when (state) {
    MessageState.CALL_OUTGOING, MessageState.CALL_INCOMING -> "Voice call"
    MessageState.CALL_FAILED -> "Call failed"
    MessageState.CALL_OUTGOING_MISSED -> when (endStatus) {
        CallStatus.REJECTED -> "Call declined"
        CallStatus.BUSY -> "Busy"
        CallStatus.UNAVAILABLE -> "No answer"
        else -> "Outgoing call"
    }
    MessageState.CALL_MISSED -> when (endStatus) {
        CallStatus.REJECTED -> "Declined call"
        else -> "Missed call"
    }
    else -> "Call"
}
