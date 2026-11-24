package com.sotto.app.ui.conversations

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
import androidx.compose.material.icons.rounded.Edit
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
import androidx.compose.runtime.remember
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
import com.sotto.app.ui.components.AuroraBackground
import com.sotto.app.ui.components.AuroraStyle
import com.sotto.app.ui.components.CollapsingTitleTopBar
import com.sotto.app.ui.components.ConversationItem
import com.sotto.app.ui.components.EmptyState
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
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val conversations by viewModel.conversations.collectAsState()
    val messageRequests by viewModel.messageRequests.collectAsState()
    val trust by viewModel.trust.collectAsState()
    val pollLifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(pollLifecycleOwner) {
        pollLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.pollWhileVisible()
        }
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
    )
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
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val isEmpty = conversations.isEmpty() && messageRequests.isEmpty()
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
                        if (conversations.isNotEmpty()) {
                            item(key = "h:chats") {
                                SectionLabel(stringResource(R.string.conversations_chats), modifier = Modifier.animateItem())
                            }
                        }
                    }
                    items(conversations, key = { "c:${it.id}" }) { conversation ->
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
                }
            }
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
