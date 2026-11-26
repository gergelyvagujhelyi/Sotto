package com.sotto.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sotto.app.R
import com.sotto.app.ui.components.GradientAvatar
import com.sotto.app.ui.components.SoftButton

/** One reaction in [ReactionsSheet]: who ([seed] picks their avatar colours) and which emoji. */
data class ReactionEntry(val name: String, val seed: String, val emoji: String, val isMine: Boolean)

/** Everyone's reactions, the user's first, then the most given emoji first (as WhatsApp lists them). */
internal fun sortedReactions(entries: List<ReactionEntry>): List<ReactionEntry> {
    val counts = entries.groupingBy { it.emoji }.eachCount()
    return entries.sortedWith(
        compareByDescending<ReactionEntry> { it.isMine }
            .thenByDescending { counts[it.emoji] ?: 0 }
            .thenBy { it.emoji }
            .thenBy { it.name.lowercase() },
    )
}

/**
 * Who reacted to a message, opened by tapping the reactions under it: each person with their
 * emoji. The user's own row takes it back when tapped; [onChange] opens the emoji picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReactionsSheet(
    entries: List<ReactionEntry>,
    onRemoveMine: (() -> Unit)?,
    onChange: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.chat_reactions_title, entries.size), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(sortedReactions(entries), key = { "${it.isMine}:${it.seed}" }) { entry ->
                    val remove = if (entry.isMine) onRemoveMine else null
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (remove != null) {
                                    Modifier.clickable(onClickLabel = stringResource(R.string.chat_reactions_remove), onClick = remove)
                                } else {
                                    Modifier
                                },
                            )
                            .padding(vertical = 6.dp),
                    ) {
                        GradientAvatar(name = entry.name, seed = entry.seed, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (entry.isMine) stringResource(R.string.chat_reactions_you) else entry.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (remove != null) {
                                Text(
                                    stringResource(R.string.chat_reactions_tap_to_remove),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                        Text(entry.emoji, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
            if (onChange != null) {
                Spacer(Modifier.size(12.dp))
                SoftButton(
                    text = stringResource(
                        if (entries.any { it.isMine }) R.string.chat_reaction_change else R.string.chat_reactions_add,
                    ),
                    onClick = onChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.size(20.dp))
        }
    }
}
