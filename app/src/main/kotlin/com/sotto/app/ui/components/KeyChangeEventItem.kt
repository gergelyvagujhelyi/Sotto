package com.sotto.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sotto.app.domain.model.MessageState

@Composable
fun KeyChangeEventItem(
    state: MessageState,
    timestamp: String,
    modifier: Modifier = Modifier,
) {
    val isAccountDeleted = state == MessageState.ACCOUNT_DELETED
    val label = when (state) {
        MessageState.KEY_ROTATED_LOCAL -> "You rotated your keys"
        MessageState.KEY_ROTATED_REMOTE -> "Peer rotated their keys"
        MessageState.ACCOUNT_DELETED -> "Peer deleted their account"
        else -> "Keys changed"
    }
    val colors = MaterialTheme.colorScheme
    SystemEventPill(
        icon = if (isAccountDeleted) Icons.Rounded.PersonOff else Icons.Rounded.Key,
        iconTint = if (isAccountDeleted) colors.error else colors.tertiary,
        iconDescription = label,
        text = "$label · $timestamp",
        modifier = modifier,
    )
}
