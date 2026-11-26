package com.sotto.app.ui.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sotto.app.R
import com.sotto.app.domain.model.Conversation
import com.sotto.app.domain.model.Group
import com.sotto.app.ui.components.AuroraBackground
import com.sotto.app.ui.components.AuroraStyle
import com.sotto.app.ui.components.CollapsingTitleTopBar
import com.sotto.app.ui.components.ConversationItem
import com.sotto.app.ui.components.EmptyState
import com.sotto.app.ui.components.BackgroundActivity
import com.sotto.app.ui.components.GlassCard
import com.sotto.app.ui.components.GlassIconButton
import com.sotto.app.ui.components.GradientAvatar
import com.sotto.app.ui.components.GradientFab
import com.sotto.app.ui.components.MessageRequestActions
import com.sotto.app.ui.components.SectionLabel
import com.sotto.app.ui.components.TagChip
import com.sotto.app.ui.components.listTimestamp
import com.sotto.app.ui.components.rememberDateFormats
import com.sotto.app.ui.components.rememberToday
import com.sotto.app.ui.theme.Aurora

@Composable
fun ConversationsScreen(
    onConversationClick: (conversationId: String, contactId: String) -> Unit,
    onAddContact: () -> Unit,
    onSettings: () -> Unit,
    onGroupClick: (groupId: String) -> Unit = {},
    onNewGroup: () -> Unit = {},
    onInvitationClick: (groupId: String) -> Unit = {},
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val conversations by viewModel.conversations.collectAsState()
    val messageRequests by viewModel.messageRequests.collectAsState()
    val trust by viewModel.trust.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val invitations by viewModel.groupInvitations.collectAsState()
    val pollLifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(pollLifecycleOwner) {
        pollLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.pollWhileVisible()
        }
    }
    // Offered until the user lets Sotto run in the background or says "Not now"; re-checked on
    // coming back from Android's dialog.
    val context = LocalContext.current
    val offerDismissed by viewModel.backgroundOfferDismissed.collectAsState()
    var restricted by remember { mutableStateOf(BackgroundActivity.isRestricted(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        restricted = BackgroundActivity.isRestricted(context)
        onPauseOrDispose { }
    }

    ConversationsContent(
        conversations = conversations,
        messageRequests = messageRequests,
        onConversationClick = onConversationClick,
        onAddContact = onAddContact,
        onSettings = onSettings,
        onAcceptRequest = viewModel::acceptMessageRequest,
        onRejectRequest = viewModel::rejectMessageRequest,
        onBlockRequest = { request -> viewModel.blockRequest(request.contactId, request.contactDisplayName) },
        trust = trust,
        groups = groups,
        groupInvitations = invitations,
        onGroupClick = onGroupClick,
        onNewGroup = onNewGroup,
        onInvitationClick = onInvitationClick,
        onAcceptInvitation = viewModel::acceptInvitation,
        onRejectInvitation = viewModel::rejectInvitation,
        offerBackground = restricted && offerDismissed == false,
        onAllowBackground = { BackgroundActivity.requestUnrestricted(context) },
        onDismissBackgroundOffer = viewModel::dismissBackgroundOffer,
    )
}

/** A row of the chat list: a one-to-one chat or a group. */
private sealed interface ChatListEntry {
    val key: String
    val time: kotlinx.datetime.Instant?

    data class Direct(val conversation: Conversation) : ChatListEntry {
        override val key get() = "c:${conversation.id}"
        override val time get() = conversation.lastMessageTimestamp
    }

    data class InGroup(val group: Group) : ChatListEntry {
        override val key get() = "g:${group.id}"
        override val time get() = group.sortTime
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsContent(
    conversations: List<Conversation>,
    messageRequests: List<Conversation>,
    onConversationClick: (conversationId: String, contactId: String) -> Unit,
    onAddContact: () -> Unit,
    onSettings: () -> Unit,
    onAcceptRequest: (conversationId: String) -> Unit,
    onRejectRequest: (conversationId: String) -> Unit,
    onBlockRequest: (request: Conversation) -> Unit,
    /** Each contact's verification, by user id; a chat whose contact isn't listed shows the lock. */
    trust: Map<String, com.sotto.app.domain.model.ContactTrust> = emptyMap(),
    groups: List<Group> = emptyList(),
    groupInvitations: List<Group> = emptyList(),
    onGroupClick: (groupId: String) -> Unit = {},
    /** Null: no "new group" button (design previews). */
    onNewGroup: (() -> Unit)? = null,
    onInvitationClick: (groupId: String) -> Unit = {},
    onAcceptInvitation: (groupId: String) -> Unit = {},
    onRejectInvitation: (groupId: String) -> Unit = {},
    /** Android may hold Sotto back in the background: offer to let it run there. */
    offerBackground: Boolean = false,
    onAllowBackground: () -> Unit = {},
    onDismissBackgroundOffer: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val isEmpty = conversations.isEmpty() && messageRequests.isEmpty() && groups.isEmpty() && groupInvitations.isEmpty()
    // Chats and groups in one list, newest first.
    val entries = remember(conversations, groups) {
        (conversations.map { ChatListEntry.Direct(it) } + groups.map { ChatListEntry.InGroup(it) })
            .sortedByDescending { it.time?.toEpochMilliseconds() ?: 0L }
    }
    val today = rememberToday()
    val dates = rememberDateFormats()

    AuroraBackground(Modifier.fillMaxSize(), style = AuroraStyle.Header) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                CollapsingTitleTopBar(
                    title = stringResource(R.string.conversations_title),
                    scrollBehavior = scrollBehavior,
                    actions = {
                        if (onNewGroup != null) {
                            GlassIconButton(Icons.Rounded.GroupAdd, contentDescription = stringResource(R.string.group_new), onClick = onNewGroup)
                            Spacer(Modifier.width(8.dp))
                        }
                        GlassIconButton(Icons.Rounded.Settings, contentDescription = stringResource(R.string.common_settings), onClick = onSettings)
                        Spacer(Modifier.width(8.dp))
                    },
                )
            },
            floatingActionButton = {
                // Always shown: it is the only way to add a contact, and the empty
                // state may be scrolled or squeezed (landscape, split screen).
                GradientFab(
                    text = stringResource(R.string.conversations_new_chat),
                    icon = Icons.Rounded.Edit,
                    expanded = fabExpanded,
                    onClick = onAddContact,
                )
            },
        ) { padding ->
            if (isEmpty) {
                Column(Modifier.padding(padding)) {
                    if (offerBackground) {
                        BackgroundOfferCard(
                            onAllow = onAllowBackground,
                            onDismiss = onDismissBackgroundOffer,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    EmptyState(
                        icon = Icons.AutoMirrored.Rounded.Chat,
                        title = stringResource(R.string.conversations_empty_title),
                        subtitle = stringResource(R.string.conversations_empty_body),
                    )
                }
            } else {
                val layoutDirection = LocalLayoutDirection.current
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = padding.calculateStartPadding(layoutDirection),
                        top = padding.calculateTopPadding(),
                        end = padding.calculateEndPadding(layoutDirection),
                        bottom = padding.calculateBottomPadding() + 96.dp,
                    ),
                ) {
                    // Keys are namespaced: conversation ids come from the relay, so a raw
                    // id must never be able to equal another row's key (duplicate keys
                    // crash the list).
                    if (offerBackground) {
                        item(key = "h:background") {
                            BackgroundOfferCard(
                                onAllow = onAllowBackground,
                                onDismiss = onDismissBackgroundOffer,
                                modifier = Modifier
                                    .animateItem()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    }
                    if (groupInvitations.isNotEmpty()) {
                        item(key = "h:invitations") {
                            SectionLabel(
                                stringResource(R.string.group_invitations),
                                modifier = Modifier.animateItem(),
                                trailing = { TagChip(groupInvitations.size.toString(), highlighted = true) },
                            )
                        }
                        items(groupInvitations, key = { "i:${it.id}" }) { invitation ->
                            GroupInvitationCard(
                                group = invitation,
                                onOpen = { onInvitationClick(invitation.id) },
                                onAccept = { onAcceptInvitation(invitation.id) },
                                onReject = { onRejectInvitation(invitation.id) },
                                modifier = Modifier
                                    .animateItem()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                        if (entries.isNotEmpty() && messageRequests.isEmpty()) {
                            item(key = "h:chats-after-invitations") {
                                SectionLabel(stringResource(R.string.conversations_chats), modifier = Modifier.animateItem())
                            }
                        }
                    }
                    if (messageRequests.isNotEmpty()) {
                        item(key = "h:requests") {
                            SectionLabel(
                                stringResource(R.string.conversations_requests),
                                modifier = Modifier.animateItem(),
                                trailing = { TagChip(messageRequests.size.toString(), highlighted = true) },
                            )
                        }
                        items(messageRequests, key = { "r:${it.id}" }) { request ->
                            MessageRequestCard(
                                displayName = request.contactDisplayName,
                                avatarSeed = request.contactId,
                                lastMessage = request.lastMessagePreview,
                                timestamp = request.lastMessageTimestamp?.let { listTimestamp(it, today, dates) },
                                onAccept = { onAcceptRequest(request.id) },
                                onReject = { onRejectRequest(request.id) },
                                onBlock = { onBlockRequest(request) },
                                modifier = Modifier
                                    .animateItem()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                        if (entries.isNotEmpty()) {
                            item(key = "h:chats") {
                                SectionLabel(stringResource(R.string.conversations_chats), modifier = Modifier.animateItem())
                            }
                        }
                    }
                    items(entries, key = { it.key }) { entry ->
                        when (entry) {
                            is ChatListEntry.Direct -> {
                                val conversation = entry.conversation
                                ConversationItem(
                                    displayName = conversation.contactDisplayName,
                                    avatarSeed = conversation.contactId,
                                    lastMessage = conversation.lastMessagePreview,
                                    timestamp = conversation.lastMessageTimestamp?.let { listTimestamp(it, today, dates) },
                                    unreadCount = conversation.unreadCount,
                                    isEncrypted = conversation.isEncrypted,
                                    trust = trust[conversation.contactId],
                                    onClick = {
                                        onConversationClick(conversation.id, conversation.contactId)
                                    },
                                    modifier = Modifier
                                        .animateItem()
                                        .padding(vertical = 1.dp),
                                )
                            }
                            is ChatListEntry.InGroup -> {
                                val group = entry.group
                                ConversationItem(
                                    displayName = group.title,
                                    avatarSeed = group.id,
                                    lastMessage = group.lastMessagePreview ?: groupStatusLine(group),
                                    timestamp = group.lastMessageTimestamp?.let { listTimestamp(it, today, dates) },
                                    unreadCount = group.unreadCount,
                                    isEncrypted = true,
                                    groupMembers = group.memberCount,
                                    onClick = { onGroupClick(group.id) },
                                    modifier = Modifier
                                        .animateItem()
                                        .padding(vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What a group without messages says in the list. */
@Composable
private fun groupStatusLine(group: Group): String = when (group.status) {
    com.sotto.app.domain.model.GroupStatus.JOINING -> stringResource(R.string.group_status_joining)
    com.sotto.app.domain.model.GroupStatus.LEFT -> stringResource(R.string.group_status_left)
    com.sotto.app.domain.model.GroupStatus.REMOVED -> stringResource(R.string.group_status_removed)
    com.sotto.app.domain.model.GroupStatus.ENDED -> stringResource(R.string.group_status_ended)
    else -> stringResource(R.string.group_status_new)
}

/**
 * Android (or the phone maker's battery saver) may hold Sotto back in the background, so
 * messages and calls wait until it's opened: offer to let it run there (Android's own dialog).
 */
@Composable
private fun BackgroundOfferCard(
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Rounded.BatteryAlert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp).size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.conversations_background_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.conversations_background_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            com.sotto.app.ui.components.SoftButton(
                text = stringResource(R.string.conversations_background_later),
                onClick = onDismiss,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            com.sotto.app.ui.components.GradientButton(
                text = stringResource(R.string.conversations_background_allow),
                onClick = onAllow,
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** An open invitation: who from, how many in it, accept or decline (or open it to see who's in). */
@Composable
private fun GroupInvitationCard(
    group: Group,
    onOpen: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClickLabel = stringResource(R.string.group_invitation_open), onClick = onOpen),
        ) {
            GradientAvatar(name = group.title, seed = group.id, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.group_invitation_from, group.invitedByName ?: stringResource(R.string.chat_them)) +
                        " · " + androidx.compose.ui.res.pluralStringResource(R.plurals.group_member_count, group.memberCount, group.memberCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            com.sotto.app.ui.components.SoftButton(
                text = stringResource(R.string.group_invitation_decline),
                onClick = onReject,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            com.sotto.app.ui.components.GradientButton(
                text = stringResource(R.string.group_invitation_accept),
                onClick = onAccept,
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MessageRequestCard(
    displayName: String,
    avatarSeed: String,
    lastMessage: String?,
    timestamp: String?,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onBlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradientAvatar(name = displayName, seed = avatarSeed, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.conversations_request_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (timestamp != null) {
                Text(
                    text = timestamp,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (lastMessage != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = lastMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(14.dp))
        MessageRequestActions(onAccept = onAccept, onReject = onReject, onBlock = onBlock)
    }
}
