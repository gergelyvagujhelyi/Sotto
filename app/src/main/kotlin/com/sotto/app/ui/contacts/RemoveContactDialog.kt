package com.sotto.app.ui.contacts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** Asks before removing [name] from the contacts, with the chat with them. */
@Composable
internal fun RemoveContactDialog(name: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.PersonRemove, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Remove ${name ?: "this contact"}?") },
        text = {
            Text(
                "They're removed from your contacts, and the chat with them is deleted from this " +
                    "phone. They aren't told. If they write to you again, it arrives as a message request."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Remove", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
