package com.sotto.app.ui.chat

import androidx.activity.compose.BackHandler
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import com.sotto.app.domain.model.DisplayNames
import com.sotto.app.ui.components.BubbleSurface
import com.sotto.app.ui.components.BubbleTextInsetStart
import com.sotto.app.ui.components.BubbleTextInsetTop
import com.sotto.app.ui.components.MessageRequestActions
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MarkChatUnread
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import com.sotto.app.ui.components.runScramble
import com.sotto.app.ui.contacts.RemoveContactDialog
import com.sotto.app.ui.theme.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sotto.app.domain.model.Message
import com.sotto.app.domain.model.MessageState
import com.sotto.app.domain.model.ReactionPayload
import com.sotto.app.ui.components.AppSnackbarHost
import com.sotto.app.ui.components.AuroraTextField
import com.sotto.app.ui.components.clockTime
import com.sotto.app.ui.components.dayLabel
import com.sotto.app.ui.components.glass
import com.sotto.app.ui.components.rememberToday
import kotlinx.datetime.LocalTime
import com.sotto.app.ui.components.AuroraBackground
import com.sotto.app.ui.components.AuroraStyle
import com.sotto.app.ui.components.CallEventItem
import com.sotto.app.ui.components.DaySeparator
import com.sotto.app.ui.components.GlassIconButton
import com.sotto.app.ui.components.GradientAvatar
import com.sotto.app.ui.components.GradientText
import com.sotto.app.ui.components.KeyChangeEventItem
import com.sotto.app.ui.components.BubbleReactions
import com.sotto.app.ui.components.MessageBubble
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.sotto.app.ui.components.SoftButton
import com.sotto.app.ui.components.blockTouches
import com.sotto.app.ui.components.isExiting
import com.sotto.app.ui.theme.Aurora
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlin.time.Duration.Companion.minutes

@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onCallClick: (String, String) -> Unit = { _, _ -> },
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsState()
    val history = messages
    val conversation by viewModel.conversation.collectAsState()
    val composerText by viewModel.composerText.collectAsState()
    val sendError by viewModel.sendError.collectAsState()
    val isReEstablishing by viewModel.isReEstablishing.collectAsState()
    val encryptionInfo by viewModel.encryptionInfo.collectAsState()
    val isBlocked by viewModel.isBlocked.collectAsState()
    val contact by viewModel.contact.collectAsState()
    val replyingTo by viewModel.replyingTo.collectAsState()
    var confirmBlock by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }

    if (renaming) {
        RenameContactDialog(
            currentName = conversation?.contactDisplayName.orEmpty(),
            theirOwnName = contact?.profileName,
            onSave = { name ->
                renaming = false
                viewModel.rename(name)
            },
            onDismiss = { renaming = false },
        )
    }

    if (confirmBlock) {
        BlockContactDialog(
            name = conversation?.contactDisplayName,
            onConfirm = {
                confirmBlock = false
                viewModel.block(onRemoved = onBack)
            },
            onDismiss = { confirmBlock = false },
        )
    }

    if (confirmDelete) {
        DeleteChatDialog(
            name = conversation?.contactDisplayName,
            onConfirm = {
                confirmDelete = false
                viewModel.deleteChat(onDeleted = onBack)
            },
            onDismiss = { confirmDelete = false },
        )
    }

    if (confirmRemove) {
        RemoveContactDialog(
            name = conversation?.contactDisplayName,
            onConfirm = {
                confirmRemove = false
                viewModel.removeContact(onRemoved = onBack)
            },
            onDismiss = { confirmRemove = false },
        )
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Request mic permission before navigating to CallScreen for outgoing calls
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onCallClick(viewModel.contactId, conversation?.contactDisplayName ?: "Unknown")
        }
    }

    // Only a resumed (visible) chat suppresses notifications / unread for its messages.
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

    // Poll only while this chat is the resumed screen.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.pollWhileVisible()
        }
    }

    // Show error snackbar
    LaunchedEffect(sendError) {
        sendError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Taps that land while this screen animates out belong to the screen taking
    // its place; ignore them to prevent ghost clicks.
    val isResumed = { lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED }

    ChatContent(
        contactName = conversation?.contactDisplayName,
        avatarSeed = viewModel.contactId,
        isEncrypted = conversation?.isEncrypted == true,
        encryptionInfo = encryptionInfo,
        messages = history.orEmpty(),
        historyLoaded = history != null,
        composerText = composerText,
        needsSessionReset = conversation?.needsSessionReset == true,
        peerDeleted = conversation?.peerDeleted == true,
        isReEstablishing = isReEstablishing,
        isMessageRequest = conversation?.isApproved == false,
        isBlocked = isBlocked,
        snackbarHostState = snackbarHostState,
        onBack = { if (isResumed()) onBack() },
        onCall = {
            if (isResumed()) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    onCallClick(viewModel.contactId, conversation?.contactDisplayName ?: "Unknown")
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        },
        onComposerChange = viewModel::updateComposer,
        onSend = viewModel::send,
        onRetry = viewModel::retrySend,
        onReEstablishSession = viewModel::reEstablishSession,
        onAcceptRequest = viewModel::acceptMessageRequest,
        onRejectRequest = { viewModel.rejectMessageRequest { onBack() } },
        onBlock = { confirmBlock = true },
        onUnblock = viewModel::unblock,
        onRename = { renaming = true },
        onDelete = { confirmDelete = true },
        onRemoveContact = { confirmRemove = true },
        replyingTo = replyingTo,
        onReply = viewModel::startReply,
        onCancelReply = viewModel::cancelReply,
        onReact = viewModel::react,
        onSendVoice = viewModel::sendVoice,
        onSendPhoto = viewModel::sendPhoto,
        loadMedia = viewModel::loadMedia,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatContent(
    contactName: String?,
    avatarSeed: String,
    isEncrypted: Boolean,
    encryptionInfo: String,
    messages: List<Message>,
    /** False while the stored messages are still loading ([messages] is empty then). */
    historyLoaded: Boolean = true,
    composerText: String,
    needsSessionReset: Boolean,
    peerDeleted: Boolean,
    isReEstablishing: Boolean,
    isMessageRequest: Boolean,
    isBlocked: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onCall: () -> Unit,
    onComposerChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: (messageId: String) -> Unit,
    onReEstablishSession: () -> Unit,
    onAcceptRequest: () -> Unit,
    onRejectRequest: () -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onRemoveContact: () -> Unit,
    /** The message the composer replies to, if any. */
    replyingTo: Message? = null,
    onReply: (Message) -> Unit = {},
    onCancelReply: () -> Unit = {},
    /** React to a message with an emoji (its current one takes it back). */
    onReact: (Message, String) -> Unit = { _, _ -> },
    /** Send a recorded voice message, or the photo picked. */
    onSendVoice: (com.sotto.app.media.VoiceRecording) -> Unit = {},
    onSendPhoto: (android.net.Uri) -> Unit = {},
    /** The decrypted media of a message, for its bubble. */
    loadMedia: suspend (String) -> ByteArray? = { null },
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val voicePlayer = remember { com.sotto.app.media.VoicePlayer() }
    var viewingPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    val chatMedia = remember(loadMedia) { ChatMedia(loadMedia, voicePlayer, openPhoto = { viewingPhoto = it }) }
    viewingPhoto?.let { id -> ProvideChatMedia(chatMedia) { PhotoViewer(id, onDismiss = { viewingPhoto = null }) } }

    // Voice messages: hold the mic to record (see MicButton).
    val recorder = remember { com.sotto.app.media.VoiceRecorder(context.applicationContext) }
    var recordState by remember { mutableStateOf(RecordState.IDLE) }
    var recordElapsed by remember { mutableLongStateOf(0L) }
    var recordLevel by remember { mutableFloatStateOf(0f) }
    var recordSlide by remember { mutableStateOf(Offset.Zero) }
    val recordHaptics = LocalHapticFeedback.current
    val recordScope = rememberCoroutineScope()
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val finishRecording: (Boolean) -> Unit = { send ->
        if (recordState != RecordState.IDLE) {
            val recording = if (send) recorder.stop() else {
                recorder.cancel()
                null
            }
            recordState = RecordState.IDLE
            recordSlide = Offset.Zero
            if (recording != null) {
                onSendVoice(recording)
            } else if (send) {
                recordScope.launch { snackbarHostState.showSnackbar("Hold to record, let go to send.") }
            }
        }
    }
    val startRecording: () -> Boolean = start@{
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return@start false
        }
        voicePlayer.stop()
        val started = recorder.start(onLimit = { recordScope.launch { finishRecording(true) } })
        if (started) {
            recordState = RecordState.HOLDING
            recordHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
        } else {
            recordScope.launch { snackbarHostState.showSnackbar("The microphone is busy.") }
        }
        started
    }
    LaunchedEffect(recordState) {
        while (recordState != RecordState.IDLE) {
            recordElapsed = recorder.elapsedMs
            recordLevel = recorder.level()
            delay(100)
        }
        recordElapsed = 0
    }
    // Leaving the chat or the app throws an unfinished recording away and stops playback.
    val mediaLifecycle = LocalLifecycleOwner.current
    DisposableEffect(mediaLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                recorder.cancel()
                recordState = RecordState.IDLE
                voicePlayer.stop()
            }
        }
        mediaLifecycle.lifecycle.addObserver(observer)
        onDispose {
            mediaLifecycle.lifecycle.removeObserver(observer)
            recorder.cancel()
            voicePlayer.stop()
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onSendPhoto(uri)
    }
    val quoteName = contactName ?: "Them"
    val scope = rememberCoroutineScope()
    // The message a tapped quote led to, tinted for a moment.
    var highlighted by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(highlighted) {
        if (highlighted != null) {
            delay(1_200)
            highlighted = null
        }
    }
    val today = rememberToday()
    // Built off the main thread, so a long history doesn't cost a frame on every
    // new message. Until the first pass finishes the list is just empty.
    // Null until the first pass is done. [ChatRowsBuilt.history] tells rows made from the
    // stored history apart from the empty list shown while it's still loading, so that the
    // history is never taken for messages that just arrived.
    val built by produceState<ChatRowsBuilt?>(initialValue = null, messages, historyLoaded, isEncrypted, today) {
        val loaded = historyLoaded
        value = ChatRowsBuilt(
            rows = withContext(Dispatchers.Default) {
                buildChatRows(messages, showEncryptionNotice = isEncrypted, today = today)
            },
            history = loaded,
        )
    }
    val rows = built?.rows
    // Newest first, for a list laid out from the bottom up: it stays anchored on the
    // newest message when the keyboard (or anything else) makes it shorter.
    val newestFirst = remember(rows) { rows.orEmpty().asReversed() }

    // A sent message's text flies from the composer to its bubble (SendFlightOverlay).
    var flight by remember { mutableStateOf<SendFlight?>(null) }
    val composerTextOrigin = remember { arrayOfNulls<Offset>(1) }
    val reduceMotion = LocalReduceMotion.current
    val sendWithFlight: () -> Unit = {
        val text = composerText.trim()
        val origin = composerTextOrigin[0]
        // A reply's bubble has the quote too, so it rises into place instead of flying.
        flight = if (!reduceMotion && origin != null && text.isNotEmpty() && text.length <= FLIGHT_MAX_CHARS && replyingTo == null) {
            SendFlight(text, origin)
        } else {
            null
        }
        onSend()
    }
    // The composer emptied: the send went ahead (one that's refused keeps its text).
    LaunchedEffect(flight, composerText.isEmpty()) {
        if (composerText.isEmpty()) flight?.cleared = true
    }
    // A flight that never took off, or got stuck, gives way to the bubble itself.
    LaunchedEffect(flight) {
        val f = flight ?: return@LaunchedEffect
        delay(FLIGHT_TIMEOUT_MS)
        if (flight === f) flight = null
    }

    // Messages that appear while the chat is open arrive animated: received ones rise into
    // place while their text settles like a departure board (MessageBubble), and the one
    // just sent is the flight's. Never the history, and each message only once.
    val arrivals = remember { mutableStateMapOf<String, Boolean>() }
    val seenMessageIds = remember { HashSet<String>() }
    val historyShown = remember { booleanArrayOf(false) }
    LaunchedEffect(built) {
        val current = built?.takeIf { it.history } ?: return@LaunchedEffect
        val shown = current.rows.mapNotNull { (it as? ChatRow.Entry)?.message }
        if (!historyShown[0]) {
            shown.forEach { seenMessageIds.add(it.id) }
            historyShown[0] = true
            return@LaunchedEffect
        }
        val waiting = flight?.takeIf { it.messageId == null }
        val found = newArrivals(shown, seenMessageIds, waiting?.text)
        if (found.flightId != null) waiting?.messageId = found.flightId
        found.animate.forEach { arrivals[it] = true }
    }

    // Scroll to the newest message whenever a new one arrives.
    val newestKey = rows?.lastOrNull { it is ChatRow.Entry }?.key
    LaunchedEffect(newestKey) {
        if (newestKey != null) listState.animateScrollToItem(0)
    }

    ProvideChatMedia(chatMedia) {
    AuroraBackground(Modifier.fillMaxSize(), style = AuroraStyle.Subtle) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                ChatTopBar(
                    name = contactName ?: "Chat",
                    avatarSeed = avatarSeed,
                    isEncrypted = isEncrypted,
                    encryptionInfo = encryptionInfo,
                    isBlocked = isBlocked,
                    onBack = onBack,
                    onCall = onCall,
                    onBlock = onBlock,
                    onUnblock = onUnblock,
                    onRename = onRename,
                    onDelete = onDelete,
                    onRemoveContact = onRemoveContact,
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                // The bottom follows the navigation bar and, when it's open, the keyboard.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding)
                        .imePadding(),
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            reverseLayout = true,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            items(newestFirst, key = { it.key }, contentType = { it.contentType }) { row ->
                                val id = (row as? ChatRow.Entry)?.message?.id
                                val inFlight = flight?.takeIf { id != null && it.messageId == id }
                                ChatRowItem(
                                    row = row,
                                    onRetry = onRetry,
                                    contactName = quoteName,
                                    onReply = onReply,
                                    onReact = onReact,
                                    onQuoteClick = { targetId ->
                                        val index = newestFirst.indexOfFirst { it.key == "m:$targetId" }
                                        if (index < 0) {
                                            scope.launch { snackbarHostState.showSnackbar("That message isn't on this phone any more.") }
                                        } else {
                                            scope.launch { listState.animateScrollToItem(index) }
                                            highlighted = targetId
                                        }
                                    },
                                    highlighted = id != null && id == highlighted,
                                    animateIn = id != null && arrivals[id] == true,
                                    onAnimateInDone = { id?.let { arrivals.remove(it) } },
                                    hidden = inFlight != null,
                                    onBubblePlaced = inFlight?.let { f -> { bounds: Rect -> f.target = bounds } },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                        AppSnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
                    }

                    // Session reset / account deleted banner. Each state keeps its own
                    // content while it animates out, and a leaving banner takes no taps:
                    // after a successful re-establish it must not flash back to (and
                    // act as) the "tap to resume" card.
                    val sessionNotice = when {
                        !needsSessionReset -> null
                        peerDeleted -> SessionNotice.AccountDeleted
                        isReEstablishing -> SessionNotice.Reestablishing
                        else -> SessionNotice.KeysRotated
                    }
                    AnimatedContent(
                        targetState = sessionNotice,
                        transitionSpec = {
                            (fadeIn(tween(220)) togetherWith fadeOut(tween(150))).using(SizeTransform(clip = true))
                        },
                        label = "sessionNotice",
                    ) { notice ->
                        val guard = Modifier.blockTouches(isExiting)
                        when (notice) {
                            null -> Spacer(Modifier.fillMaxWidth())
                            SessionNotice.AccountDeleted -> NoticeCard(
                                icon = Icons.Rounded.PersonOff,
                                tint = MaterialTheme.colorScheme.error,
                                title = "Account deleted",
                                body = "This user has deleted their account.",
                                modifier = guard,
                            )
                            SessionNotice.Reestablishing -> NoticeCard(
                                icon = Icons.Rounded.Key,
                                tint = MaterialTheme.colorScheme.primary,
                                title = "Re-establishing secure session...",
                                body = null,
                                loading = true,
                                modifier = guard,
                            )
                            SessionNotice.KeysRotated -> NoticeCard(
                                icon = Icons.Rounded.Key,
                                tint = Aurora.colors.warning,
                                title = "Peer rotated keys",
                                body = "Tap to resume sending.",
                                onClick = onReEstablishSession,
                                modifier = guard,
                            )
                        }
                    }

                    // Blocked banner.
                    AnimatedVisibility(
                        visible = isBlocked,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        NoticeCard(
                            icon = Icons.Rounded.Block,
                            tint = MaterialTheme.colorScheme.error,
                            title = "You blocked this contact",
                            body = "Their messages and calls are not delivered.",
                            modifier = Modifier.blockTouches(isExiting),
                        ) {
                            SoftButton(text = "Unblock", onClick = onUnblock, compact = true, modifier = Modifier.weight(1f))
                        }
                    }

                    // Message request accept/reject/block banner. Once accepted it animates
                    // out; its buttons must not work during that (Reject would delete the
                    // conversation that was just accepted).
                    AnimatedVisibility(
                        visible = isMessageRequest && !isBlocked,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        NoticeCard(
                            icon = Icons.Rounded.MarkChatUnread,
                            tint = MaterialTheme.colorScheme.primary,
                            title = "Message request",
                            body = "This is a message request. Accept to reply.",
                            modifier = Modifier.blockTouches(isExiting),
                        ) {
                            MessageRequestActions(onAccept = onAcceptRequest, onReject = onRejectRequest, onBlock = onBlock)
                        }
                    }

                    // Asking to reply opens the keyboard, as it would to write anything.
                    val composerFocus = remember { FocusRequester() }
                    val keyboard = LocalSoftwareKeyboardController.current
                    LaunchedEffect(replyingTo?.id) {
                        if (replyingTo != null) {
                            runCatching { composerFocus.requestFocus() }
                            keyboard?.show()
                        }
                    }
                    AnimatedVisibility(
                        visible = replyingTo != null && !needsSessionReset && !isBlocked,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        // Keeps showing the last one while it animates out.
                        val shownReply = remember { arrayOfNulls<Message>(1) }
                        replyingTo?.let { shownReply[0] = it }
                        shownReply[0]?.let { ReplyPreview(it, quoteName, onCancel = onCancelReply) }
                    }
                    Composer(
                        modifier = Modifier.focusRequester(composerFocus),
                        text = composerText,
                        onTextChange = onComposerChange,
                        onSend = sendWithFlight,
                        enabled = !needsSessionReset && !isBlocked,
                        canSend = composerText.isNotBlank() && !needsSessionReset && !isBlocked,
                        placeholder = when {
                            // The text is still on its way out of the box.
                            flight != null -> ""
                            isBlocked -> "Contact blocked"
                            peerDeleted -> "User deleted their account"
                            needsSessionReset -> "Tap banner to resume sending"
                            else -> "Message"
                        },
                        onTextPlaced = { composerTextOrigin[0] = it },
                        recordState = recordState,
                        recordElapsedMs = recordElapsed,
                        recordLevel = recordLevel,
                        recordSlide = recordSlide,
                        onAttach = {
                            photoPicker.launch(
                                androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        onRecordPress = startRecording,
                        onRecordRelease = { finishRecording(true) },
                        onRecordCancel = {
                            recordHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            finishRecording(false)
                        },
                        onRecordLock = {
                            recordHaptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            recordState = RecordState.LOCKED
                            recordSlide = Offset.Zero
                        },
                        onRecordDrag = { dx, dy -> recordSlide = Offset(dx, dy) },
                    )
                }
                flight?.let { f ->
                    val entry = rows?.lastOrNull { it is ChatRow.Entry && it.message.id == f.messageId } as? ChatRow.Entry
                    // Shown in the very frame the composer empties, so the text never blinks out.
                    SendFlightOverlay(f, entry, composerEmpty = composerText.isEmpty(), onLanded = { if (flight === f) flight = null })
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    name: String,
    avatarSeed: String,
    isEncrypted: Boolean,
    encryptionInfo: String,
    isBlocked: Boolean,
    onBack: () -> Unit,
    onCall: () -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onRemoveContact: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var showMenu by remember { mutableStateOf(false) }
    // Transparent over the aurora; the message list ends at the hairline below it.
    Column {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GradientAvatar(name = name, seed = avatarSeed, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isEncrypted) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(11.dp),
                                    tint = colors.tertiary,
                                )
                                Spacer(Modifier.width(4.dp))
                                GradientText(
                                    text = encryptionInfo,
                                    style = MaterialTheme.typography.labelSmall,
                                    colors = listOf(colors.tertiary, colors.primary),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            },
            actions = {
                if (!isBlocked) {
                    GlassIconButton(
                        icon = Icons.Rounded.Call,
                        contentDescription = "Voice call",
                        onClick = onCall,
                        tint = colors.primary,
                    )
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (isBlocked) "Unblock" else "Block") },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Block,
                                    contentDescription = null,
                                    tint = if (isBlocked) colors.onSurfaceVariant else colors.error,
                                )
                            },
                            onClick = {
                                showMenu = false
                                if (isBlocked) onUnblock() else onBlock()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete chat") },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = colors.error) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove contact") },
                            leadingIcon = { Icon(Icons.Rounded.PersonRemove, contentDescription = null, tint = colors.error) },
                            onClick = {
                                showMenu = false
                                onRemoveContact()
                            },
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
            },
        )
        HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant.copy(alpha = 0.7f))
    }
}

/** Asks before blocking [name]: their messages and calls stop arriving. */
@Composable
internal fun BlockContactDialog(name: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Block ${name ?: "this contact"}?") },
        text = {
            Text(
                "You won't receive their messages or calls. They aren't told. " +
                    "You can unblock them later in Settings."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Block", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Asks before deleting the chat with [name] from this phone. */
@Composable
internal fun DeleteChatDialog(name: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Delete this chat?") },
        text = {
            Text(
                "Its messages and calls are deleted from this phone. ${name ?: "They"} keeps " +
                    "their copy, and a new message from them starts a new chat."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Names a contact on this phone only. Mentions the name they chose when it differs; saving
 * an empty field goes back to it (or to the start of their id).
 */
@Composable
internal fun RenameContactDialog(
    currentName: String,
    theirOwnName: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
        title = { Text("Rename contact") },
        text = {
            Column {
                AuroraTextField(
                    value = name,
                    onValueChange = { if (it.codePointCount(0, it.length) <= DisplayNames.MAX_LENGTH) name = it },
                    placeholder = "Name",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSave(name) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (theirOwnName != null && theirOwnName != currentName) {
                        "They call themselves \u201c$theirOwnName\u201d. Only you see the name you set here."
                    } else {
                        "Only you see this name."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private enum class SessionNotice { AccountDeleted, Reestablishing, KeysRotated }

/**
 * One row of the conversation: the encryption notice, a day header or a message.
 * Keys are namespaced ("n:", "d:", "m:"): message ids come from the relay, and a
 * raw id equal to another row's key would crash the list every time it opened.
 */
private sealed interface ChatRow {
    val key: String
    val contentType: String

    data object EncryptionNotice : ChatRow {
        override val key = "n:encryption"
        override val contentType = "notice"
    }

    data class DayHeader(val label: String, override val key: String) : ChatRow {
        override val contentType get() = "day"
    }

    /** [time] is formatted only when the row is shown, not for the whole history. */
    data class Entry(
        val message: Message,
        val time: LocalTime,
        val groupedWithPrevious: Boolean,
    ) : ChatRow {
        override val key get() = "m:${message.id}"
        override val contentType
            get() = when {
                message.state.isKeyChangeEvent -> "key"
                message.state.isCallEvent -> "call"
                else -> "bubble"
            }
    }
}

/**
 * Turns messages into rows: a day header wherever the local date changes (so a
 * bubble only needs HH:mm), and consecutive bubbles from one side, minutes apart,
 * marked as a group.
 */
private fun buildChatRows(messages: List<Message>, showEncryptionNotice: Boolean, today: LocalDate): List<ChatRow> {
    val tz = TimeZone.currentSystemDefault()
    val rows = ArrayList<ChatRow>(messages.size + 8)
    if (showEncryptionNotice) rows += ChatRow.EncryptionNotice
    var previous: Message? = null
    var previousDate: LocalDate? = null
    for (message in messages) {
        val local = message.timestamp.toLocalDateTime(tz)
        val sameDay = local.date == previousDate
        if (!sameDay) {
            rows += ChatRow.DayHeader(dayLabel(local.date, today), key = "d:${local.date}")
        }
        val grouped = sameDay && previous != null &&
            previous.isBubble && message.isBubble &&
            previous.isOutgoing == message.isOutgoing &&
            message.timestamp - previous.timestamp <= 5.minutes
        rows += ChatRow.Entry(
            message = message,
            time = local.time,
            groupedWithPrevious = grouped,
        )
        previous = message
        previousDate = local.date
    }
    return rows
}

private val Message.isBubble get() = !state.isCallEvent && !state.isKeyChangeEvent

@Composable
private fun ChatRowItem(
    row: ChatRow,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
    contactName: String = "",
    onReply: (Message) -> Unit = {},
    onReact: (Message, String) -> Unit = { _, _ -> },
    onQuoteClick: (String) -> Unit = {},
    highlighted: Boolean = false,
    animateIn: Boolean = false,
    onAnimateInDone: () -> Unit = {},
    hidden: Boolean = false,
    onBubblePlaced: ((Rect) -> Unit)? = null,
) {
    when (row) {
        ChatRow.EncryptionNotice -> EncryptionNotice(modifier.padding(top = 4.dp, bottom = 6.dp))
        is ChatRow.DayHeader -> DaySeparator(row.label, modifier.padding(top = 14.dp, bottom = 6.dp))
        is ChatRow.Entry -> {
            val message = row.message
            val time = remember(row.time) { row.time.clockTime() }
            when {
                message.state.isKeyChangeEvent -> KeyChangeEventItem(
                    state = message.state,
                    timestamp = time,
                    modifier = modifier.padding(vertical = 6.dp),
                )
                message.state.isCallEvent -> CallEventItem(
                    state = message.state,
                    durationSeconds = message.plaintext.toLongOrNull() ?: 0L,
                    timestamp = time,
                    isOutgoing = message.isOutgoing,
                    modifier = modifier.padding(vertical = 6.dp),
                )
                else -> {
                var picking by remember { mutableStateOf(false) }
                var pickingMore by remember { mutableStateOf(false) }
                val haptics = LocalHapticFeedback.current
                val react = { emoji: String ->
                    picking = false
                    pickingMore = false
                    onReact(message, emoji)
                }
                // The picker never takes focus (so the keyboard stays): Back closes it here.
                BackHandler(enabled = picking) {
                    picking = false
                    pickingMore = false
                }
                SwipeToReply(
                    enabled = message.canReply,
                    onReply = { onReply(message) },
                    modifier = modifier
                        .padding(top = if (row.groupedWithPrevious) 2.dp else 8.dp)
                        .replyHighlight(highlighted || picking),
                ) {
                    MessageBubble(
                        text = when {
                            message.state == MessageState.REJECTED -> "⛔ Message rejected (unsigned or unverifiable)"
                            message.state == MessageState.DECRYPT_FAILED -> "⚠ Could not decrypt this message"
                            message.media != null -> message.plaintext.trim()
                            message.plaintext.isEmpty() -> "..."
                            else -> message.plaintext
                        },
                        media = message.media?.let { { color -> BubbleMedia(message, color) } },
                        timestamp = time,
                        isOutgoing = message.isOutgoing,
                        state = message.state,
                        groupedWithPrevious = row.groupedWithPrevious,
                        quote = message.bubbleQuote(contactName),
                        onQuoteClick = message.replyTo?.let { ref -> { onQuoteClick(ref.messageId) } },
                        // Failed sends: tap to retry.
                        onClick = if (message.isOutgoing && message.state == MessageState.FAILED) {
                            { onRetry(message.id) }
                        } else {
                            null
                        },
                        animateIn = animateIn,
                        onAnimateInDone = onAnimateInDone,
                        hidden = hidden,
                        onBubblePlaced = onBubblePlaced,
                        onLongClick = if (message.canReact) {
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                picking = true
                            }
                        } else {
                            null
                        },
                        // As on Instagram: a double tap hearts it, another takes the heart back.
                        onDoubleClick = if (message.canReact) {
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                react(ReactionPayload.HEART)
                            }
                        } else {
                            null
                        },
                        reactions = BubbleReactions(message.theirReaction, message.myReaction, contactName)
                            .takeUnless { it.isEmpty },
                        onReactionsClick = if (message.canReact) ({ picking = true }) else null,
                        overlay = if (picking) {
                            {
                                ReactionPicker(
                                    current = message.myReaction,
                                    alignEnd = message.isOutgoing,
                                    expanded = pickingMore,
                                    onPick = react,
                                    onMore = { pickingMore = true },
                                    onDismiss = {
                                        picking = false
                                        pickingMore = false
                                    },
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun EncryptionNotice(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .glass(Aurora.colors, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Messages and calls are end-to-end encrypted. No one outside this chat, " +
                    "not even the relay server, can read them.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
        }
    }
}

/** A floating banner above the composer, tinted by what it's about. */
@Composable
private fun NoticeCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    onClick: (() -> Unit)? = null,
    actions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(22.dp)
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp)
            .clip(shape)
            .background(if (Aurora.colors.isDark) colors.surfaceContainerHigh else colors.surfaceContainerLowest)
            .background(tint.copy(alpha = 0.08f))
            .border(1.dp, tint.copy(alpha = 0.28f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = tint)
                } else {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                if (body != null) {
                    Text(text = body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowForwardIos,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (actions != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                content = actions,
            )
        }
    }
}

@Composable
private fun Composer(
    modifier: Modifier = Modifier,
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    canSend: Boolean,
    placeholder: String,
    onTextPlaced: (Offset) -> Unit = {},
    recordState: RecordState = RecordState.IDLE,
    recordElapsedMs: Long = 0,
    recordLevel: Float = 0f,
    recordSlide: Offset = Offset.Zero,
    onAttach: () -> Unit = {},
    onRecordPress: () -> Boolean = { false },
    onRecordRelease: () -> Unit = {},
    onRecordCancel: () -> Unit = {},
    onRecordLock: () -> Unit = {},
    onRecordDrag: (Float, Float) -> Unit = { _, _ -> },
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (recordState == RecordState.IDLE) {
            AttachButton(enabled = enabled, onClick = onAttach)
            AuroraTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = placeholder,
                modifier = Modifier.weight(1f).then(modifier),
                enabled = enabled,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                onTextPlaced = onTextPlaced,
            )
        } else {
            Spacer(Modifier.width(6.dp))
            RecordingBar(
                state = recordState,
                elapsedMs = recordElapsedMs,
                level = recordLevel,
                slideX = recordSlide.x,
                slideY = recordSlide.y,
                onDelete = onRecordCancel,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            recordState == RecordState.LOCKED -> SendButton(enabled = true, onClick = onRecordRelease)
            // Something typed: Send. Otherwise the mic (it keeps its touch while held).
            text.isNotBlank() && recordState == RecordState.IDLE -> SendButton(enabled = canSend, onClick = onSend)
            else -> MicButton(
                enabled = enabled,
                state = recordState,
                onPress = onRecordPress,
                onRelease = onRecordRelease,
                onCancel = onRecordCancel,
                onLock = onRecordLock,
                onDrag = onRecordDrag,
            )
        }
    }
}

/** Round gradient send button with an up arrow; pops in when there's something to send. */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val aurora = Aurora.colors
    val colors = MaterialTheme.colorScheme
    val ready by animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "sendReady",
    )
    Box(
        modifier = Modifier
            .size(52.dp)
            .graphicsLayer {
                val scale = 0.9f + 0.1f * ready
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (enabled) {
                    Modifier.shadow(14.dp, CircleShape, ambientColor = aurora.glow, spotColor = aurora.glow)
                } else {
                    Modifier
                },
            )
            .clip(CircleShape)
            .background(
                if (enabled) Brush.linearGradient(aurora.brand) else SolidColor(colors.onSurface.copy(alpha = 0.08f)),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.ArrowUpward,
            contentDescription = "Send",
            tint = if (enabled) Color.White else colors.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(24.dp),
        )
    }
}

/** The chat's rows, and whether they were made from the stored history (not the empty list shown while it loads). */
private class ChatRowsBuilt(val rows: List<ChatRow>, val history: Boolean)

/** Longer messages skip the flight from the composer and rise into place instead. */
private const val FLIGHT_MAX_CHARS = 280

/** A flight gives way to the bubble itself after this long, whatever happened. */
private const val FLIGHT_TIMEOUT_MS = 3_000L

/** How far along its way the text counts as landed, ready to flip (the spring settles the rest). */
private const val LANDED = 0.97f

/** Of messages that arrive together, only this many of the newest animate. */
private const val MAX_ARRIVAL_ANIMATIONS = 3

/**
 * A sent message's text on its way from the composer to its bubble: it lifts out of the
 * text box (whose own text just vanished), turns into the bubble on the way and lands where
 * the message appeared in the list. Only there do its letters flip and settle like a
 * departure board.
 */
@Stable
private class SendFlight(
    val text: String,
    /** Where the composer's text started, in root coordinates. */
    val textOrigin: Offset,
) {
    /** The composer emptied after the tap, so the send went ahead. */
    var cleared by mutableStateOf(false)
    /** The message it became, once that's in the list. */
    var messageId by mutableStateOf<String?>(null)
    /** That message's bubble, in root coordinates, once it's laid out. */
    var target by mutableStateOf<Rect?>(null)
}

/** Draws [flight] over the chat, and calls [onLanded] once it's in place and its text has settled. */
@Composable
private fun SendFlightOverlay(flight: SendFlight, entry: ChatRow.Entry?, composerEmpty: Boolean, onLanded: () -> Unit) {
    if (!flight.cleared && !composerEmpty) return
    val density = LocalDensity.current
    val origin = remember { arrayOf(Offset.Zero) }
    val progress = remember(flight) { Animatable(0f) }
    // Plain on the way; the letters flip once the text has landed.
    var scrambleMs by remember(flight) { mutableStateOf<Long?>(null) }
    LaunchedEffect(flight) {
        // It takes off as soon as its bubble has a place in the list.
        snapshotFlow { flight.target != null }.first { it }
        coroutineScope {
            launch { progress.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 260f)) }
            snapshotFlow { progress.value }.first { it >= LANDED }
            runScramble(flight.text.length) { scrambleMs = it }
            scrambleMs = null
        }
        onLanded()
    }
    // The bubble's corner is that far up and left of its text, so the text starts out
    // exactly where it was in the composer.
    val from = flight.textOrigin - with(density) { Offset(BubbleTextInsetStart.toPx(), BubbleTextInsetTop.toPx()) }
    val time = remember(entry?.time) { entry?.time?.clockTime().orEmpty() }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin[0] = it.positionInRoot() }
            .clearAndSetSemantics {},
    ) {
        BubbleSurface(
            text = flight.text,
            timestamp = time,
            isOutgoing = true,
            state = entry?.message?.state ?: MessageState.SENDING,
            groupedWithPrevious = entry?.groupedWithPrevious ?: false,
            onClick = null,
            scrambleMs = scrambleMs,
            look = progress.value,
            modifier = Modifier
                .offset {
                    val to = flight.target?.topLeft ?: from
                    val at = from + (to - from) * progress.value - origin[0]
                    IntOffset(at.x.roundToInt(), at.y.roundToInt())
                }
                .graphicsLayer {
                    // Lifted a little on the way, as if picked up out of the text box.
                    val lift = sin(PI * progress.value.coerceIn(0f, 1f)).toFloat()
                    scaleX = 1f + 0.05f * lift
                    scaleY = 1f + 0.05f * lift
                },
        )
    }
}

internal data class Arrivals(val animate: List<String>, val flightId: String?)

/**
 * The messages of [messages] not in [seen] yet (adding them): which ones arrive with the
 * rise-and-flip animation, and which one is [flightText], just sent from the composer.
 * Call and key events never animate, and of a batch that arrives at once only the newest
 * few do.
 */
internal fun newArrivals(messages: List<Message>, seen: MutableSet<String>, flightText: String?): Arrivals {
    var flightId: String? = null
    val animate = ArrayList<String>()
    for (message in messages) {
        if (!seen.add(message.id)) continue
        if (message.state.isCallEvent || message.state.isKeyChangeEvent) continue
        if (flightId == null && flightText != null && message.isOutgoing && message.media == null && message.plaintext == flightText) {
            flightId = message.id
        } else {
            animate += message.id
        }
    }
    return Arrivals(animate.takeLast(MAX_ARRIVAL_ANIMATIONS), flightId)
}
