package com.sotto.app.ui.components

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
    val wasConnected = durationSeconds > 0

    val icon = when {
        isMissed -> Icons.AutoMirrored.Rounded.CallMissed
        isOutgoingMissed -> Icons.AutoMirrored.Rounded.CallMade
        isOutgoing -> Icons.AutoMirrored.Rounded.CallMade
        else -> Icons.AutoMirrored.Rounded.CallReceived
    }

    val label = when {
        isMissed -> "Missed voice call"
        isOutgoingMissed -> "Unanswered call"
        wasConnected -> "Voice call"
        isOutgoing -> "Outgoing call"
        else -> "Incoming call"
    }

    val durationText = if (wasConnected) {
        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    } else {
        null
    }

    val missed = isMissed || isOutgoingMissed
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
