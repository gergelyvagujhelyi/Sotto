package com.sotto.app.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sotto.app.R
import com.sotto.app.domain.model.ContactTrust
import com.sotto.app.domain.model.trust
import com.sotto.app.domain.repository.ConversationRepository
import com.sotto.app.domain.repository.GroupRepository
import com.sotto.app.domain.repository.IdentityRepository
import com.sotto.app.group.GroupRoster
import com.sotto.app.ui.components.AuroraBackground
import com.sotto.app.ui.components.AuroraStyle
import com.sotto.app.ui.components.AuroraTextField
import com.sotto.app.ui.components.EmptyState
import com.sotto.app.ui.components.GradientAvatar
import com.sotto.app.ui.components.GradientButton
import com.sotto.app.ui.components.SectionLabel
import com.sotto.app.ui.components.TrustIcon
import com.sotto.app.ui.components.trustLabel
import com.sotto.app.ui.text.UiText
import com.sotto.app.ui.text.asString
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Someone who can be added to a group: an accepted contact, not blocked. */
data class GroupCandidate(val userId: String, val name: String, val trust: ContactTrust)

/**
 * Picks contacts for a new group (with a name), or, with a `groupId`, more members for one the
 * user runs. Only the user's own accepted contacts can be added: the admin vouches for each.
 */
@HiltViewModel
class NewGroupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    identityRepository: IdentityRepository,
    conversationRepository: ConversationRepository,
    private val groups: GroupRepository,
) : ViewModel() {

    /** Adding to this group, or null for a new one. */
    val groupId: String? = savedStateHandle.get<String>("groupId")?.takeIf { it.isNotBlank() && it != "new" }

    val candidates: StateFlow<List<GroupCandidate>> = combine(
        identityRepository.getContacts(),
        conversationRepository.getConversations(),
        conversationRepository.getBlockedUsers(),
        if (groupId != null) groups.observeMembers(groupId) else kotlinx.coroutines.flow.flowOf(emptyList()),
    ) { contacts, conversations, blocked, members ->
        val accepted = conversations.filter { it.isApproved }.map { it.contactId }.toSet()
        val blockedIds = blocked.map { it.userId }.toSet()
        val inGroup = members.map { it.userId }.toSet()
        contacts.filter { it.userId in accepted && it.userId !in blockedIds && it.userId !in inGroup }
            .map { GroupCandidate(it.userId, it.displayName, it.trust) }
            .sortedBy { it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<UiText?>(null)
    val error: StateFlow<UiText?> = _error.asStateFlow()

    fun setTitle(value: String) {
        _title.value = value.take(GroupRoster.MAX_TITLE)
    }

    fun toggle(userId: String) {
        val now = _selected.value
        _selected.value = if (userId in now) now - userId else if (now.size < GroupRoster.MAX_MEMBERS - 1) now + userId else now
    }

    /** Creates the group (or invites to it); [onDone] gets its id. */
    fun create(onDone: (String) -> Unit) {
        if (_busy.value) return
        val picked = _selected.value.toList()
        if (picked.isEmpty()) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val id = if (groupId != null) {
                    groups.invite(groupId, picked)
                    groupId
                } else {
                    groups.createGroup(_title.value, picked)
                }
                onDone(id)
            } catch (e: Exception) {
                _error.value = UiText.res(R.string.group_error_create)
            } finally {
                _busy.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGroupScreen(
    onBack: () -> Unit,
    onCreated: (groupId: String) -> Unit,
    viewModel: NewGroupViewModel = hiltViewModel(),
) {
    val candidates by viewModel.candidates.collectAsState()
    val title by viewModel.title.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    val adding = viewModel.groupId != null

    AuroraBackground(Modifier.fillMaxSize(), style = AuroraStyle.Subtle) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    title = { Text(stringResource(if (adding) R.string.group_add_members else R.string.group_new)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .imePadding(),
            ) {
                if (!adding) {
                    AuroraTextField(
                        value = title,
                        onValueChange = viewModel::setTitle,
                        placeholder = stringResource(R.string.group_name_hint),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.group_pick_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                if (candidates.isEmpty()) {
                    EmptyState(
                        icon = Icons.Rounded.Groups,
                        title = stringResource(R.string.group_no_contacts_title),
                        subtitle = stringResource(R.string.group_no_contacts_body),
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    SectionLabel(
                        pluralStringResource(R.plurals.group_selected_count, selected.size, selected.size),
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        items(candidates, key = { it.userId }) { c ->
                            CandidateRow(c, checked = c.userId in selected, onToggle = { viewModel.toggle(c.userId) })
                        }
                    }
                }
                error?.let {
                    Text(
                        it.asString(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                GradientButton(
                    text = stringResource(if (adding) R.string.group_invite_action else R.string.group_create_action),
                    onClick = { viewModel.create(onCreated) },
                    enabled = selected.isNotEmpty() && (adding || title.isNotBlank()) && !busy,
                    loading = busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .navigationBarsPadding(),
                )
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: GroupCandidate, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GradientAvatar(name = candidate.name, seed = candidate.userId, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(candidate.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrustIcon(candidate.trust, size = 12.dp)
                Spacer(Modifier.width(4.dp))
                Text(
                    trustLabel(candidate.trust),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(Modifier.size(4.dp))
    }
}
