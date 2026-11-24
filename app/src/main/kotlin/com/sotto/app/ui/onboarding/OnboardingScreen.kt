package com.sotto.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sotto.app.R
import com.sotto.app.domain.model.DisplayNames
import com.sotto.app.domain.model.KeyType
import com.sotto.app.ui.components.AuroraBackground
import com.sotto.app.ui.components.AuroraTextField
import com.sotto.app.ui.components.GlassCard
import com.sotto.app.ui.components.GlowOrb
import com.sotto.app.ui.components.GradientButton
import com.sotto.app.ui.components.GradientText
import com.sotto.app.ui.components.ShimmerText
import com.sotto.app.ui.components.SoftButton
import com.sotto.app.ui.components.TagChip
import com.sotto.app.ui.components.blockTouches
import com.sotto.app.ui.components.isSettled
import com.sotto.app.ui.text.UiText
import com.sotto.app.ui.text.asString
import com.sotto.app.ui.theme.Aurora

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onScanInvite: () -> Unit = {},
    /** An invite link the scanner read, until [onScannedInviteHandled]. */
    scannedInvite: String? = null,
    onScannedInviteHandled: () -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(scannedInvite) {
        val link = scannedInvite ?: return@LaunchedEffect
        onScannedInviteHandled()
        viewModel.submitRelayInput(link)
    }

    // Registered only while the notification step is showing, so a permission
    // result that arrives at any other time is dropped rather than acted on.
    val notificationPermission = if (state == OnboardingState.NotificationPermission) {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { _ ->
            viewModel.createGuestIdentity()
        }
    } else {
        null
    }

    if (state is OnboardingState.Complete) {
        // In an effect: calling it during composition navigated again on every
        // recomposition before the screen was gone.
        LaunchedEffect(Unit) { onComplete() }
    }

    OnboardingContent(
        state = state,
        onWelcomeContinue = viewModel::advanceToRelay,
        onRelaySubmit = viewModel::submitRelayInput,
        onScanInvite = onScanInvite,
        onRelayConfirm = viewModel::confirmRelay,
        onChooseAnotherRelay = viewModel::chooseAnotherRelay,
        onPrivacyContinue = viewModel::advanceToKeySelection,
        onKeySelected = viewModel::selectKeyType,
        onNameContinue = viewModel::submitName,
        onNameSkip = { viewModel.submitName(null) },
        onAllowNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && notificationPermission != null) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.createGuestIdentity()
            }
        },
        onSkipNotifications = viewModel::createGuestIdentity,
        onRetry = viewModel::createGuestIdentity,
    )
}

@Composable
fun OnboardingContent(
    state: OnboardingState,
    onWelcomeContinue: () -> Unit,
    onPrivacyContinue: () -> Unit,
    onKeySelected: (KeyType) -> Unit,
    onNameContinue: (String) -> Unit,
    onNameSkip: () -> Unit,
    onAllowNotifications: () -> Unit,
    onSkipNotifications: () -> Unit,
    onRetry: () -> Unit,
    onRelaySubmit: (String) -> Unit = {},
    onScanInvite: () -> Unit = {},
    onRelayConfirm: () -> Unit = {},
    onChooseAnotherRelay: () -> Unit = {},
) {
    AuroraBackground(Modifier.fillMaxSize(), animated = true) {
        AnimatedContent(
            targetState = state,
            // Complete keeps showing the generating step rather than animating to a copy of it.
            contentKey = { if (it is OnboardingState.Complete) OnboardingState.CreatingIdentity::class else it::class },
            transitionSpec = {
                (fadeIn(tween(320)) + slideInHorizontally(tween(320)) { it / 6 }) togetherWith
                    (fadeOut(tween(180)) + slideOutHorizontally(tween(320)) { -it / 6 })
            },
            label = "onboardingStep",
        ) { current ->
            // Every step's main button sits in the same place, so during a transition
            // the second tap of a double tap would land on the next step's button (or
            // the leaving one's). Steps only take input once they have settled.
            Box(Modifier.fillMaxSize().blockTouches(!isSettled)) {
                when (current) {
                    OnboardingState.Welcome -> WelcomeStep(onWelcomeContinue)
                    is OnboardingState.RelayChoice -> RelayChoiceStep(
                        state = current,
                        onSubmit = onRelaySubmit,
                        onScanInvite = onScanInvite,
                        onConfirm = onRelayConfirm,
                        onChooseAnother = onChooseAnotherRelay,
                    )
                    OnboardingState.PrivacyExplainer -> PrivacyStep(onPrivacyContinue)
                    OnboardingState.KeySelection -> KeySelectionStep(onKeySelected)
                    OnboardingState.NameEntry -> NameStep(onNameContinue, onNameSkip)
                    OnboardingState.NotificationPermission -> NotificationStep(onAllowNotifications, onSkipNotifications)
                    // Keep the generating screen up while navigation takes over.
                    OnboardingState.CreatingIdentity, is OnboardingState.Complete -> GeneratingStep()
                    is OnboardingState.Error -> ErrorStep(current.message, onRetry)
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    OnboardingPage(
        step = 0,
        hero = {
            GlowOrb(size = 136.dp) {
                Icon(
                    painter = painterResource(R.drawable.ic_brand_mark),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(52.dp),
                )
            }
        },
        title = {
            GradientText(
                text = "Sotto",
                style = MaterialTheme.typography.displayMedium,
                colors = listOf(MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                textAlign = TextAlign.Center,
            )
        },
        subtitle = stringResource(R.string.onboarding_welcome_subtitle),
        primaryText = stringResource(R.string.onboarding_get_started),
        onPrimary = onContinue,
    ) {
        FeatureList(
            Feature(Icons.Rounded.Fingerprint, stringResource(R.string.onboarding_feature_no_phone_title), stringResource(R.string.onboarding_feature_no_phone_body)),
            Feature(Icons.Rounded.Shield, stringResource(R.string.onboarding_feature_pq_title), stringResource(R.string.onboarding_feature_pq_body)),
            Feature(Icons.Rounded.VisibilityOff, stringResource(R.string.onboarding_feature_no_tracking_title), stringResource(R.string.onboarding_feature_no_tracking_body)),
        )
    }
}

@Composable
private fun PrivacyStep(onContinue: () -> Unit) {
    OnboardingPage(
        step = 2,
        hero = { HeroOrb(Icons.Rounded.Lock) },
        title = { PageTitle(stringResource(R.string.onboarding_privacy_title)) },
        subtitle = null,
        primaryText = stringResource(R.string.onboarding_privacy_understand),
        onPrimary = onContinue,
    ) {
        FeatureList(
            Feature(Icons.Rounded.PhoneAndroid, stringResource(R.string.onboarding_privacy_device_title), stringResource(R.string.onboarding_privacy_device_body)),
            Feature(Icons.Rounded.Lock, stringResource(R.string.onboarding_privacy_encrypted_title), stringResource(R.string.onboarding_privacy_encrypted_body)),
            Feature(Icons.Rounded.Dns, stringResource(R.string.onboarding_privacy_relay_title), stringResource(R.string.onboarding_privacy_relay_body)),
        )
        Spacer(Modifier.height(12.dp))
        WarningCard(stringResource(R.string.onboarding_privacy_warning))
    }
}

@Composable
private fun KeySelectionStep(onSelected: (KeyType) -> Unit) {
    // Saveable: the choice must survive rotation, or it silently reverts to hybrid.
    var selected by rememberSaveable { mutableStateOf(KeyType.HYBRID_POST_QUANTUM) }

    OnboardingPage(
        step = 3,
        hero = { HeroOrb(Icons.Rounded.Key, size = 104.dp) },
        title = { PageTitle(stringResource(R.string.onboarding_keys_title)) },
        subtitle = stringResource(R.string.onboarding_keys_subtitle),
        primaryText = stringResource(R.string.common_continue),
        onPrimary = { onSelected(selected) },
    ) {
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KeyOptionCard(
                title = stringResource(R.string.onboarding_keys_pq_title),
                algorithms = "ML-KEM-768 + X25519",
                description = stringResource(R.string.onboarding_keys_pq_description),
                tag = stringResource(R.string.onboarding_keys_recommended),
                recommended = true,
                isSelected = selected == KeyType.HYBRID_POST_QUANTUM,
                onClick = { selected = KeyType.HYBRID_POST_QUANTUM },
            )
            KeyOptionCard(
                title = stringResource(R.string.onboarding_keys_standard_title),
                algorithms = "X25519 + Ed25519",
                description = stringResource(R.string.onboarding_keys_standard_description),
                tag = stringResource(R.string.onboarding_keys_legacy),
                recommended = false,
                isSelected = selected == KeyType.CLASSICAL,
                onClick = { selected = KeyType.CLASSICAL },
            )
        }
    }
}

@Composable
private fun KeyOptionCard(
    title: String,
    algorithms: String,
    description: String,
    tag: String,
    recommended: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val aurora = Aurora.colors
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    val fill by animateColorAsState(
        if (isSelected) colors.primary.copy(alpha = if (aurora.isDark) 0.12f else 0.08f) else aurora.glass,
        label = "keyCardFill",
    )
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick),
        shape = shape,
        fill = fill,
        border = if (isSelected) {
            BorderStroke(1.5.dp, Brush.linearGradient(aurora.brand))
        } else {
            BorderStroke(1.dp, aurora.glassBorder)
        },
    ) {
        Row(verticalAlignment = Alignment.Top) {
            SelectionDot(isSelected)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    TagChip(tag, highlighted = recommended)
                }
                Spacer(Modifier.height(4.dp))
                Text(algorithms, style = MaterialTheme.typography.labelMedium, color = colors.primary)
                Spacer(Modifier.height(6.dp))
                Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** Radio indicator: a gradient disc with a check when selected, a ring otherwise. */
@Composable
private fun SelectionDot(selected: Boolean) {
    val aurora = Aurora.colors
    Box(
        modifier = Modifier
            .padding(top = 1.dp)
            .size(24.dp)
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.background(Brush.linearGradient(aurora.brand))
                } else {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * Where the account will live. First an invite link (opened, pasted, as a whole message too, or
 * scanned), which holds the relay and the code together, or the address of a relay that needs no
 * invite; then, once the relay answered, its host to confirm.
 */
@Composable
private fun RelayChoiceStep(
    state: OnboardingState.RelayChoice,
    onSubmit: (String) -> Unit,
    onScanInvite: () -> Unit,
    onConfirm: () -> Unit,
    onChooseAnother: () -> Unit,
) {
    val relay = state.relay
    if (relay == null) {
        var text by rememberSaveable(state.input) { mutableStateOf(state.input) }
        OnboardingPage(
            step = 1,
            hero = { HeroOrb(Icons.Rounded.Dns) },
            title = { PageTitle(stringResource(R.string.onboarding_invite_title)) },
            subtitle = stringResource(R.string.onboarding_invite_subtitle),
            primaryText = stringResource(if (state.checking) R.string.onboarding_checking else R.string.common_continue),
            onPrimary = { if (!state.checking && text.isNotBlank()) onSubmit(text) },
        ) {
            AuroraTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = stringResource(R.string.onboarding_invite_placeholder),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onSubmit(text) }),
                errorMessage = state.error?.asString(),
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { StepError(it) }
            Spacer(Modifier.height(16.dp))
            SoftButton(
                text = stringResource(R.string.onboarding_scan_invite),
                icon = Icons.Rounded.QrCodeScanner,
                onClick = onScanInvite,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        val host = relay.substringAfter("://")
        OnboardingPage(
            step = 1,
            hero = { HeroOrb(Icons.Rounded.Dns) },
            title = { PageTitle(stringResource(R.string.onboarding_join_title)) },
            subtitle = stringResource(R.string.onboarding_join_subtitle, host) +
                if (state.code.isNotBlank()) " " + stringResource(R.string.onboarding_join_invite_included) else "",
            primaryText = stringResource(R.string.onboarding_join_button, host),
            onPrimary = onConfirm,
            secondaryText = stringResource(R.string.onboarding_other_relay),
            onSecondary = onChooseAnother,
        ) {
            state.error?.let { StepError(it) }
        }
    }
}

@Composable
private fun StepError(message: UiText) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = message.asString(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NameStep(onContinue: (String) -> Unit, onSkip: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    OnboardingPage(
        step = 4,
        hero = { HeroOrb(Icons.Rounded.Badge) },
        title = { PageTitle(stringResource(R.string.onboarding_name_title)) },
        subtitle = stringResource(R.string.onboarding_name_subtitle),
        primaryText = stringResource(R.string.common_continue),
        onPrimary = { onContinue(name) },
        secondaryText = stringResource(R.string.onboarding_skip),
        onSecondary = onSkip,
    ) {
        AuroraTextField(
            value = name,
            onValueChange = { if (it.codePointCount(0, it.length) <= DisplayNames.MAX_LENGTH) name = it },
            placeholder = stringResource(R.string.onboarding_name_placeholder),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onContinue(name) }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NotificationStep(onAllow: () -> Unit, onSkip: () -> Unit) {
    OnboardingPage(
        step = 5,
        hero = { HeroOrb(Icons.Rounded.Notifications) },
        title = { PageTitle(stringResource(R.string.onboarding_notifications_title)) },
        subtitle = stringResource(R.string.onboarding_notifications_subtitle),
        primaryText = stringResource(R.string.onboarding_notifications_allow),
        onPrimary = onAllow,
        secondaryText = stringResource(R.string.onboarding_skip),
        onSecondary = onSkip,
    ) {
        FeatureList(
            Feature(
                Icons.Rounded.Visibility,
                stringResource(R.string.onboarding_notifications_lock_title),
                stringResource(R.string.onboarding_notifications_lock_body),
            ),
        )
    }
}

@Composable
private fun GeneratingStep() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GlowOrb(size = 152.dp, speed = 2.5f) {
            Icon(
                painter = painterResource(R.drawable.ic_brand_mark),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(56.dp),
            )
        }
        Spacer(Modifier.height(48.dp))
        ShimmerText(
            text = stringResource(R.string.onboarding_generating),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_generating_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorStep(message: UiText, onRetry: () -> Unit) {
    OnboardingPage(
        step = null,
        hero = { HeroOrb(Icons.Rounded.ErrorOutline) },
        title = { PageTitle(stringResource(R.string.onboarding_error_title)) },
        subtitle = message.asString(),
        primaryText = stringResource(R.string.onboarding_retry),
        onPrimary = onRetry,
    )
}

/**
 * Shared page frame: progress at the top, a hero and title, step content, and the
 * actions pinned to the bottom. Content scrolls on small screens and is centred
 * when it fits.
 */
@Composable
private fun OnboardingPage(
    step: Int?,
    hero: @Composable () -> Unit,
    title: @Composable () -> Unit,
    subtitle: String?,
    primaryText: String,
    onPrimary: () -> Unit,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            // Keeps the buttons above the keyboard on the name step.
            .imePadding()
            .padding(horizontal = 24.dp),
    ) {
        if (step != null) {
            StepIndicator(step = step, total = 6, modifier = Modifier.padding(top = 16.dp))
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                hero()
                Spacer(Modifier.height(32.dp))
                title()
                if (subtitle != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(28.dp))
                content()
            }
        }
        GradientButton(
            text = primaryText,
            onClick = onPrimary,
            modifier = Modifier.fillMaxWidth(),
        )
        if (secondaryText != null && onSecondary != null) {
            TextButton(
                onClick = onSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                Text(secondaryText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Spacer(Modifier.height(16.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StepIndicator(step: Int, total: Int, modifier: Modifier = Modifier) {
    val aurora = Aurora.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(total) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .then(
                        if (index <= step) {
                            Modifier.background(Brush.horizontalGradient(aurora.brand))
                        } else {
                            Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                        },
                    ),
            )
        }
    }
}

@Composable
private fun HeroOrb(icon: ImageVector, size: androidx.compose.ui.unit.Dp = 120.dp) {
    GlowOrb(size = size) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.34f))
    }
}

@Composable
private fun PageTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineLarge,
        textAlign = TextAlign.Center,
    )
}

private class Feature(val icon: ImageVector, val title: String, val body: String)

@Composable
private fun FeatureList(vararg features: Feature) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
    ) {
        features.forEach { feature ->
            Row(
                modifier = Modifier.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(feature.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(feature.title, style = MaterialTheme.typography.titleSmall)
                    Text(feature.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun WarningCard(text: String) {
    val warning = Aurora.colors.warning
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(warning.copy(alpha = 0.10f))
            .border(1.dp, warning.copy(alpha = 0.30f), MaterialTheme.shapes.medium)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = warning, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}
