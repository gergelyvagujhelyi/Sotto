package com.sotto.app.ui.components

import androidx.compose.ui.res.stringResource
import com.sotto.app.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sotto.app.domain.model.MessageState

@Composable
fun CallEventItem(
    state: MessageState,
    durationSeconds: Long,
    timestamp: String,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier,
) {
    val isMissed = state == MessageState.CALL_MISSED
    val isOutgoingMissed = state == MessageState.CALL_OUTGOING_MISSED
    val isFailed = state == MessageState.CALL_FAILED
    val wasConnected = durationSeconds > 0

    val icon = when {
        isMissed -> Icons.AutoMirrored.Rounded.CallMissed
        isOutgoingMissed -> Icons.AutoMirrored.Rounded.CallMade
        isFailed -> if (isOutgoing) Icons.AutoMirrored.Rounded.CallMade else Icons.AutoMirrored.Rounded.CallReceived
        isOutgoing -> Icons.AutoMirrored.Rounded.CallMade
        else -> Icons.AutoMirrored.Rounded.CallReceived
    }

    val label = when {
        isMissed -> R.string.chat_call_missed
        isOutgoingMissed -> R.string.chat_call_unanswered
        isFailed -> R.string.chat_call_failed
        wasConnected -> R.string.chat_call_voice
        isOutgoing -> R.string.chat_call_outgoing
        else -> R.string.chat_call_incoming
    }.let { stringResource(it) }

    val durationText = if (wasConnected) {
        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        if (minutes > 0) {
            stringResource(R.string.chat_call_duration_minutes, minutes.toInt(), seconds.toInt())
        } else {
            stringResource(R.string.chat_call_duration_seconds, seconds.toInt())
        }
    } else {
        null
    }

    val missed = isMissed || isOutgoingMissed || isFailed
    val colors = MaterialTheme.colorScheme
    SystemEventPill(
        icon = icon,
        iconTint = if (missed) colors.error else colors.primary,
        iconDescription = label,
        text = buildString {
            append(label)
            if (durationText != null) {
                append(" · ")
                append(durationText)
            }
            append(" · ")
            append(timestamp)
        },
        textColor = if (missed) colors.error else colors.onSurfaceVariant,
        modifier = modifier,
    )
}
