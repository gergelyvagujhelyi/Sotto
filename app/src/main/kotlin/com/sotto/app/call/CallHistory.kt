package com.sotto.app.call

import com.sotto.app.R
import com.sotto.app.domain.model.CallStatus
import com.sotto.app.domain.model.MessageState
import com.sotto.app.ui.text.UiText

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
internal fun callEventPreview(state: MessageState, endStatus: CallStatus, wasConnected: Boolean): UiText = UiText.res(
    when (state) {
        MessageState.CALL_OUTGOING -> R.string.call_history_voice_call
        // Answered, but hung up before it connected: the chat says "Incoming call" too.
        MessageState.CALL_INCOMING -> if (wasConnected) R.string.call_history_voice_call else R.string.call_incoming
        MessageState.CALL_FAILED -> R.string.call_failed
        MessageState.CALL_OUTGOING_MISSED -> when (endStatus) {
            CallStatus.REJECTED -> R.string.call_declined
            CallStatus.BUSY -> R.string.call_busy
            CallStatus.UNAVAILABLE -> R.string.call_history_no_answer
            else -> R.string.call_history_outgoing
        }
        MessageState.CALL_MISSED -> when (endStatus) {
            CallStatus.REJECTED -> R.string.call_history_declined
            else -> R.string.call_history_missed
        }
        else -> R.string.call_generic
    },
)
