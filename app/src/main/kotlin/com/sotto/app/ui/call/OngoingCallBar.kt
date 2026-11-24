package com.sotto.app.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sotto.app.R
import com.sotto.app.domain.model.CallDirection
import com.sotto.app.domain.model.CallState
import com.sotto.app.domain.model.CallStatus
import kotlinx.coroutines.delay

private val OngoingCallColor = Color(0xFF087F5B)
/** Amber while the call's connection is lost and being restored. */
private val ReconnectingColor = Color(0xFFB45309)

/**
 * Shown above the app while a call goes on behind it, after Back left the call screen:
 * who, how long, and a tap returns to the call. It draws under the status bar itself.
 */
@Composable
fun OngoingCallBar(call: CallState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val connectedAt = call.connectedAt
    val elapsed by produceState(0L, connectedAt) {
        while (connectedAt != null) {
            value = System.currentTimeMillis() - connectedAt
            delay(1_000 - value % 1_000)
        }
    }
    val status = when {
        call.status == CallStatus.CONNECTED && call.peerUnreachable -> stringResource(R.string.call_cant_reach, call.peerDisplayName)
        call.status == CallStatus.CONNECTED && call.reconnecting -> stringResource(R.string.call_reconnecting)
        call.status == CallStatus.CONNECTED && connectedAt != null -> formatElapsed(elapsed)
        call.direction == CallDirection.INCOMING && call.status == CallStatus.RINGING -> stringResource(R.string.call_incoming)
        else -> stringResource(R.string.call_calling)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (call.reconnecting || call.peerUnreachable) ReconnectingColor else OngoingCallColor)
            .clickable(onClickLabel = stringResource(R.string.call_return_to_call), onClick = onClick)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = call.peerDisplayName,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = "  ·  $status",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.call_tap_to_return), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

/** 0:42, 12:05, 1:02:09 */
internal fun formatElapsed(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
