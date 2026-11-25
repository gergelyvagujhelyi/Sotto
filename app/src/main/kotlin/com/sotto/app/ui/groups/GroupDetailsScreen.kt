package com.sotto.app.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sotto.app.R
import com.sotto.app.domain.model.DisappearingDuration
import com.sotto.app.domain.model.Group
import com.sotto.app.domain.model.GroupCopy
import com.sotto.app.domain.model.GroupKeyState
import com.sotto.app.domain.model.GroupMember
import com.sotto.app.domain.model.GroupMemberStatus
import com.sotto.app.domain.model.GroupStatus
import com.sotto.app.domain.model.GroupVouch
import com.sotto.app.domain.repository.GroupRepository
import com.sotto.app.ui.chat.CopyRow
import com.sotto.app.ui.chat.timerLabel
import com.sotto.app.ui.components.AppSnackbarHost
import com.sotto.app.ui.components.AuroraBackground
import com.sotto.app.ui.components.AuroraStyle
import com.sotto.app.ui.components.AuroraTextField
import com.sotto.app.ui.components.GlassCard
import com.sotto.app.ui.components.GradientAvatar
import com.sotto.app.ui.components.GradientButton
import com.sotto.app.ui.components.SectionLabel
import com.sotto.app.ui.components.SoftButton
import com.sotto.app.ui.components.TrustIcon
import com.sotto.app.ui.components.trustLabel
import com.sotto.app.ui.text.UiText
import com.sotto.app.ui.text.resolve
import com.sotto.app.ui.theme.Aurora
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GroupDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groups: GroupRepository,
) : ViewModel() {
    val groupId: String = savedStateHandle["groupId"] ?: ""

    val group: StateFlow<Group?> = groups.observeGroup(groupId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val members: StateFlow<List<GroupMember>> = groups.observeMembers(groupId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val rosterDelivery: StateFlow<List<GroupCopy>> =
        groups.observeRosterDelivery(groupId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _error = MutableStateFlow<UiText?>(null)
    val error: StateFlow<UiText?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    private fun run(action: suspend () -> Unit, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                action()
                onDone()
            } catch (e: Exception) {
                _error.value = UiText.res(R.string.group_error_change)
            }
        }
    }

    fun rename(title: String) = run({ groups.rename(groupId, title) })
    fun setTimer(ms: Long) = run({ groups.setTimer(groupId, ms) })
    fun remove(userId: String) = run({ groups.remove(groupId, userId) })
    fun makeAdmin(userId: String) = run({ groups.makeAdmin(groupId, userId) })
    fun accept() = run({ groups.acceptInvitation(groupId) })
    fun decline(onDone: () -> Unit) = run({ groups.rejectInvitation(groupId) }, onDone)
    fun leave() = run({ groups.leave(groupId) })
    fun deleteGroup() = run({ groups.deleteGroup(groupId) })
    fun deleteLocally(onDone: () -> Unit) = run({ groups.deleteLocally(groupId) }, onDone)
}

private sealed interface Confirm {
    data class Remove(val member: GroupMember) : Confirm
    data class MakeAdmin(val member: GroupMember) : Confirm
    data object Leave : Confirm
    data object DeleteGroup : Confirm
    data object DeleteLocally : Confirm
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GroupDetailsScreen(
    onBack: () -> Unit,
    onAddMembers: (groupId: String) -> Unit,
    onOpenChat: (groupId: String) -> Unit,
    onGone: () -> Unit,
    viewModel: GroupDetailsViewModel = hiltViewModel(),
) {
    val group by viewModel.group.collectAsState()
    val members by viewModel.members.collectAsState()
    val delivery by viewModel.rosterDelivery.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it.resolve(context))
            viewModel.clearError()
        }
    }
    val g = group
    val canChange = g != null && g.isAdmin && !g.frozen && g.status == GroupStatus.ACTIVE

    AuroraBackground(Modifier.fillMaxSize(), style = AuroraStyle.Subtle) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { AppSnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    title = { Text(stringResource(R.string.group_details)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    },
                )
            },
        ) { padding ->
            if (g == null) return@Scaffold
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                item(key = "header") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        GradientAvatar(name = g.title, seed = g.id, size = 72.dp)
                        Spacer(Modifier.size(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(g.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (canChange) {
                                IconButton(onClick = { renaming = true }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.group_rename))
                                }
                            }
                        }
                        Text(
                            pluralStringResource(R.plurals.group_member_count, g.memberCount, g.memberCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (g.status == GroupStatus.INVITED) {
                            Spacer(Modifier.size(12.dp))
                            Text(
                                stringResource(R.string.group_invitation_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                SoftButton(stringResource(R.string.group_invitation_decline), onClick = { viewModel.decline(onGone) }, modifier = Modifier.weight(1f))
                                GradientButton(stringResource(R.string.group_invitation_accept), onClick = {
                                    viewModel.accept()
                                    onOpenChat(g.id)
                                }, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (g.frozen) {
                    item(key = "frozen") {
                        InfoCard(Icons.Rounded.Info, stringResource(R.string.group_frozen_title), stringResource(R.string.group_frozen_body))
                    }
                }
                item(key = "timer") {
                    SectionLabel(stringResource(R.string.group_timer))
                    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(10.dp))
                            Text(timerLabel(g.timerMs), style = MaterialTheme.typography.bodyLarge)
                        }
                        Text(
                            stringResource(if (canChange) R.string.group_timer_admin else R.string.group_timer_member),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        if (canChange) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                DisappearingDuration.entries.forEach { d ->
                                    val ms = d.millis ?: 0L
                                    FilterChip(selected = g.timerMs == ms, onClick = { viewModel.setTimer(ms) }, label = { Text(timerLabel(ms)) })
                                }
                            }
                        }
                    }
                }
                item(key = "members-label") {
                    SectionLabel(
                        stringResource(R.string.group_members),
                        trailing = if (canChange && members.size < com.sotto.app.group.GroupRoster.MAX_MEMBERS) {
                            {
                                TextButton(onClick = { onAddMembers(g.id) }) {
                                    Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.group_add_members))
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
                items(members, key = { "m:${it.userId}" }) { m ->
                    MemberRow(
                        member = m,
                        canChange = canChange && !m.isMe,
                        onRemove = { confirm = Confirm.Remove(m) },
                        onMakeAdmin = { confirm = Confirm.MakeAdmin(m) },
                    )
                }
                item(key = "trust-note") {
                    Text(
                        stringResource(R.string.group_vouch_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                if (g.isAdmin && delivery.isNotEmpty()) {
                    item(key = "delivery-label") { SectionLabel(stringResource(R.string.group_roster_delivery)) }
                    item(key = "delivery") {
                        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                delivery.forEach { CopyRow(it) }
                            }
                        }
                    }
                }
                item(key = "removal-note") {
                    InfoCard(Icons.Rounded.HourglassTop, stringResource(R.string.group_offline_title), stringResource(R.string.group_offline_body))
                }
                item(key = "no-calls") {
                    Text(
                        stringResource(R.string.group_no_calls),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
                item(key = "actions") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .navigationBarsPadding(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when (g.status) {
                            GroupStatus.ACTIVE, GroupStatus.JOINING -> {
                                SoftButton(
                                    stringResource(R.string.group_leave),
                                    icon = Icons.AutoMirrored.Rounded.ExitToApp,
                                    onClick = { confirm = Confirm.Leave },
                                    tone = com.sotto.app.ui.components.SoftButtonTone.Danger,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (canChange) {
                                    SoftButton(
                                        stringResource(R.string.group_delete),
                                        icon = Icons.Rounded.Delete,
                                        onClick = { confirm = Confirm.DeleteGroup },
                                        tone = com.sotto.app.ui.components.SoftButtonTone.Danger,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            GroupStatus.INVITED -> Unit
                            else -> SoftButton(
                                stringResource(R.string.group_delete_chat),
                                icon = Icons.Rounded.Delete,
                                onClick = { confirm = Confirm.DeleteLocally },
                                tone = com.sotto.app.ui.components.SoftButtonTone.Danger,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    if (renaming && g != null) {
        var text by rememberSaveable { mutableStateOf(g.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.group_rename)) },
            text = {
                AuroraTextField(value = text, onValueChange = { text = it.take(com.sotto.app.group.GroupRoster.MAX_TITLE) }, placeholder = stringResource(R.string.group_name_hint), singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    viewModel.rename(text)
                }, enabled = text.isNotBlank()) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    confirm?.let { c ->
        val (title, body, label) = when (c) {
            is Confirm.Remove -> Triple(stringResource(R.string.group_remove_title, c.member.name), stringResource(R.string.group_remove_body), stringResource(R.string.group_remove))
            is Confirm.MakeAdmin -> Triple(stringResource(R.string.group_make_admin_title, c.member.name), stringResource(R.string.group_make_admin_body), stringResource(R.string.group_make_admin))
            Confirm.Leave -> Triple(
                stringResource(R.string.group_leave_title),
                stringResource(if (g?.isAdmin == true) R.string.group_leave_body_admin else R.string.group_leave_body),
                stringResource(R.string.group_leave),
            )
            Confirm.DeleteGroup -> Triple(stringResource(R.string.group_delete_title), stringResource(R.string.group_delete_body), stringResource(R.string.group_delete))
            Confirm.DeleteLocally -> Triple(stringResource(R.string.group_delete_chat_title), stringResource(R.string.group_delete_chat_body), stringResource(R.string.group_delete_chat))
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    when (c) {
                        is Confirm.Remove -> viewModel.remove(c.member.userId)
                        is Confirm.MakeAdmin -> viewModel.makeAdmin(c.member.userId)
                        Confirm.Leave -> viewModel.leave()
                        Confirm.DeleteGroup -> viewModel.deleteGroup()
                        Confirm.DeleteLocally -> viewModel.deleteLocally(onGone)
                    }
                }) { Text(label, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun InfoCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** How the group's admin verified a member, in words. */
@Composable
private fun vouchLabel(vouch: GroupVouch): String = stringResource(
    when (vouch) {
        GroupVouch.IN_PERSON -> R.string.group_vouch_in_person
        GroupVouch.BY_PHONE -> R.string.group_vouch_by_phone
        GroupVouch.SHARE_CODE -> R.string.group_vouch_share_code
        GroupVouch.NONE -> R.string.group_vouch_none
    },
)

@Composable
private fun MemberRow(member: GroupMember, canChange: Boolean, onRemove: () -> Unit, onMakeAdmin: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canChange) Modifier.clickable { menu = true } else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GradientAvatar(name = member.name, seed = member.userId, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (member.isMe) stringResource(R.string.group_member_you, member.name) else member.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (member.isAdmin) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.Star, contentDescription = null, tint = Aurora.colors.warning, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.group_admin), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            // The user's own contact: how the user verified them. Anyone else: the admin's vouch.
            val line = when {
                member.isMe -> null
                member.keyState == GroupKeyState.MISMATCH -> stringResource(R.string.group_member_keys_mismatch)
                member.status == GroupMemberStatus.INVITED -> stringResource(R.string.group_member_invited)
                member.status == GroupMemberStatus.GONE -> stringResource(R.string.group_member_gone)
                member.contactTrust != null -> trustLabel(member.contactTrust)
                else -> vouchLabel(member.vouchedTrust)
            }
            if (line != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        member.keyState == GroupKeyState.MISMATCH ->
                            Icon(Icons.Rounded.GppBad, contentDescription = null, tint = colors.error, modifier = Modifier.size(12.dp))
                        member.contactTrust != null -> TrustIcon(member.contactTrust, size = 12.dp)
                        else -> Unit
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (member.keyState == GroupKeyState.MISMATCH) colors.error else colors.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }
        if (canChange) {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.chat_more_options))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (member.status == GroupMemberStatus.ACTIVE && !member.isAdmin) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.group_make_admin)) },
                        leadingIcon = { Icon(Icons.Rounded.Star, contentDescription = null) },
                        onClick = {
                            menu = false
                            onMakeAdmin()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_remove)) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = colors.error) },
                    onClick = {
                        menu = false
                        onRemove()
                    },
                )
            }
        }
    }
}
