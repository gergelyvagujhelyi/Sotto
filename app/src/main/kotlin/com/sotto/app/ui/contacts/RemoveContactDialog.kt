package com.sotto.app.ui.contacts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sotto.app.R

/** Asks before removing [name] from the contacts, with the chat with them. */
@Composable
internal fun RemoveContactDialog(name: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.PersonRemove, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = {
            Text(
                if (name != null) {
                    stringResource(R.string.contacts_remove_title, name)
                } else {
                    stringResource(R.string.contacts_remove_title_unnamed)
                },
            )
        },
        text = {
            Text(stringResource(R.string.contacts_remove_body))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_remove), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
