package com.sotto.app.call

import com.sotto.app.domain.model.CallStatus
import com.sotto.app.domain.model.MessageState

/** End states of a call that never got going: nobody declined or missed it. */
private val CALL_FAILURES = setOf(CallStatus.FAILED, CallStatus.NO_RELAY, CallStatus.NO_NETWORK)

/**
 * The chat-history entry for a call that ended with [endStatus].
 *
 * An outgoing call that failed before it connected is "Call failed", not "Unanswered call":
 * with no internet it never left the phone, and the other phone never rang. An incoming call
 * the user [answered] is never "missed": if it failed before connecting it's "Call failed",
 * and if either side hung up first it's a plain incoming call.
 */
internal fun callEventState(
    isOutgoing: Boolean,
    wasConnected: Boolean,
    endStatus: CallStatus,
    answered: Boolean = false,
): MessageState = when {
    isOutgoing && wasConnected -> MessageState.CALL_OUTGOING
    isOutgoing && endStatus in CALL_FAILURES -> MessageState.CALL_FAILED
    isOutgoing -> MessageState.CALL_OUTGOING_MISSED
    wasConnected -> MessageState.CALL_INCOMING
    answered && endStatus in CALL_FAILURES -> MessageState.CALL_FAILED
    answered -> MessageState.CALL_INCOMING
    else -> MessageState.CALL_MISSED
}

/** Whether that entry also gets a missed-call notification: a call the user neither answered nor declined. */
internal fun notifiesMissedCall(state: MessageState, endStatus: CallStatus): Boolean =
    state == MessageState.CALL_MISSED && endStatus != CallStatus.REJECTED

/** The chat list's preview of that entry; [wasConnected] as for [callEventState]. */
internal fun callEventPreview(state: MessageState, endStatus: CallStatus, wasConnected: Boolean): String = when (state) {
    MessageState.CALL_OUTGOING -> "Voice call"
    // Answered, but hung up before it connected: the chat says "Incoming call" too.
    MessageState.CALL_INCOMING -> if (wasConnected) "Voice call" else "Incoming call"
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
