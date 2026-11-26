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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sotto.app.R
import com.sotto.app.domain.model.GroupCopy
import com.sotto.app.domain.model.GroupCopyState
import com.sotto.app.domain.model.GroupEvent
import com.sotto.app.domain.model.GroupReaction
import com.sotto.app.domain.model.Message
import com.sotto.app.domain.model.MessageState
import com.sotto.app.ui.components.GradientAvatar
import com.sotto.app.ui.components.SoftButton
import com.sotto.app.ui.components.SystemEventPill
import com.sotto.app.ui.theme.Aurora

/**
 * What a group chat (ADR-32) adds to [ChatContent]: its header, who wrote each message, every
 * member's reactions, and what stands where the composer would when the user can't write.
 */
class GroupChatUi(
    val groupId: String,
    val title: String,
    val memberCount: Int,
    /** What each member is called on this phone, by user id. */
    val names: Map<String, String>,
    /** Every reaction, by message id. */
    val reactions: Map<String, List<GroupReaction>>,
    /** How far each of our messages got, by message id. */
    val progress: Map<String, com.sotto.app.domain.model.GroupProgress> = emptyMap(),
    /** By message id, the members who read up to it: their circles sit under it. */
    val readUpTo: Map<String, List<String>> = emptyMap(),
    val canSend: Boolean,
    /** Shown above the composer, or null. */
    val notice: (@Composable () -> Unit)?,
    /** What the composer says when the user can't write. */
    val composerHint: String?,
    val onOpenDetails: () -> Unit,
    /** Our message tapped: who has it. */
    val onMessageInfo: (Message) -> Unit,
) {
    fun nameOf(userId: String): String = names[userId] ?: userId.take(8)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupTopBar(group: GroupChatUi, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            },
            title = {
                // No call button: a group has no calls (ADR-32). The header opens the group's details.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(onClickLabel = stringResource(R.string.group_details), onClick = group.onOpenDetails),
                ) {
                    GradientAvatar(name = group.title, seed = group.groupId, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(group.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Groups, contentDescription = null, modifier = Modifier.size(12.dp), tint = colors.tertiary)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                pluralStringResource(R.plurals.group_member_count, group.memberCount, group.memberCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            },
            actions = {
                IconButton(onClick = group.onOpenDetails) {
                    Icon(Icons.Rounded.Info, contentDescription = stringResource(R.string.group_details))
                }
                Spacer(Modifier.width(4.dp))
            },
        )
        HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant.copy(alpha = 0.7f))
    }
}

/** A disappearing-messages timer in words ("1 hour"), or "off". */
@Composable
internal fun timerLabel(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        ms <= 0 -> stringResource(R.string.common_off)
        minutes < 60 -> pluralStringResource(R.plurals.settings_minutes, minutes.toInt(), minutes.toInt())
        minutes < 24 * 60 -> (minutes / 60).toInt().let { pluralStringResource(R.plurals.settings_hours, it, it) }
        else -> (minutes / (24 * 60)).toInt().let { pluralStringResource(R.plurals.settings_days, it, it) }
    }
}

/** What a [GroupEvent] says, in the user's language. */
@Composable
internal fun groupEventText(event: GroupEvent): String {
    val names = event.names.map { it.ifBlank { stringResource(R.string.chat_you) } }
    val who = names.joinToString(", ")
    return when (event.type) {
        GroupEvent.CREATED -> stringResource(R.string.group_event_created)
        GroupEvent.JOINED -> stringResource(R.string.group_event_joined, who)
        GroupEvent.INVITED -> stringResource(R.string.group_event_invited, who)
        GroupEvent.LEFT -> stringResource(R.string.group_event_left, who)
        GroupEvent.REMOVED -> stringResource(R.string.group_event_removed, who)
        GroupEvent.DECLINED -> stringResource(R.string.group_event_declined, who)
        GroupEvent.TITLE -> if (event.names.firstOrNull().isNullOrBlank()) {
            stringResource(R.string.group_event_title_you, event.value)
        } else {
            stringResource(R.string.group_event_title, who, event.value)
        }
        GroupEvent.TIMER -> if (event.names.firstOrNull().isNullOrBlank()) {
            stringResource(R.string.group_event_timer_you, timerLabel(event.value.toLongOrNull() ?: 0))
        } else {
            stringResource(R.string.group_event_timer, who, timerLabel(event.value.toLongOrNull() ?: 0))
        }
        GroupEvent.ADMIN -> if (names.firstOrNull() == stringResource(R.string.chat_you)) {
            stringResource(R.string.group_event_admin_you)
        } else {
            stringResource(R.string.group_event_admin, who)
        }
        GroupEvent.ENDED -> stringResource(R.string.group_event_ended)
        GroupEvent.YOU_REMOVED -> stringResource(R.string.group_event_you_removed)
        GroupEvent.YOU_LEFT -> stringResource(R.string.group_event_you_left)
        GroupEvent.YOU_JOINED -> stringResource(R.string.group_event_you_joined)
        GroupEvent.FROZEN -> stringResource(R.string.group_event_frozen)
        GroupEvent.KEYS_MISMATCH -> stringResource(R.string.group_event_keys_mismatch, who)
        else -> stringResource(R.string.group_event_unknown)
    }
}

@Composable
internal fun GroupEventItem(message: Message, timestamp: String, modifier: Modifier = Modifier) {
    val event = GroupEvent.decode(message.plaintext) ?: return
    val colors = MaterialTheme.colorScheme
    val (icon, tint) = when (event.type) {
        GroupEvent.KEYS_MISMATCH -> Icons.Rounded.Key to colors.error
        GroupEvent.TIMER -> Icons.Rounded.Timer to colors.tertiary
        GroupEvent.ENDED, GroupEvent.YOU_REMOVED, GroupEvent.FROZEN -> Icons.Rounded.Block to colors.error
        else -> Icons.Rounded.Groups to colors.tertiary
    }
    SystemEventPill(icon = icon, iconTint = tint, text = groupEventText(event) + " · " + timestamp, modifier = modifier)
}

/**
 * Who has our group message: each member's copy, sent, delivered, read or not sent, and a
 * button that sends only the copies that failed again. Removing a member doesn't reach a phone
 * that's offline until it's back (ADR-32), so this is about delivery, not about who may read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageInfoSheet(copies: List<GroupCopy>, onRetry: (() -> Unit)?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.group_message_info), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(12.dp))
            if (copies.isEmpty()) {
                Text(
                    stringResource(R.string.group_message_info_nobody),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(copies, key = { it.recipientId }) { copy -> CopyRow(copy) }
            }
            if (onRetry != null && copies.any { it.state == GroupCopyState.FAILED }) {
                Spacer(Modifier.size(16.dp))
                SoftButton(text = stringResource(R.string.group_message_retry_failed), onClick = onRetry, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun CopyRow(copy: GroupCopy) {
    val colors = MaterialTheme.colorScheme
    val (icon, tint, label) = when (copy.state) {
        GroupCopyState.SENDING -> Triple(Icons.Rounded.Schedule, colors.onSurfaceVariant, stringResource(R.string.chat_state_sending))
        GroupCopyState.SENT -> Triple(Icons.Rounded.Done, colors.onSurfaceVariant, stringResource(R.string.chat_state_sent))
        GroupCopyState.DELIVERED -> Triple(Icons.Rounded.DoneAll, colors.onSurfaceVariant, stringResource(R.string.chat_state_delivered))
        GroupCopyState.READ -> Triple(Icons.Rounded.DoneAll, Aurora.colors.readTick, stringResource(R.string.chat_state_seen))
        GroupCopyState.SKIPPED -> Triple(Icons.Rounded.Block, colors.onSurfaceVariant, stringResource(R.string.group_copy_skipped))
        GroupCopyState.FAILED -> Triple(
            Icons.Rounded.ErrorOutline, colors.error,
            stringResource(
                when (copy.error) {
                    com.sotto.app.data.repository.GroupRepositoryImpl.ERROR_KEYS -> R.string.group_copy_failed_keys
                    com.sotto.app.data.repository.GroupRepositoryImpl.ERROR_NO_KEYS -> R.string.group_copy_failed_no_keys
                    else -> R.string.group_copy_failed
                },
            ),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        GradientAvatar(name = copy.recipientName, seed = copy.recipientId, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Text(copy.recipientName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/**
 * The ticks on our group message, with how far it got while it's partway: [state] is all-or-
 * nothing (delivered only when every member's phone has it), which looks stuck while one phone
 * sleeps. Partway through delivery: double ticks with "delivered/total". Who read it isn't in the
 * ticks (blue only once everyone has): the members' read circles show it ([ReadCircles]).
 */
internal fun groupTicks(state: MessageState, progress: com.sotto.app.domain.model.GroupProgress?): Pair<MessageState, String?> {
    progress ?: return state to null
    val total = progress.total
    return when {
        total >= 2 && state == MessageState.SENT && progress.delivered in 1 until total -> MessageState.DELIVERED to "${progress.delivered}/$total"
        else -> state to null
    }
}

/**
 * Small circles with the initials of the members who read up to this message of ours, under it
 * (as in Messenger): each member's sits under the latest one they read.
 */
@Composable
internal fun ReadCircles(readers: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    if (readers.isEmpty()) return
    val shown = readers.take(MAX_READ_CIRCLES)
    // One label for the circles together, for screen readers ("Read by Bjorn, Cecilia").
    val label = androidx.compose.ui.res.stringResource(R.string.group_read_by, readers.joinToString(", ") { it.first })
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .padding(top = 3.dp, end = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy((-4).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        shown.forEach { (name, id) ->
            GradientAvatar(
                name = name,
                seed = id,
                size = 16.dp,
                modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.background, androidx.compose.foundation.shape.CircleShape),
            )
        }
        if (readers.size > shown.size) {
            Text(
                "+${readers.size - shown.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

private const val MAX_READ_CIRCLES = 5
