package com.sotto.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sotto.app.R
import com.sotto.app.domain.model.GroupStatus
import com.sotto.app.domain.model.MessageState
import com.sotto.app.ui.components.GradientButton
import com.sotto.app.ui.components.SoftButton
import com.sotto.app.ui.text.UiText
import com.sotto.app.ui.text.resolve
import com.sotto.app.ui.theme.Aurora

/** A group chat (ADR-32): [ChatContent] with the group's header, senders and notices; no calls. */
@Composable
fun GroupChatScreen(
    onBack: () -> Unit,
    onOpenDetails: (groupId: String) -> Unit,
    viewModel: GroupChatViewModel = hiltViewModel(),
) {
    val group by viewModel.group.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val members by viewModel.members.collectAsState()
    val reactions by viewModel.reactions.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val composerText by viewModel.composerText.collectAsState()
    val replyingTo by viewModel.replyingTo.collectAsState()
    val error by viewModel.error.collectAsState()
    val fileProgress by viewModel.fileProgress.collectAsState()
    val infoFor by viewModel.infoFor.collectAsState()
    val infoCopies by viewModel.infoCopies.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }

    var saving by remember { mutableStateOf<ChatViewModel.OpenedFile?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target ->
        val file = saving
        saving = null
        if (target != null && file != null) viewModel.saveCopy(file, target)
    }
    LaunchedEffect(Unit) {
        viewModel.openFile.collect { opened ->
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", opened.file)
            val view = android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, opened.mime)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context.packageManager.queryIntentActivities(view, 0).isNotEmpty()) {
                try {
                    context.startActivity(android.content.Intent.createChooser(view, context.getString(R.string.chat_open_with)))
                } catch (_: android.content.ActivityNotFoundException) {
                    viewModel.reportError(UiText.res(R.string.chat_error_no_app_for_file))
                }
            } else {
                saving = opened
                saveLauncher.launch(opened.file.name)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onScreenResumed()
                Lifecycle.Event.ON_PAUSE -> viewModel.onScreenPaused()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenPaused()
        }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.pollWhileVisible() }
    }
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it.resolve(context))
            viewModel.clearError()
        }
    }

    val status = group?.status
    val ui = GroupChatUi(
        groupId = viewModel.groupId,
        title = group?.title ?: stringResource(R.string.group_title_fallback),
        memberCount = group?.memberCount ?: 0,
        names = members.associate { it.userId to it.name },
        reactions = reactions,
        progress = progress,
        canSend = status == GroupStatus.ACTIVE,
        notice = group?.let { g -> { GroupNotice(g, onAccept = viewModel::accept, onDecline = { viewModel.decline(onBack) }) } },
        composerHint = when (status) {
            GroupStatus.INVITED -> stringResource(R.string.group_composer_invited)
            GroupStatus.JOINING -> stringResource(R.string.group_composer_joining)
            GroupStatus.LEFT, GroupStatus.REMOVED, GroupStatus.REJECTED -> stringResource(R.string.group_composer_not_member)
            GroupStatus.ENDED -> stringResource(R.string.group_composer_ended)
            else -> null
        },
        onOpenDetails = { onOpenDetails(viewModel.groupId) },
        onMessageInfo = viewModel::showInfo,
    )

    ChatContent(
        contactName = ui.title,
        avatarSeed = viewModel.groupId,
        isEncrypted = true,
        encryptionInfo = "",
        messages = messages.orEmpty(),
        historyLoaded = messages != null,
        composerText = composerText,
        needsSessionReset = false,
        peerDeleted = false,
        isReEstablishing = false,
        isMessageRequest = false,
        isBlocked = false,
        snackbarHostState = snackbarHostState,
        onBack = { if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED) onBack() },
        onCall = {},
        onComposerChange = viewModel::updateComposer,
        onSend = viewModel::send,
        onRetry = viewModel::retry,
        onReEstablishSession = {},
        onAcceptRequest = {},
        onRejectRequest = {},
        onBlock = {},
        onUnblock = {},
        onRename = {},
        onDelete = {},
        onRemoveContact = {},
        replyingTo = replyingTo,
        onReply = viewModel::startReply,
        onCancelReply = viewModel::cancelReply,
        onReact = viewModel::react,
        onSendVoice = viewModel::sendVoice,
        onSendPhoto = { uri, hd, caption -> viewModel.sendPhoto(uri, hd, caption) },
        loadHd = viewModel::loadHd,
        onSendFile = viewModel::sendFile,
        checkMediaAllowed = { status == GroupStatus.ACTIVE },
        onFileTap = viewModel::onFileTap,
        fileProgress = fileProgress,
        loadMedia = viewModel::loadMedia,
        group = ui,
    )

    if (infoFor != null) {
        val message = messages?.firstOrNull { it.id == infoFor }
        MessageInfoSheet(
            copies = infoCopies,
            onRetry = if (message?.state == MessageState.FAILED) ({ viewModel.retry(message.id) }) else null,
            onDismiss = viewModel::hideInfo,
        )
    }
}

/** What stands above the composer for a group the user can't write in, or whose admin is gone. */
@Composable
private fun GroupNotice(group: com.sotto.app.domain.model.Group, onAccept: () -> Unit, onDecline: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    when (group.status) {
        GroupStatus.INVITED -> NoticeCard(
            icon = Icons.Rounded.GroupAdd,
            tint = colors.primary,
            title = stringResource(R.string.group_invitation_title, group.invitedByName ?: stringResource(R.string.chat_them)),
            body = stringResource(R.string.group_invitation_body),
        ) {
            SoftButton(text = stringResource(R.string.group_invitation_decline), onClick = onDecline, compact = true, modifier = Modifier.weight(1f))
            GradientButton(text = stringResource(R.string.group_invitation_accept), onClick = onAccept, compact = true, modifier = Modifier.weight(1f))
        }
        GroupStatus.JOINING -> NoticeCard(
            icon = Icons.Rounded.HourglassTop,
            tint = colors.primary,
            title = stringResource(R.string.group_status_joining),
            body = stringResource(R.string.group_joining_body),
            loading = true,
        )
        GroupStatus.REMOVED, GroupStatus.LEFT, GroupStatus.ENDED, GroupStatus.REJECTED -> NoticeCard(
            icon = Icons.Rounded.Block,
            tint = colors.error,
            title = stringResource(
                when (group.status) {
                    GroupStatus.REMOVED -> R.string.group_status_removed
                    GroupStatus.ENDED -> R.string.group_status_ended
                    else -> R.string.group_status_left
                },
            ),
            body = stringResource(R.string.group_not_member_body),
        )
        GroupStatus.ACTIVE -> if (group.frozen) {
            NoticeCard(
                icon = Icons.Rounded.Lock,
                tint = Aurora.colors.warning,
                title = stringResource(R.string.group_frozen_title),
                body = stringResource(R.string.group_frozen_body),
            )
        }
    }
}
