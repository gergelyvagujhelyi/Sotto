package com.sotto.app.ui.call

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.SubcomposeLayout
import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.sotto.app.call.hasRouteChoice
import com.sotto.app.domain.model.AudioRoute
import com.sotto.app.domain.model.AudioRouteKind
import com.sotto.app.domain.model.CallDirection
import com.sotto.app.domain.model.CallPath
import com.sotto.app.domain.model.CallState
import com.sotto.app.domain.model.CallStatus
import com.sotto.app.ui.components.AppSnackbarHost
import com.sotto.app.ui.components.AuroraBackground
import com.sotto.app.ui.components.AuroraStyle
import com.sotto.app.ui.components.GradientAvatar
import com.sotto.app.ui.components.InfoPill
import com.sotto.app.ui.components.glass
import com.sotto.app.ui.theme.Aurora
import com.sotto.app.ui.theme.CallGreen
import com.sotto.app.ui.theme.CallRed
import com.sotto.app.ui.theme.ForcedDarkTheme
import com.sotto.app.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay

@Composable
fun CallScreen(
    onCallEnded: () -> Unit,
    onMinimize: () -> Unit = {},
    viewModel: CallViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val callState by viewModel.callState.collectAsState()
    val callError by viewModel.callError.collectAsState()
    val encryptionInfo by viewModel.encryptionInfo.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Back during a live call leaves this screen while the call goes on: the rest of the
    // app shows a bar that returns here, and so does the ongoing-call notification. On a
    // locked phone the call shows over the lock screen, so Back sends the app to the
    // background instead of revealing the chats.
    val callLive = callState?.status?.let {
        it !in setOf(
            CallStatus.ENDED, CallStatus.FAILED, CallStatus.NO_RELAY,
            CallStatus.REJECTED, CallStatus.BUSY, CallStatus.UNAVAILABLE, CallStatus.PEER_GONE,
            CallStatus.NO_NETWORK,
        )
    } == true
    val minimize: () -> Unit = {
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (locked) (context as? android.app.Activity)?.moveTaskToBack(true) else onMinimize()
    }
    androidx.activity.compose.BackHandler(enabled = callLive, onBack = minimize)

    LaunchedEffect(callError) {
        callError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearCallError()
        }
    }

    // Permission launcher — shared by outgoing auto-start and incoming Accept button.
    // The callback checks current state to decide what to do after grant.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val state = viewModel.callState.value
            if (state?.direction == CallDirection.INCOMING && state.status == CallStatus.RINGING) {
                // User pressed Accept → permission dialog → granted → accept the call
                viewModel.acceptCall()
            } else {
                viewModel.onMicPermissionGranted()
            }
        }
    }

    // Accepted from the incoming-call notification: answer like the Accept button.
    val autoAcceptRequest by viewModel.autoAcceptRequest.collectAsState()
    LaunchedEffect(autoAcceptRequest, callState?.callId) {
        if (autoAcceptRequest == null || !viewModel.consumeAutoAccept()) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.acceptCall()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // For outgoing calls: request permission immediately so the call can start.
    // For incoming calls: skip — let the user see who's calling first.
    // The Accept button handles permission when they choose to answer.
    LaunchedEffect(Unit) {
        val state = viewModel.callState.value
        if (state?.direction == CallDirection.INCOMING) return@LaunchedEffect

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.onMicPermissionGranted()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Auto-navigate back when call ends
    LaunchedEffect(callState) {
        val state = callState
        if (state == null) {
            // Give a moment for state to be set (e.g. incoming call navigation)
            delay(500)
            if (viewModel.callState.value == null) {
                onCallEnded()
            }
        } else when (state.status) {
            CallStatus.ENDED, CallStatus.FAILED, CallStatus.NO_RELAY,
            CallStatus.REJECTED, CallStatus.BUSY, CallStatus.UNAVAILABLE -> {
                delay(1500)
                onCallEnded()
            }
            // Why the call ended takes reading (CallManager keeps these states as long).
            CallStatus.PEER_GONE, CallStatus.NO_NETWORK -> {
                delay(4000)
                onCallEnded()
            }
            else -> {}
        }
    }

    CallContent(
        callState = callState,
        encryptionInfo = encryptionInfo,
        snackbarHostState = snackbarHostState,
        onAccept = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.acceptCall()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onDecline = viewModel::rejectCall,
        onHangup = viewModel::hangup,
        onToggleMute = viewModel::toggleMute,
        onToggleSpeaker = viewModel::toggleSpeaker,
        onSelectAudioRoute = viewModel::selectAudioRoute,
        onToggleLouder = viewModel::toggleLouder,
        onMinimize = if (callLive) minimize else null,
        onVerify = viewModel::startVerification,
        onVerifyAnswer = viewModel::answerVerification,
        onVerifyDismiss = viewModel::dismissVerification,
    )
}

/** The call UI. Always dark and immersive, whatever the system theme. */
@Composable
fun CallContent(
    callState: CallState?,
    encryptionInfo: String,
    snackbarHostState: SnackbarHostState,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangup: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    /** Leave this screen while the call goes on; null hides the button. */
    onMinimize: (() -> Unit)? = null,
    onSelectAudioRoute: (AudioRoute) -> Unit = {},
    onToggleLouder: () -> Unit = {},
    /** Verify by phone: compare six digits with the other person (ADR-31). */
    onVerify: () -> Unit = {},
    onVerifyAnswer: (matches: Boolean) -> Unit = {},
    onVerifyDismiss: () -> Unit = {},
) = ForcedDarkTheme {
    val status = callState?.status
    val name = callState?.peerDisplayName ?: "Unknown"
    val colors = MaterialTheme.colorScheme

    AuroraBackground(
        modifier = Modifier.fillMaxSize(),
        style = AuroraStyle.Full,
        color = colors.surfaceContainerLowest,
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { AppSnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth()) {
                    InfoPill(
                        text = encryptionInfo,
                        icon = Icons.Rounded.Lock,
                        maxLines = 2,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = if (onMinimize != null) 44.dp else 0.dp),
                    )
                    if (onMinimize != null) {
                        IconButton(onClick = onMinimize, modifier = Modifier.align(Alignment.CenterStart)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to the app; the call goes on")
                        }
                    }
                }
                callState?.verify?.let { verify ->
                    CallVerifyDialog(
                        verify = verify,
                        name = name,
                        onStart = onVerify,
                        onAnswer = onVerifyAnswer,
                        onDismiss = onVerifyDismiss,
                    )
                }

                // The controls below are measured before this weighted area, so they
                // always get their full height. The caller's details shrink into what
                // is left and scroll when even that is too little (landscape, split
                // screen, large fonts).
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val compact = maxHeight < 360.dp
                    val available = maxHeight
                    val avatarGap = if (compact) 12.dp else 24.dp
                    // The name, the time, the path and any warning are measured first, at the
                    // phone's own font size; the avatar gets what's left, so it's the avatar
                    // that shrinks on a shorter screen, not the space above the controls.
                    // (A fixed allowance for them let the path pill reach the controls on an
                    // S25 with a larger display or font size.)
                    SubcomposeLayout(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 12.dp),
                    ) { constraints ->
                        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
                        val details = subcompose("details") {
                            CallDetails(callState = callState, name = name, compact = compact, onVerify = onVerify)
                        }.map { it.measure(loose) }
                        val detailsHeight = details.sumOf { it.height }
                        val room = available.roundToPx() - (24.dp + avatarGap + MIN_CONTROLS_GAP * 2).roundToPx() - detailsHeight
                        val size = room.toDp().coerceIn(64.dp, 240.dp)
                        val avatar = subcompose("avatar") {
                            CallAvatar(
                                name = name,
                                seed = callState?.peerId ?: name,
                                pulsing = status == CallStatus.RINGING || status == CallStatus.CONNECTING,
                                size = size,
                            )
                        }.map { it.measure(loose) }
                        val width = constraints.maxWidth
                        val height = avatar.sumOf { it.height } + avatarGap.roundToPx() + detailsHeight
                        layout(width, height) {
                            var y = 0
                            avatar.forEach { it.placeRelative((width - it.width) / 2, y); y += it.height }
                            y += avatarGap.roundToPx()
                            details.forEach { it.placeRelative((width - it.width) / 2, y); y += it.height }
                        }
                    }
                }

                CallControls(
                    callState = callState,
                    onAccept = onAccept,
                    onDecline = onDecline,
                    onHangup = onHangup,
                    onToggleMute = onToggleMute,
                    onToggleSpeaker = onToggleSpeaker,
                    onSelectAudioRoute = onSelectAudioRoute,
                    onToggleLouder = onToggleLouder,
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** The caller's name, the call's time or status, and its path or trouble, under the avatar. */
@Composable
private fun CallDetails(callState: CallState?, name: String, compact: Boolean, onVerify: () -> Unit = {}) {
    val status = callState?.status
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = name,
            style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (callState?.isNewContact == true) {
            Spacer(Modifier.height(10.dp))
            InfoPill(text = "Unknown contact", icon = Icons.Rounded.PersonSearch, tint = colors.error)
        }
        Spacer(Modifier.height(10.dp))
        if (status == CallStatus.CONNECTED) {
            val fallback = remember { System.currentTimeMillis() }
            CallDurationTimer(connectedAt = callState?.connectedAt ?: fallback)
        } else {
            Text(
                text = statusText(
                    status, callState?.direction, callState?.peerDisplayName,
                    wasConnected = callState?.connectedAt != null,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        val reconnecting = status == CallStatus.CONNECTED && callState?.reconnecting == true
        // Nothing arriving from the other phone: said even while ICE claims to be
        // connected, and until their audio is back.
        val peerUnreachable = status == CallStatus.CONNECTED && callState?.peerUnreachable == true
        val justReconnected = rememberJustReconnected(reconnecting || peerUnreachable)
        if (peerUnreachable) {
            Spacer(Modifier.height(14.dp))
            ReconnectingIndicator(
                title = "Can't reach ${callState?.peerDisplayName ?: "the other phone"}",
                detail = "Nothing is coming from their phone. The call ends after a minute if it doesn't come back.",
            )
        } else if (reconnecting) {
            Spacer(Modifier.height(14.dp))
            ReconnectingIndicator()
        } else if (status == CallStatus.CONNECTED && justReconnected) {
            Spacer(Modifier.height(14.dp))
            InfoPill(text = "Reconnected", icon = Icons.Rounded.CheckCircle, tint = Aurora.colors.success)
        } else if (status == CallStatus.CONNECTED && callState?.path != null) {
            Spacer(Modifier.height(14.dp))
            val hd = if (callState.highQuality) " \u00b7 HD" else ""
            if (callState.path == CallPath.DIRECT) {
                InfoPill(text = "Direct$hd", icon = Icons.Rounded.SwapCalls)
            } else {
                InfoPill(text = "Via relay$hd", icon = Icons.Rounded.Dns, tint = colors.onSurfaceVariant)
            }
        }

        // Verify by phone (ADR-31): a labelled pill, so it says what it's for.
        if (status == CallStatus.CONNECTED && callState?.isNewContact == false) {
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.primary.copy(alpha = 0.18f))
                    .clickable(onClickLabel = "Verify $name by phone", onClick = onVerify)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Verify by phone", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        }

        // Relay unavailable warning
        if (callState?.relayUnavailable == true && status != CallStatus.NO_RELAY) {
            Spacer(Modifier.height(14.dp))
            InfoPill(
                text = "Relay unavailable — call may not connect",
                icon = Icons.Rounded.WarningAmber,
                tint = Aurora.colors.warning,
                maxLines = 3,
            )
        }
    }
}

/** At least this much space above and below the caller's details (before the area scrolls). */
private val MIN_CONTROLS_GAP = 16.dp

@Composable
private fun CallControls(
    callState: CallState?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangup: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onSelectAudioRoute: (AudioRoute) -> Unit,
    onToggleLouder: () -> Unit,
) {
    val state = callState
    when {
        state != null && state.status == CallStatus.RINGING && state.direction == CallDirection.INCOMING -> {
            // Incoming: Accept / Decline
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                RoundCallButton(Icons.Rounded.CallEnd, "Decline", CallRed, onDecline, label = "Decline")
                RoundCallButton(Icons.Rounded.Call, "Accept", CallGreen, onAccept, label = "Accept", beckon = true)
            }
        }
        state != null && state.status == CallStatus.CONNECTED -> {
            // In-call: Mute, Louder and Speaker on a glass tray, End centred below them
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(Aurora.colors, RoundedCornerShape(40.dp))
                    .padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Top,
                ) {
                    CallToggleButton(
                        icon = if (state.isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        contentDescription = "Mute",
                        label = if (state.isMuted) "Muted" else "Mute",
                        checked = state.isMuted,
                        onToggle = onToggleMute,
                    )
                    // Lifts the other person's voice more; remembered for them (ADR-23).
                    CallToggleButton(
                        icon = Icons.Rounded.RecordVoiceOver,
                        contentDescription = "Louder",
                        label = "Louder",
                        checked = state.louder,
                        onToggle = onToggleLouder,
                    )
                    AudioRouteButton(
                        routes = state.audioRoutes,
                        current = state.audioRoute,
                        onToggleSpeaker = onToggleSpeaker,
                        onSelect = onSelectAudioRoute,
                    )
                }
                Spacer(Modifier.height(20.dp))
                RoundCallButton(Icons.Rounded.CallEnd, "End call", CallRed, onHangup, label = "End", size = 64.dp)
            }
        }
        else -> {
            // Non-active state: hangup (no-op if terminal), LaunchedEffect handles dismiss
            RoundCallButton(Icons.Rounded.CallEnd, "Cancel", CallRed, onHangup, label = "Cancel")
        }
    }
}

/** A big round gradient button with a coloured glow; [beckon] adds a pulse. */
@Composable
private fun RoundCallButton(
    icon: ImageVector,
    contentDescription: String,
    colors: List<Color>,
    onClick: () -> Unit,
    label: String? = null,
    beckon: Boolean = false,
    size: Dp = 72.dp,
) {
    // The pulse draws outside the button's bounds, so it doesn't change the layout.
    val pulse = if (beckon) rememberPulse() else null
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // The whole button takes the tap, its label too. Only the circle used to, so a tap on
        // "End" under it did nothing, and a call went on that way.
        modifier = Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            role = Role.Button,
            onClick = onClick,
        ),
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .drawWithCache {
                    val ring = Stroke(width = 1.5.dp.toPx())
                    val start = this.size.minDimension / 2f
                    val travel = (size * 0.36f).toPx()
                    onDrawBehind {
                        if (pulse != null) drawPulseRings(pulse.value, colors.first(), start, travel, ring)
                    }
                }
                .shadow(20.dp, CircleShape, ambientColor = colors.first(), spotColor = colors.first())
                .background(Brush.linearGradient(colors), CircleShape)
                .clip(CircleShape)
                // The ripple stays on the circle.
                .indication(interaction, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(size * 0.44f))
        }
        if (label != null) {
            Spacer(Modifier.height(10.dp))
            // Read out once, as the icon's description: the label only repeats it.
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/**
 * Where the call plays. With only the phone's earpiece and speaker it's a Speaker toggle;
 * with a headset, Bluetooth device or car connected it shows the route in use and opens a
 * list to pick one (output and microphone switch together).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioRouteButton(
    routes: List<AudioRoute>,
    current: AudioRoute?,
    onToggleSpeaker: () -> Unit,
    onSelect: (AudioRoute) -> Unit,
) {
    if (!hasRouteChoice(routes)) {
        CallToggleButton(
            icon = Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = "Speaker",
            label = "Speaker",
            checked = current?.kind == AudioRouteKind.SPEAKER,
            onToggle = onToggleSpeaker,
        )
        return
    }
    var choosing by remember { mutableStateOf(false) }
    val kind = current?.kind ?: AudioRouteKind.EARPIECE
    CallToggleButton(
        icon = routeIcon(kind),
        contentDescription = "Audio on ${current?.name ?: "Phone"}. Choose where the call plays",
        label = current?.name ?: "Audio",
        checked = kind != AudioRouteKind.EARPIECE,
        onToggle = { choosing = true },
    )
    if (choosing) {
        ModalBottomSheet(onDismissRequest = { choosing = false }) {
            Text(
                "Play the call on",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            routes.forEach { route ->
                ListItem(
                    headlineContent = { Text(route.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(routeIcon(route.kind), contentDescription = null) },
                    trailingContent = {
                        if (route.key == current?.key) Icon(Icons.Rounded.Check, contentDescription = "In use")
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        onSelect(route)
                        choosing = false
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun routeIcon(kind: AudioRouteKind): ImageVector = when (kind) {
    AudioRouteKind.EARPIECE -> Icons.Rounded.PhoneInTalk
    AudioRouteKind.SPEAKER -> Icons.AutoMirrored.Rounded.VolumeUp
    AudioRouteKind.WIRED, AudioRouteKind.USB -> Icons.Rounded.Headphones
    AudioRouteKind.BLUETOOTH -> Icons.Rounded.BluetoothAudio
    AudioRouteKind.HEARING_AID -> Icons.Rounded.Hearing
}

/** Glass when off, solid white when on — like the toggles of a phone's dialer. */
@Composable
private fun CallToggleButton(
    icon: ImageVector,
    contentDescription: String,
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // The whole button toggles, its label too (see RoundCallButton).
        modifier = Modifier.toggleable(
            value = checked,
            interactionSource = interaction,
            indication = null,
            role = Role.Switch,
            onValueChange = { onToggle() },
        ),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (checked) Color.White else Color.White.copy(alpha = 0.10f))
                .border(1.dp, Color.White.copy(alpha = if (checked) 0f else 0.12f), CircleShape)
                .indication(interaction, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (checked) Color(0xFF0B0B12) else Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Read out once, as the icon's description and the switch's state.
            modifier = Modifier.widthIn(max = 96.dp).clearAndSetSemantics {},
        )
    }
}

@Composable
private fun CallAvatar(name: String, seed: String, pulsing: Boolean, size: Dp) {
    val glow = Aurora.colors.glow
    val avatar = size * 0.55f
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        // Rings only animate while ringing/connecting; a connected call stays still.
        if (pulsing) PulseRings(color = glow, baseRadius = avatar / 2, modifier = Modifier.matchParentSize())
        Spacer(
            Modifier
                .size(size * 0.83f)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to glow.copy(alpha = 0.45f),
                            1f to Color.Transparent,
                            center = center,
                            radius = this.size.minDimension / 2f,
                        ),
                    )
                },
        )
        GradientAvatar(name = name, seed = seed, size = avatar)
    }
}

@Composable
private fun PulseRings(color: Color, baseRadius: Dp, modifier: Modifier = Modifier) {
    val pulse = rememberPulse()
    Spacer(
        modifier.drawWithCache {
            val ring = Stroke(width = 1.5.dp.toPx())
            val start = baseRadius.toPx()
            val travel = size.minDimension / 2f - start
            onDrawBehind { drawPulseRings(pulse.value, color, start, travel, ring) }
        },
    )
}

@Composable
private fun rememberPulse(): State<Float> = if (LocalReduceMotion.current) {
    remember { mutableFloatStateOf(0.35f) } // the rings hold still
} else {
    rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing)),
        label = "pulseProgress",
    )
}

/** Three rings expanding from [start] by up to [travel] and fading as they go. */
private fun DrawScope.drawPulseRings(progress: Float, color: Color, start: Float, travel: Float, ring: Stroke) {
    repeat(3) { i ->
        val p = (progress + i / 3f) % 1f
        val radius = start + travel * p
        val alpha = (1f - p) * 0.5f
        drawCircle(color.copy(alpha = alpha * 0.25f), radius = radius)
        drawCircle(color.copy(alpha = alpha), radius = radius, style = ring)
    }
}

@Composable
private fun CallDurationTimer(connectedAt: Long) {
    var elapsed by remember { mutableLongStateOf(0L) }

    LaunchedEffect(connectedAt) {
        while (true) {
            elapsed = (System.currentTimeMillis() - connectedAt) / 1000
            delay(1000)
        }
    }

    val minutes = elapsed / 60
    val seconds = elapsed % 60
    Text(
        text = "%d:%02d".format(minutes, seconds),
        style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** True for a moment after a lost connection came back, to say so (the call also chimes). */
@Composable
private fun rememberJustReconnected(reconnecting: Boolean): Boolean {
    var wasReconnecting by remember { mutableStateOf(false) }
    var justBack by remember { mutableStateOf(false) }
    LaunchedEffect(reconnecting) {
        if (reconnecting) {
            wasReconnecting = true
            justBack = false
        } else if (wasReconnecting) {
            wasReconnecting = false
            justBack = true
            kotlinx.coroutines.delay(2_500)
            justBack = false
        }
    }
    return justBack
}

/**
 * The connection is lost and being restored: a spinning, pulsing amber banner that TalkBack
 * announces. The call keeps beeping softly meanwhile (CallManager).
 */
@Composable
private fun ReconnectingIndicator(
    title: String = "Reconnecting…",
    detail: String = "No connection right now",
) {
    val warning = Aurora.colors.warning
    val transition = rememberInfiniteTransition(label = "reconnecting")
    val turn by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing)),
        label = "spin",
    )
    val glow by transition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.26f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .drawBehind { drawRect(warning.copy(alpha = glow)) }
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Sync,
            contentDescription = null,
            tint = warning,
            modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = -turn },
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = warning,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun statusText(
    status: CallStatus?,
    direction: CallDirection?,
    peerName: String? = null,
    wasConnected: Boolean = false,
): String = when (status) {
    CallStatus.RINGING -> if (direction == CallDirection.INCOMING) "Incoming call..." else "Ringing..."
    CallStatus.CONNECTING -> if (direction == CallDirection.OUTGOING && peerName != null) "Connecting to $peerName..." else "Connecting..."
    CallStatus.CONNECTED -> "Connected"
    CallStatus.ENDED -> "Call ended"
    CallStatus.FAILED -> if (wasConnected) "Connection lost" else "Call failed"
    CallStatus.NO_RELAY -> "Call failed — relay unavailable"
    CallStatus.NO_NETWORK -> "No internet connection"
    CallStatus.REJECTED -> "Call declined"
    CallStatus.BUSY -> "Busy"
    CallStatus.UNAVAILABLE -> "Contact unavailable"
    CallStatus.PEER_GONE -> if (peerName != null) "$peerName's phone stopped responding" else "The other phone stopped responding"
    else -> ""
}


/** Verify by phone: comparing six digits with the other person (CallVerification, ADR-31). */
@Composable
private fun CallVerifyDialog(
    verify: com.sotto.app.domain.model.CallVerifyState,
    name: String,
    onStart: () -> Unit,
    onAnswer: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val step = verify.step
    val colors = MaterialTheme.colorScheme
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                when (step) {
                    com.sotto.app.domain.model.CallVerifyState.Step.MATCHED -> Icons.Rounded.CheckCircle
                    com.sotto.app.domain.model.CallVerifyState.Step.DIFFERENT -> Icons.Rounded.WarningAmber
                    else -> Icons.Rounded.VerifiedUser
                },
                contentDescription = null,
                tint = when (step) {
                    com.sotto.app.domain.model.CallVerifyState.Step.MATCHED -> Aurora.colors.success
                    com.sotto.app.domain.model.CallVerifyState.Step.DIFFERENT -> colors.error
                    else -> colors.primary
                },
            )
        },
        title = {
            Text(
                when (step) {
                    com.sotto.app.domain.model.CallVerifyState.Step.ASKED -> "$name wants to verify by phone"
                    com.sotto.app.domain.model.CallVerifyState.Step.CODE -> "Read these digits to $name"
                    com.sotto.app.domain.model.CallVerifyState.Step.MATCHED -> "$name is verified by phone"
                    com.sotto.app.domain.model.CallVerifyState.Step.DIFFERENT -> "The codes don't match"
                    com.sotto.app.domain.model.CallVerifyState.Step.UNAVAILABLE -> "Can't verify by phone now"
                    else -> "Verify by phone"
                },
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (step == com.sotto.app.domain.model.CallVerifyState.Step.CODE && verify.code != null) {
                    Text(
                        verify.code,
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    when (step) {
                        com.sotto.app.domain.model.CallVerifyState.Step.WAITING ->
                            "Waiting for $name to tap Verify by phone too. If nothing happens, their Sotto may be too old."
                        com.sotto.app.domain.model.CallVerifyState.Step.ASKED ->
                            "You'll both see the same six digits if nobody is listening in on this call. Read them to each other to check."
                        com.sotto.app.domain.model.CallVerifyState.Step.EXCHANGING -> "Working out the code…"
                        com.sotto.app.domain.model.CallVerifyState.Step.CODE ->
                            "Is it the same on $name's phone? Only say yes if you're sure it's $name's voice."
                        com.sotto.app.domain.model.CallVerifyState.Step.MATCHED ->
                            "The chat says \"Verified by phone only\": a call is weaker proof than meeting. " +
                                "When you meet, verify in person too."
                        com.sotto.app.domain.model.CallVerifyState.Step.DIFFERENT ->
                            "Someone may be in the middle of this call, such as the relay. Don't trust this chat " +
                                "until you've met $name and verified in person."
                        com.sotto.app.domain.model.CallVerifyState.Step.UNAVAILABLE ->
                            "$name needs to be a saved contact whose keys this phone has checked. Try again later."
                    },
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            when (step) {
                com.sotto.app.domain.model.CallVerifyState.Step.ASKED ->
                    androidx.compose.material3.TextButton(onClick = onStart) { Text("Verify") }
                com.sotto.app.domain.model.CallVerifyState.Step.CODE ->
                    androidx.compose.material3.TextButton(onClick = { onAnswer(true) }) { Text("It matches") }
                com.sotto.app.domain.model.CallVerifyState.Step.WAITING,
                com.sotto.app.domain.model.CallVerifyState.Step.EXCHANGING ->
                    androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") }
                else -> androidx.compose.material3.TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = when (step) {
            com.sotto.app.domain.model.CallVerifyState.Step.ASKED -> {
                { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Not now") } }
            }
            com.sotto.app.domain.model.CallVerifyState.Step.CODE -> {
                { androidx.compose.material3.TextButton(onClick = { onAnswer(false) }) { Text("It's different") } }
            }
            else -> null
        },
    )
}
