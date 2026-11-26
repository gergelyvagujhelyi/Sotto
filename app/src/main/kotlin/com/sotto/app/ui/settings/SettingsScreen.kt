package com.sotto.app.ui.settings

import android.app.LocaleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.RingVolume
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.sotto.app.BuildConfig
import com.sotto.app.R
import com.sotto.app.domain.model.BlockedUser
import com.sotto.app.domain.model.DisappearingDuration
import com.sotto.app.domain.model.DisplayNames
import com.sotto.app.domain.model.LockScreenPreviewMode
import com.sotto.app.domain.model.PrivacySettings
import com.sotto.app.notification.PickSound
import com.sotto.app.notification.SoundKind
import com.sotto.app.notification.SoundPickerRequest
import com.sotto.app.ui.components.AppSnackbarHost
import com.sotto.app.ui.components.AuroraBackground
import com.sotto.app.ui.components.AuroraStyle
import com.sotto.app.ui.components.AuroraTextField
import com.sotto.app.ui.components.CollapsingTitleTopBar
import com.sotto.app.ui.components.GlassCard
import com.sotto.app.ui.components.GradientAvatar
import com.sotto.app.ui.components.QrCodeImage
import com.sotto.app.ui.components.SectionLabel
import com.sotto.app.ui.components.ShareCodeActions
import com.sotto.app.ui.components.SoftButton
import com.sotto.app.ui.text.asString
import com.sotto.app.ui.text.resolve
import com.sotto.app.ui.theme.Aurora

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
    // No live code for a few seconds in a row (no signing key right now): the share code's QR
    // stands in, rather than a spinner that never ends.
    var noLiveQr by remember { mutableStateOf(false) }
    val qrLifecycle = LocalLifecycleOwner.current
    LaunchedEffect(qrLifecycle) {
        qrLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            try {
                var misses = 0
                while (true) {
                    val code = viewModel.liveQr()
                    if (code != null) {
                        liveQr = code
                        misses = 0
                        noLiveQr = false
                    } else if (++misses >= 3) {
                        // A code from seconds ago still verifies (60 s), but don't show one for long.
                        liveQr = null
                        noLiveQr = true
                    }
                    kotlinx.coroutines.delay(1000)
                }
            } finally {
                com.sotto.app.ui.contacts.LiveQrWatch.hidden()
            }
        }
    }
    val showWipeConfirmation by viewModel.showWipeConfirmation.collectAsState()
    val groupsToHandOver by viewModel.groupsToHandOver.collectAsState()
    var handOver by remember { mutableStateOf(true) }
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
        android.widget.Toast.makeText(inviteContext, message.resolve(inviteContext), android.widget.Toast.LENGTH_LONG).show()
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
            title = { Text(stringResource(R.string.settings_wipe_unreachable_title)) },
            text = { Text(error.asString()) },
            confirmButton = {
                TextButton(onClick = { viewModel.retryWipe() }) {
                    Text(stringResource(R.string.settings_wipe_retry))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.forceWipeLocal() }) {
                    Text(stringResource(R.string.settings_wipe_anyway), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }

    if (showRotateConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::cancelRotateKeys,
            icon = { Icon(Icons.Rounded.Autorenew, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_rotate_title)) },
            text = { Text(stringResource(R.string.settings_rotate_text)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRotateKeys) {
                    Text(stringResource(R.string.settings_rotate_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRotateKeys) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    rotationError?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRotationError,
            title = { Text(stringResource(R.string.settings_rotate_failed_title)) },
            text = { Text(error.asString()) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissRotationError) {
                    Text(stringResource(R.string.common_ok))
                }
            },
        )
    }

    pendingRetentionDays?.let { days ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRetention,
            icon = { Icon(Icons.Rounded.History, contentDescription = null) },
            title = {
                Text(
                    if (days == 365) stringResource(R.string.settings_retention_title_year)
                    else pluralStringResource(R.plurals.settings_retention_title_days, days, days)
                )
            },
            text = {
                Text(
                    if (days == 365) stringResource(R.string.settings_retention_text_year)
                    else pluralStringResource(R.plurals.settings_retention_text_days, days, days)
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRetention) {
                    Text(stringResource(R.string.settings_retention_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRetention) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showWipeConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::cancelWipe,
            icon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.settings_wipe_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_wipe_text))
                    if (groupsToHandOver > 0) {
                        Spacer(Modifier.height(12.dp))
                        Text(androidx.compose.ui.res.pluralStringResource(R.plurals.group_handover_note, groupsToHandOver, groupsToHandOver))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { handOver = !handOver }) {
                            Checkbox(checked = handOver, onCheckedChange = { handOver = it })
                            Text(stringResource(R.string.group_handover_checkbox), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmWipe(handOver) }) {
                    Text(stringResource(R.string.settings_wipe_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelWipe) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    val context = LocalContext.current
    // Whether Android may still stop the app in the background (battery optimisation on).
    fun isBatteryOptimised() = com.sotto.app.ui.components.BackgroundActivity.isRestricted(context)
    var batteryOptimised by remember { mutableStateOf(isBatteryOptimised()) }
    // Re-checked when coming back from Android's dialog or its settings.
    LifecycleResumeEffect(Unit) {
        batteryOptimised = isBatteryOptimised()
        // A sound may have been changed in Android's settings meanwhile.
        viewModel.refreshSounds()
        onPauseOrDispose { }
    }
    val sounds by viewModel.sounds.collectAsState()
    // Which sound the picker is open for; saved, as the app may be recreated meanwhile.
    var pickingSound by rememberSaveable { mutableStateOf<SoundKind?>(null) }
    val soundPicker = rememberLauncherForActivityResult(PickSound()) { picked ->
        val kind = pickingSound
        pickingSound = null
        if (picked != null && kind != null) viewModel.setSound(kind, picked.uri)
    }
    fun pickSound(kind: SoundKind) {
        val title = context.getString(
            when (kind) {
                SoundKind.MESSAGES -> R.string.settings_sound_messages
                SoundKind.GROUPS -> R.string.settings_sound_groups
                SoundKind.CALLS -> R.string.settings_sound_calls
            },
        )
        pickingSound = kind
        try {
            soundPicker.launch(SoundPickerRequest(kind, sounds?.get(kind)?.uri, title))
        } catch (_: ActivityNotFoundException) {
            pickingSound = null
            android.widget.Toast.makeText(context, R.string.settings_sound_no_picker, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val noGooglePush = remember {
        GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS
    }

    // Asks Android to stop optimising the app's battery use, so neither Doze nor a phone
    // maker's battery saver holds messages, calls or the background connection back.
    fun allowBackgroundActivity() = com.sotto.app.ui.components.BackgroundActivity.requestUnrestricted(context)

    SettingsContent(
        settings = settings,
        shareCode = shareCode,
        liveQr = liveQr,
        noLiveQr = noLiveQr,
        myName = myName,
        isRotatingKeys = isRotatingKeys,
        onBack = onBack,
        onEditName = { editingName = true },
        onInvite = viewModel::createInvite,
        relayHost = viewModel.relayHost,
        onRotateKeys = viewModel::requestRotateKeys,
        onLockScreenPreviewChange = viewModel::updateLockScreenPreview,
        sounds = sounds,
        onPickSound = ::pickSound,
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
    noLiveQr: Boolean = false,
    myName: String?,
    isRotatingKeys: Boolean,
    onBack: () -> Unit,
    onEditName: () -> Unit,
    onInvite: () -> Unit = {},
    /** The relay the account lives on; null hides the row. */
    relayHost: String? = null,
    onRotateKeys: () -> Unit,
    onLockScreenPreviewChange: (LockScreenPreviewMode) -> Unit,
    /** The sounds to show in Settings > Sounds; null hides the section until they're read. */
    sounds: Sounds? = null,
    onPickSound: (SoundKind) -> Unit = {},
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
                    title = stringResource(R.string.common_settings),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
                SectionLabel(stringResource(R.string.settings_section_identity), Modifier.padding(start = 4.dp, top = 4.dp))
                IdentityCard(
                    shareCode = shareCode,
                    liveQr = liveQr,
                    noLiveQr = noLiveQr,
                    isRotatingKeys = isRotatingKeys,
                    snackbarHostState = snackbarHostState,
                )
                Spacer(Modifier.height(12.dp))
                SettingsGroup {
                    SettingsRow(
                        icon = Icons.Rounded.Badge,
                        title = stringResource(R.string.settings_your_name),
                        subtitle = myName ?: stringResource(R.string.settings_name_not_set),
                        onClick = onEditName,
                    )
                    RowDivider()
                    SettingsRow(
                        icon = Icons.Rounded.PersonAdd,
                        title = stringResource(R.string.settings_invite_title),
                        subtitle = stringResource(R.string.settings_invite_subtitle),
                        onClick = onInvite,
                    )
                }

                // Notification privacy
                SectionLabel(stringResource(R.string.settings_section_lock_screen), Modifier.padding(start = 4.dp))
                val previewLabels = mapOf(
                    LockScreenPreviewMode.SHOW_ALL to stringResource(R.string.settings_preview_show_all),
                    LockScreenPreviewMode.SENDER_ONLY to stringResource(R.string.settings_preview_sender_only),
                    LockScreenPreviewMode.HIDE_BODY to stringResource(R.string.settings_preview_hide_body),
                    LockScreenPreviewMode.HIDE_ALL to stringResource(R.string.settings_preview_hide_all),
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

                sounds?.let { sounds ->
                    SectionLabel(stringResource(R.string.settings_section_sounds), Modifier.padding(start = 4.dp))
                    SettingsGroup {
                        SettingsRow(
                            icon = Icons.Rounded.Notifications,
                            title = stringResource(R.string.settings_sound_messages),
                            subtitle = sounds.messages.name.asString(),
                            onClick = { onPickSound(SoundKind.MESSAGES) },
                        )
                        RowDivider()
                        SettingsRow(
                            icon = Icons.Rounded.Groups,
                            title = stringResource(R.string.settings_sound_groups),
                            subtitle = sounds.groups.name.asString(),
                            onClick = { onPickSound(SoundKind.GROUPS) },
                        )
                        RowDivider()
                        SettingsRow(
                            icon = Icons.Rounded.RingVolume,
                            title = stringResource(R.string.settings_sound_calls),
                            subtitle = sounds.calls.name.asString(),
                            onClick = { onPickSound(SoundKind.CALLS) },
                        )
                    }
                }

                // Background connection
                SectionLabel(stringResource(R.string.settings_section_connection), Modifier.padding(start = 4.dp))
                SettingsGroup {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.WifiTethering,
                        title = stringResource(R.string.settings_background_connection),
                        subtitle = listOfNotNull(
                            stringResource(R.string.settings_background_connection_subtitle),
                            when {
                                noRelayPush -> stringResource(R.string.settings_background_recommended_no_relay_push)
                                backgroundConnectionRecommended -> stringResource(R.string.settings_background_recommended_no_google_push)
                                else -> null
                            },
                        ).joinToString(" "),
                        checked = settings.backgroundConnection,
                        onCheckedChange = onBackgroundConnectionChange,
                    )
                    // Not only for the background connection: push-woken messages wait too while
                        // Android (or a phone maker's battery saver) holds Sotto back.
                    if (batteryOptimised) {
                        RowDivider()
                        SettingsRow(
                            icon = Icons.Rounded.BatteryAlert,
                            title = stringResource(R.string.settings_allow_background),
                            subtitle = stringResource(R.string.settings_allow_background_subtitle),
                            onClick = onAllowBackgroundActivity,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (relayHost != null) {
                        RowDivider()
                        SettingsInfoRow(
                            icon = Icons.Rounded.Dns,
                            title = stringResource(R.string.settings_relay),
                            subtitle = stringResource(R.string.settings_relay_subtitle, relayHost),
                        )
                    }
                }

                SectionLabel(stringResource(R.string.settings_section_calls), Modifier.padding(start = 4.dp))
                SettingsGroup {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.GraphicEq,
                        title = stringResource(R.string.settings_hq_calls),
                        subtitle = stringResource(R.string.settings_hq_calls_subtitle),
                        checked = settings.highQualityCalls,
                        onCheckedChange = onHighQualityCallsChange,
                    )
                    RowDivider()
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Hearing,
                        title = stringResource(R.string.settings_level_voices),
                        subtitle = stringResource(R.string.settings_level_voices_subtitle),
                        checked = settings.levelVoices,
                        onCheckedChange = onLevelVoicesChange,
                    )
                    RowDivider()
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Tune,
                        title = stringResource(R.string.settings_webrtc_voice),
                        subtitle = stringResource(R.string.settings_webrtc_voice_subtitle),
                        checked = settings.webRtcVoiceProcessing,
                        onCheckedChange = onWebRtcVoiceProcessingChange,
                    )
                }

                // Privacy
                SectionLabel(stringResource(R.string.settings_section_privacy), Modifier.padding(start = 4.dp))
                SettingsGroup {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.DoneAll,
                        title = stringResource(R.string.settings_read_receipts),
                        subtitle = stringResource(R.string.settings_read_receipts_subtitle),
                        checked = settings.readReceipts,
                        onCheckedChange = onReadReceiptsChange,
                    )
                    RowDivider()
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Screenshot,
                        title = stringResource(R.string.settings_screen_security),
                        subtitle = stringResource(R.string.settings_screen_security_subtitle),
                        checked = settings.screenSecurity,
                        onCheckedChange = onScreenSecurityChange,
                    )
                    RowDivider()
                    SettingsSwitchRow(
                        icon = Icons.Rounded.SwapCalls,
                        title = stringResource(R.string.settings_direct_calls),
                        subtitle = stringResource(R.string.settings_direct_calls_subtitle),
                        checked = settings.directCalls,
                        onCheckedChange = onDirectCallsChange,
                    )
                }

                SectionLabel(stringResource(R.string.settings_section_display), Modifier.padding(start = 4.dp))
                SettingsGroup {
                    // Android 13+ keeps a language per app; before that, or on a phone without
                    // Android's page for it, the phone's language applies and there's no row.
                    val context = LocalContext.current
                    val languagePage = remember(context) { languageSettingsIntent(context) }
                    if (languagePage != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        LanguageRow(languagePage)
                        RowDivider()
                    }
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Animation,
                        title = stringResource(R.string.settings_reduce_animations),
                        subtitle = stringResource(R.string.settings_reduce_animations_subtitle),
                        checked = settings.reduceAnimations,
                        onCheckedChange = onReduceAnimationsChange,
                    )
                }

                // Message history retention
                SectionLabel(stringResource(R.string.settings_section_history), Modifier.padding(start = 4.dp))
                SettingsGroup(Modifier.selectableGroup()) {
                    RETENTION_OPTIONS.forEachIndexed { index, days ->
                        if (index > 0) RowDivider(inset = 56.dp)
                        SettingsRadioRow(
                            title = keepLabel(days),
                            selected = settings.localDataRetentionDays == days,
                            onClick = { onRetentionChange(days) },
                        )
                    }
                }

                // Disappearing messages
                SectionLabel(stringResource(R.string.settings_section_disappearing), Modifier.padding(start = 4.dp))
                DisappearingChips(
                    selected = settings.disappearingMessagesDefault,
                    onSelect = onDisappearingChange,
                )

                // Blocked users
                SectionLabel(stringResource(R.string.settings_section_blocked), Modifier.padding(start = 4.dp))
                SettingsGroup {
                    if (blockedUsers.isEmpty()) {
                        SettingsInfoRow(
                            icon = Icons.Rounded.Block,
                            title = stringResource(R.string.settings_none_blocked),
                            subtitle = stringResource(R.string.settings_none_blocked_subtitle),
                        )
                    }
                    blockedUsers.forEachIndexed { index, user ->
                        if (index > 0) RowDivider()
                        BlockedUserRow(user = user, onUnblock = { onUnblock(user.userId) })
                    }
                }

                // Rare and disruptive for every contact, so kept away from the everyday rows
                // above: it sat right under "Your name", one mistap away.
                SectionLabel(stringResource(R.string.settings_section_keys), Modifier.padding(start = 4.dp))
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
                            Text(stringResource(R.string.settings_rotating_keys), style = MaterialTheme.typography.bodyLarge)
                        }
                    } else {
                        SettingsRow(
                            icon = Icons.Rounded.Autorenew,
                            title = stringResource(R.string.settings_rotate_keys),
                            subtitle = stringResource(R.string.settings_rotate_keys_subtitle),
                            onClick = onRotateKeys,
                        )
                    }
                }

                // Danger zone
                SectionLabel(stringResource(R.string.settings_section_data), Modifier.padding(start = 4.dp))
                SettingsGroup(borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f)) {
                    SettingsRow(
                        icon = Icons.Rounded.DeleteForever,
                        tint = MaterialTheme.colorScheme.error,
                        title = stringResource(R.string.settings_wipe),
                        titleColor = MaterialTheme.colorScheme.error,
                        subtitle = stringResource(R.string.settings_wipe_subtitle),
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
    noLiveQr: Boolean = false,
) {
    val aurora = Aurora.colors
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.5.dp, Brush.linearGradient(aurora.brand.map { it.copy(alpha = 0.8f) })),
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Only the live code: the share code's QR (shown here until the first live code was
            // ready) is a different code, and swapping one for the other looked like a glitch.
            Box(
                Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White)
                    .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                val qr = liveQr ?: shareCode?.takeIf { noLiveQr }
                if (qr != null) {
                    // Live: scanned in person it adds and verifies you; a picture of it only adds you.
                    QrCodeImage(content = qr, size = 184.dp)
                } else {
                    Box(Modifier.size(184.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp, color = Color(0xFF6B6B80))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.settings_your_share_code),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = shareCode ?: stringResource(R.string.settings_loading),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                textAlign = TextAlign.Center,
            )
            if (liveQr != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_live_qr_hint),
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
        title = { Text(stringResource(R.string.settings_your_name)) },
        text = {
            Column {
                AuroraTextField(
                    value = name,
                    onValueChange = { if (it.codePointCount(0, it.length) <= DisplayNames.MAX_LENGTH) name = it },
                    placeholder = stringResource(R.string.settings_your_name),
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
                    text = stringResource(R.string.settings_name_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
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
        SoftButton(text = stringResource(R.string.settings_unblock), onClick = onUnblock, compact = true)
    }
}

private val RETENTION_OPTIONS = listOf(0, 365, 90, 30)

/** A history option: kept forever, for a year, or for some days. */
@Composable
private fun keepLabel(days: Int): String = when (days) {
    0 -> stringResource(R.string.settings_keep_forever)
    365 -> stringResource(R.string.settings_keep_year)
    else -> pluralStringResource(R.plurals.settings_keep_days, days, days)
}

/**
 * The app's own language (Android 13+), shown by name; a tap opens Android's per-app language
 * page, which lists the languages Sotto has (the build generates that list).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun LanguageRow(open: Intent) {
    val context = LocalContext.current
    // Null while the app follows the phone's language.
    fun appLanguage(): String? = context.getSystemService(LocaleManager::class.java)
        ?.applicationLocales
        ?.takeUnless { it.isEmpty }
        ?.get(0)
        ?.let { locale -> locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) } }
    var language by remember { mutableStateOf(appLanguage()) }
    // Re-read when coming back from Android's page (choosing the phone's language again may
    // not restart the screen).
    LifecycleResumeEffect(Unit) {
        language = appLanguage()
        onPauseOrDispose { }
    }
    SettingsRow(
        icon = Icons.Rounded.Language,
        title = stringResource(R.string.settings_language),
        subtitle = language ?: stringResource(R.string.settings_language_phone),
        onClick = {
            try {
                context.startActivity(open)
            } catch (_: ActivityNotFoundException) {
                // Gone since the screen checked: nothing to open.
            }
        },
    )
}

/** Android's per-app language page for Sotto (Android 13+), or null where the phone has none. */
private fun languageSettingsIntent(context: Context): Intent? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val intent = Intent(
        android.provider.Settings.ACTION_APP_LOCALE_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    return intent.takeIf { it.resolveActivity(context.packageManager) != null }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DisappearingChips(
    selected: DisappearingDuration,
    onSelect: (DisappearingDuration) -> Unit,
) {
    val labels = mapOf(
        DisappearingDuration.OFF to stringResource(R.string.common_off),
        DisappearingDuration.MINUTES_5 to pluralStringResource(R.plurals.settings_minutes, 5, 5),
        DisappearingDuration.HOURS_1 to pluralStringResource(R.plurals.settings_hours, 1, 1),
        DisappearingDuration.HOURS_24 to pluralStringResource(R.plurals.settings_hours, 24, 24),
        DisappearingDuration.DAYS_7 to pluralStringResource(R.plurals.settings_days, 7, 7),
        DisappearingDuration.DAYS_30 to pluralStringResource(R.plurals.settings_days, 30, 30),
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
                stringResource(R.string.settings_footer_encrypted),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.settings_footer_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
