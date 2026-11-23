package com.sotto.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.PowerManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.sotto.app.domain.model.DisplayNames
import com.sotto.app.ui.components.AuroraTextField
import com.sotto.app.ui.components.CollapsingTitleTopBar
import com.sotto.app.ui.components.ShareCodeActions
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.History
import androidx.compose.ui.text.style.TextOverflow
import com.sotto.app.domain.model.BlockedUser
import com.sotto.app.ui.components.GradientAvatar
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sotto.app.BuildConfig
import com.sotto.app.domain.model.DisappearingDuration
import com.sotto.app.domain.model.LockScreenPreviewMode
import com.sotto.app.domain.model.PrivacySettings
import com.sotto.app.ui.components.AppSnackbarHost
import com.sotto.app.ui.components.AuroraBackground
import com.sotto.app.ui.components.AuroraStyle
import com.sotto.app.ui.components.GlassCard
import com.sotto.app.ui.components.QrCodeImage
import com.sotto.app.ui.components.SectionLabel
import com.sotto.app.ui.components.SoftButton
import com.sotto.app.ui.theme.Aurora
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onWiped: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.privacySettings.collectAsState()
    val shareCode by viewModel.shareCode.collectAsState()
    // The identity QR code changes every second while Settings is in front (BankID-style).
    var liveQr by remember { mutableStateOf<String?>(null) }
    val qrLifecycle = LocalLifecycleOwner.current
    LaunchedEffect(qrLifecycle) {
        qrLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            try {
                while (true) {
                    liveQr = viewModel.liveQr()
                    kotlinx.coroutines.delay(1000)
                }
            } finally {
                com.sotto.app.ui.contacts.LiveQrWatch.hidden()
            }
        }
    }
    val showWipeConfirmation by viewModel.showWipeConfirmation.collectAsState()
    val wipeCompleted by viewModel.wipeCompleted.collectAsState()
    val showRotateConfirmation by viewModel.showRotateConfirmation.collectAsState()
    val isRotatingKeys by viewModel.isRotatingKeys.collectAsState()
    val rotationError by viewModel.rotationError.collectAsState()
    val wipeError by viewModel.wipeError.collectAsState()
    val pendingRetentionDays by viewModel.pendingRetentionDays.collectAsState()
    val blockedUsers by viewModel.blockedUsers.collectAsState()
    val myName by viewModel.myName.collectAsState()
    val invite by viewModel.invite.collectAsState()
    val inviteError by viewModel.inviteError.collectAsState()
    invite?.let { InviteSheet(it, onDismiss = viewModel::dismissInvite) }
    val inviteContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(inviteError) {
        val message = inviteError ?: return@LaunchedEffect
        android.widget.Toast.makeText(inviteContext, message, android.widget.Toast.LENGTH_LONG).show()
        viewModel.inviteErrorShown()
    }
    var editingName by rememberSaveable { mutableStateOf(false) }

    if (editingName) {
        EditMyNameDialog(
            currentName = myName,
            onSave = { name ->
                editingName = false
                viewModel.setMyName(name)
            },
            onDismiss = { editingName = false },
        )
    }

    // Navigate to onboarding after wipe — delay if there's a warning to show
    LaunchedEffect(wipeCompleted) {
        if (wipeCompleted && wipeError == null) onWiped()
    }

    wipeError?.let { error ->
        AlertDialog(
            onDismissRequest = { /* must choose an action */ },
            title = { Text("Server unreachable") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.retryWipe() }) {
                    Text("Retry")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.forceWipeLocal() }) {
                    Text("Delete Anyway", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }

    if (showRotateConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::cancelRotateKeys,
            icon = { Icon(Icons.Rounded.Autorenew, contentDescription = null) },
            title = { Text("Rotate identity keys?") },
            text = {
                Text(
                    "This generates new signing and encryption keys and a new share code. " +
                        "Every contact sees that your keys changed and has to accept them " +
                        "before your chats continue. Only needed if you think your keys " +
                        "were exposed."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRotateKeys) {
                    Text("Rotate")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRotateKeys) {
                    Text("Cancel")
                }
            },
        )
    }

    rotationError?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRotationError,
            title = { Text("Key rotation failed") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissRotationError) {
                    Text("OK")
                }
            },
        )
    }

    pendingRetentionDays?.let { days ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRetention,
            icon = { Icon(Icons.Rounded.History, contentDescription = null) },
            title = { Text("Keep messages for ${retentionLabel(days)}?") },
            text = {
                Text(
                    "Messages older than ${retentionLabel(days)} are deleted from this device now, " +
                        "and from then on as they reach that age. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRetention) {
                    Text("Delete older messages", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRetention) { Text("Cancel") }
            },
        )
    }

    if (showWipeConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::cancelWipe,
            icon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Wipe all local data?") },
            text = {
                Text(
                    "This will permanently delete your identity, all messages, " +
                        "contacts, and keys from this device. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmWipe() }) {
                    Text("Wipe", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelWipe) {
                    Text("Cancel")
                }
            },
        )
    }

    val context = LocalContext.current
    // Whether Android may still stop the app in the background (battery optimisation on).
    fun isBatteryOptimised() =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == false
    var batteryOptimised by remember { mutableStateOf(isBatteryOptimised()) }
    // Re-checked when coming back from Android's dialog or its settings.
    LifecycleResumeEffect(Unit) {
        batteryOptimised = isBatteryOptimised()
        onPauseOrDispose { }
    }
    val noGooglePush = remember {
        GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS
    }

    // Asks Android to stop optimising the app's battery use, so Doze doesn't cut the
    // background connection off. Lint flags this request (BatteryLife); a chat app keeping its
    // own connection on phones without Google push is what the exemption is meant for.
    // app/lint.xml says so to lint: it ignored a @SuppressLint on this local function.
    fun allowBackgroundActivity() {
        val request = Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:${context.packageName}".toUri(),
        )
        try {
            context.startActivity(request)
        } catch (e: ActivityNotFoundException) {
            runCatching {
                context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    SettingsContent(
        settings = settings,
        shareCode = shareCode,
        liveQr = liveQr,
        myName = myName,
        isRotatingKeys = isRotatingKeys,
        onBack = onBack,
        onEditName = { editingName = true },
        onInvite = viewModel::createInvite,
        relayHost = viewModel.relayHost,
        onRotateKeys = viewModel::requestRotateKeys,
        onLockScreenPreviewChange = viewModel::updateLockScreenPreview,
        onReadReceiptsChange = viewModel::updateReadReceipts,
        onScreenSecurityChange = viewModel::updateScreenSecurity,
        onDirectCallsChange = viewModel::updateDirectCalls,
        onHighQualityCallsChange = viewModel::updateHighQualityCalls,
        onLevelVoicesChange = viewModel::updateLevelVoices,
        onWebRtcVoiceProcessingChange = viewModel::updateWebRtcVoiceProcessing,
        onReduceAnimationsChange = viewModel::updateReduceAnimations,
        onBackgroundConnectionChange = { on ->
            viewModel.updateBackgroundConnection(on)
            if (on && batteryOptimised) allowBackgroundActivity()
        },
        backgroundConnectionRecommended = noGooglePush,
        noRelayPush = !viewModel.relayCanPush,
        batteryOptimised = batteryOptimised,
        onAllowBackgroundActivity = { allowBackgroundActivity() },
        onDisappearingChange = viewModel::updateDisappearingMessages,
        onRetentionChange = viewModel::requestRetention,
        blockedUsers = blockedUsers,
        onUnblock = viewModel::unblock,
        onWipe = viewModel::requestWipe,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    settings: PrivacySettings,
    shareCode: String?,
    /** The live QR code (changes every second); null while it's made, or in previews. */
    liveQr: String? = null,
    myName: String?,
    isRotatingKeys: Boolean,
    onBack: () -> Unit,
    onEditName: () -> Unit,
    onInvite: () -> Unit = {},
    /** The relay the account lives on; null hides the row. */
    relayHost: String? = null,
    onRotateKeys: () -> Unit,
    onLockScreenPreviewChange: (LockScreenPreviewMode) -> Unit,
    onReadReceiptsChange: (Boolean) -> Unit,
    onScreenSecurityChange: (Boolean) -> Unit,
    onDirectCallsChange: (Boolean) -> Unit,
    onHighQualityCallsChange: (Boolean) -> Unit = {},
    onLevelVoicesChange: (Boolean) -> Unit = {},
    onWebRtcVoiceProcessingChange: (Boolean) -> Unit = {},
    onReduceAnimationsChange: (Boolean) -> Unit = {},
    onBackgroundConnectionChange: (Boolean) -> Unit,
    /** No Google push on this phone, so the background connection is how messages arrive. */
    backgroundConnectionRecommended: Boolean,
    /** The account's relay can't push to this app, so the background connection is how messages arrive. */
    noRelayPush: Boolean = false,
    /** Android may still stop the app in the background (battery optimisation is on). */
    batteryOptimised: Boolean,
    onAllowBackgroundActivity: () -> Unit,
    onDisappearingChange: (DisappearingDuration) -> Unit,
    onRetentionChange: (days: Int) -> Unit,
    blockedUsers: List<BlockedUser>,
    onUnblock: (userId: String) -> Unit,
    onWipe: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }

    AuroraBackground(Modifier.fillMaxSize(), style = AuroraStyle.Header) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            snackbarHost = { AppSnackbarHost(snackbarHostState) },
            topBar = {
                CollapsingTitleTopBar(
                    title = "Settings",
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                // Identity section
                SectionLabel("Identity", Modifier.padding(start = 4.dp, top = 4.dp))
                IdentityCard(
                    shareCode = shareCode,
                    liveQr = liveQr,
                    isRotatingKeys = isRotatingKeys,
                    snackbarHostState = snackbarHostState,
                )
                Spacer(Modifier.height(12.dp))
                SettingsGroup {
                    SettingsRow(
                        icon = Icons.Rounded.Badge,
                        title = "Your name",
                        subtitle = myName ?: "Not set \u2014 new contacts see your ID",
                        onClick = onEditName,
                    )
                    RowDivider()
                    SettingsRow(
                        icon = Icons.Rounded.PersonAdd,
                        title = "Invite someone",
                        subtitle = "A one-time code to join your relay, valid for 7 days",
                        onClick = onInvite,
                    )
                }

                // Notification privacy
                SectionLabel("Lock screen notifications", Modifier.padding(start = 4.dp))
                val previewLabels = mapOf(
                    LockScreenPreviewMode.SHOW_ALL to "Show sender and message",
                    LockScreenPreviewMode.SENDER_ONLY to "Show sender only",
                    LockScreenPreviewMode.HIDE_BODY to "Hide message body",
                    LockScreenPreviewMode.HIDE_ALL to "No lock screen preview",
                )
                SettingsGroup(Modifier.selectableGroup()) {
                    LockScreenPreviewMode.entries.forEachIndexed { index, mode ->
                        if (index > 0) RowDivider(inset = 56.dp)
                        SettingsRadioRow(
                            title = previewLabels[mode] ?: mode.name,
                            selected = settings.lockScreenPreview == mode,
                            onClick = { onLockScreenPreviewChange(mode) },
                        )
                    }
                }

                // Background connection
                SectionLabel("Connection", Modifier.padding(start = 4.dp))
                SettingsGroup {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.WifiTethering,
                        title = "Background connection",
                        subtitle = "Get messages and calls while the app is closed. Shows a permanent " +
                            "notification and uses more battery." +
                            when {
                                noRelayPush -> " Recommended: your relay can't wake the app with push."
                                backgroundConnectionRecommended -> " Recommended on this phone: it has no Google push."
                                else -> ""
                            },
                        checked = settings.backgroundConnection,
                        onCheckedChange = onBackgroundConnectionChange,
                    )
                    if (settings.backgroundConnection && batteryOptimised) {
                        RowDivider()
                        SettingsRow(
                            icon = Icons.Rounded.BatteryAlert,
                            title = "Allow background activity",
                            subtitle = "Until you do, Android can delay messages and calls",
                            onClick = onAllowBackgroundActivity,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (relayHost != null) {
                        RowDivider()
                        SettingsInfoRow(
                            icon = Icons.Rounded.Dns,
                            title = "Relay",
                            subtitle = "$relayHost \u00b7 to use another relay, wipe this app's data and set it up again",
                        )
                    }
                }

                SectionLabel("Calls", Modifier.padding(start = 4.dp))
                SettingsGroup {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.GraphicEq,
                        title = "High-quality calls",
                        subtitle = "Fuller sound in calls with someone who has this on too; the call screen " +
                            "then shows HD. Uses about a quarter more data.",
                        checked = settings.highQualityCalls,
                        onCheckedChange = onHighQualityCallsChange,
                    )
                    RowDivider()
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Hearing,
                        title = "Level quiet voices",
                        subtitle = "Lifts a quiet voice in a call to a normal level. Without it, Louder on " +
                            "the call screen lifts one person's voice, and is remembered for them.",
                        checked = settings.levelVoices,
                        onCheckedChange = onLevelVoicesChange,
                    )
                    RowDivider()
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Tune,
                        title = "WebRTC voice processing",
                        subtitle = "Your microphone goes through WebRTC's echo cancellation, noise suppression " +
                            "and volume instead of the phone's own. Try it if people hear an echo or you sound " +
                            "too quiet. From the next call.",
                        checked = settings.webRtcVoiceProcessing,
                        onCheckedChange = onWebRtcVoiceProcessingChange,
                    )
                }

                // Privacy
                SectionLabel("Privacy", Modifier.padding(start = 4.dp))
                SettingsGroup {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.DoneAll,
                        title = "Read receipts",
                        subtitle = "Let others know when you've read their messages. " +
                            "Works both ways: with this off you won't see when they read yours.",
                        checked = settings.readReceipts,
                        onCheckedChange = onReadReceiptsChange,
                    )
                    RowDivider()
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Screenshot,
                        title = "Screen security",
                        subtitle = "Block screenshots and app switcher preview",
                        checked = settings.screenSecurity,
                        onCheckedChange = onScreenSecurityChange,
                    )
                    RowDivider()
                    SettingsSwitchRow(
                        icon = Icons.Rounded.SwapCalls,
                        title = "Direct calls",
                        subtitle = "When a contact also has this on, call audio and files go straight between " +
                            "your phones instead of through the relay: better sound, less relay data, but each of " +
                            "you can see the other's IP addresses (internet, home network and mobile). Calls and " +
                            "files with people who aren't your contacts always use the relay.",
                        checked = settings.directCalls,
                        onCheckedChange = onDirectCallsChange,
                    )
                }

                SectionLabel("Display", Modifier.padding(start = 4.dp))
                SettingsGroup {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Animation,
                        title = "Reduce animations",
                        subtitle = "No send animation; backgrounds and the call screen stay still. " +
                            "Also on while Android's “Remove animations” is.",
                        checked = settings.reduceAnimations,
                        onCheckedChange = onReduceAnimationsChange,
                    )
                }

                // Message history retention
                SectionLabel("Message history", Modifier.padding(start = 4.dp))
                SettingsGroup(Modifier.selectableGroup()) {
                    RETENTION_OPTIONS.forEachIndexed { index, days ->
                        if (index > 0) RowDivider(inset = 56.dp)
                        SettingsRadioRow(
                            title = if (days == 0) "Keep forever" else "Keep for ${retentionLabel(days)}",
                            selected = settings.localDataRetentionDays == days,
                            onClick = { onRetentionChange(days) },
                        )
                    }
                }

                // Disappearing messages
                SectionLabel("Disappearing messages", Modifier.padding(start = 4.dp))
                DisappearingChips(
                    selected = settings.disappearingMessagesDefault,
                    onSelect = onDisappearingChange,
                )

                // Blocked users
                SectionLabel("Blocked users", Modifier.padding(start = 4.dp))
                SettingsGroup {
                    if (blockedUsers.isEmpty()) {
                        SettingsInfoRow(
                            icon = Icons.Rounded.Block,
                            title = "No one is blocked",
                            subtitle = "Block from a chat's menu or a message request",
                        )
                    }
                    blockedUsers.forEachIndexed { index, user ->
                        if (index > 0) RowDivider()
                        BlockedUserRow(user = user, onUnblock = { onUnblock(user.userId) })
                    }
                }

                // Rare and disruptive for every contact, so kept away from the everyday rows
                // above: it sat right under "Your name", one mistap away.
                SectionLabel("Keys", Modifier.padding(start = 4.dp))
                SettingsGroup {
                    if (isRotatingKeys) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(16.dp))
                            Text("Rotating keys...", style = MaterialTheme.typography.bodyLarge)
                        }
                    } else {
                        SettingsRow(
                            icon = Icons.Rounded.Autorenew,
                            title = "Rotate identity keys",
                            subtitle = "New keys and a new share code; every contact has to accept them",
                            onClick = onRotateKeys,
                        )
                    }
                }

                // Danger zone
                SectionLabel("Data", Modifier.padding(start = 4.dp))
                SettingsGroup(borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f)) {
                    SettingsRow(
                        icon = Icons.Rounded.DeleteForever,
                        tint = MaterialTheme.colorScheme.error,
                        title = "Wipe all local data",
                        titleColor = MaterialTheme.colorScheme.error,
                        subtitle = "Permanently delete everything on this device",
                        onClick = onWipe,
                    )
                }

                Footer()
            }
        }
    }
}

/**
 * The share code as a QR and as text, with copy and share actions. During a key
 * rotation the server already holds the new code while this one is still shown,
 * so copying and sharing wait until the rotation finishes.
 */
@Composable
private fun IdentityCard(
    shareCode: String?,
    isRotatingKeys: Boolean,
    snackbarHostState: SnackbarHostState,
    liveQr: String? = null,
) {
    val aurora = Aurora.colors
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.5.dp, Brush.linearGradient(aurora.brand.map { it.copy(alpha = 0.8f) })),
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val qr = liveQr ?: shareCode
            if (qr != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White)
                        .padding(6.dp),
                ) {
                    // Live: scanned in person it adds and verifies you; a picture of it only adds you.
                    QrCodeImage(content = qr, size = 184.dp)
                }
            } else {
                Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Your share code",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = shareCode ?: "Loading...",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                textAlign = TextAlign.Center,
            )
            if (liveQr != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "The QR code changes every second: scanned in person, it adds and verifies you. " +
                        "The share code, or a picture of the QR code, only adds you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (shareCode != null) {
                Spacer(Modifier.height(16.dp))
                ShareCodeActions(
                    shareCode = shareCode,
                    snackbarHostState = snackbarHostState,
                    enabled = !isRotatingKeys,
                )
            }
        }
    }
}

/**
 * Sets the user's own name. It goes end-to-end encrypted to the people they add or accept
 * from then on; the relay never sees it.
 */
@Composable
internal fun EditMyNameDialog(
    currentName: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(currentName.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Badge, contentDescription = null) },
        title = { Text("Your name") },
        text = {
            Column {
                AuroraTextField(
                    value = name,
                    onValueChange = { if (it.codePointCount(0, it.length) <= DisplayNames.MAX_LENGTH) name = it },
                    placeholder = "Your name",
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
                    text = "Sent end-to-end encrypted to people you add, and to people whose " +
                        "request you accept. The relay never sees it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SettingsGroup(
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        border = borderColor?.let { BorderStroke(1.dp, it) },
        contentPadding = PaddingValues(vertical = 4.dp),
        content = content,
    )
}

@Composable
private fun RowDivider(inset: androidx.compose.ui.unit.Dp = 68.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = inset, end = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Tinted rounded square holding a setting's icon. */
@Composable
private fun IconTile(icon: ImageVector, tint: Color) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.primary,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Rounded.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        // The row handles the toggle (and announces it); the switch is just the visual.
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingsRadioRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun SettingsInfoRow(icon: ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BlockedUserRow(user: BlockedUser, onUnblock: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GradientAvatar(name = user.displayName, seed = user.userId, size = 36.dp)
        Spacer(Modifier.width(16.dp))
        Text(
            text = user.displayName,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        SoftButton(text = "Unblock", onClick = onUnblock, compact = true)
    }
}

private val RETENTION_OPTIONS = listOf(0, 365, 90, 30)

private fun retentionLabel(days: Int): String = when (days) {
    365 -> "1 year"
    else -> "$days days"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DisappearingChips(
    selected: DisappearingDuration,
    onSelect: (DisappearingDuration) -> Unit,
) {
    val labels = mapOf(
        DisappearingDuration.OFF to "Off",
        DisappearingDuration.MINUTES_5 to "5 minutes",
        DisappearingDuration.HOURS_1 to "1 hour",
        DisappearingDuration.HOURS_24 to "24 hours",
        DisappearingDuration.DAYS_7 to "7 days",
        DisappearingDuration.DAYS_30 to "30 days",
    )
    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DisappearingDuration.entries.forEach { duration ->
                ChoiceChip(
                    label = labels[duration] ?: duration.name,
                    selected = selected == duration,
                    onClick = { onSelect(duration) },
                )
            }
        }
    }
}

/**
 * One option of a single-choice group, shaped as a chip. Announced as a radio
 * button (Material's FilterChip reads as a checkbox, which suggests several can
 * be on at once).
 */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val content = if (selected) colors.primary else colors.onSurface
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) colors.primary.copy(alpha = if (Aurora.colors.isDark) 0.22f else 0.12f) else Color.Transparent)
            .border(1.dp, if (selected) colors.primary.copy(alpha = 0.5f) else colors.outlineVariant, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

@Composable
private fun Footer() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "End-to-end encrypted",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Sotto ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
