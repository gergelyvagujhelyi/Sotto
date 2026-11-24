package com.sotto.app.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.sotto.app.domain.model.PrivacySettings
import com.sotto.app.ui.call.CallContent
import com.sotto.app.ui.chat.BlockContactDialog
import com.sotto.app.ui.chat.ChatContent
import com.sotto.app.ui.chat.DeleteChatDialog
import com.sotto.app.ui.chat.RenameContactDialog
import com.sotto.app.ui.contacts.AddContactContent
import com.sotto.app.ui.contacts.AddContactState
import com.sotto.app.ui.contacts.RemoveContactDialog
import com.sotto.app.ui.contacts.ScanQrContent
import com.sotto.app.ui.contacts.ShareCodeQrDialog
import com.sotto.app.ui.conversations.ConversationsContent
import com.sotto.app.ui.onboarding.OnboardingContent
import com.sotto.app.ui.onboarding.OnboardingState
import com.sotto.app.ui.settings.EditMyNameDialog
import com.sotto.app.ui.settings.InviteSheet
import com.sotto.app.ui.settings.SettingsContent
import com.sotto.app.ui.text.UiText
import com.sotto.app.ui.theme.SottoTheme

/** Every screen of the app filled with [SampleData], by name. Used by the previews below. */
@Composable
internal fun DesignSample(name: String) {
    val snackbar = remember { SnackbarHostState() }
    var draft by remember { mutableStateOf("") }
    when (name) {
        "conversations", "conversations-empty" -> ConversationsContent(
            conversations = if (name == "conversations") SampleData.conversations else emptyList(),
            messageRequests = if (name == "conversations") SampleData.requests else emptyList(),
            onConversationClick = { _, _ -> },
            onAddContact = {},
            onSettings = {},
            onAcceptRequest = {},
            onRejectRequest = {},
            onBlockRequest = {},
        )
        "chat", "chat-reply", "chat-request", "chat-reset", "chat-blocked", "chat-block-dialog", "chat-rename-dialog" -> ChatContent(
            contactName = if (name == "chat-request") "Tilda Lindqvist" else "Maja Sjöberg",
            avatarSeed = if (name == "chat-request") "u-tilda" else "u-maja",
            isEncrypted = true,
            encryptionInfo = SampleData.PQC_INFO,
            messages = when (name) {
                "chat-request" -> SampleData.messages.take(1)
                "chat-reply" -> SampleData.replyMessages
                else -> SampleData.messages
            },
            replyingTo = if (name == "chat-reply") SampleData.replyMessages.first() else null,
            composerText = draft,
            needsSessionReset = name == "chat-reset",
            peerDeleted = false,
            isReEstablishing = false,
            isMessageRequest = name == "chat-request",
            isBlocked = name == "chat-blocked",
            snackbarHostState = snackbar,
            onBack = {},
            onCall = {},
            onComposerChange = { draft = it },
            onSend = { draft = "" },
            onRetry = {},
            onReEstablishSession = {},
            onAcceptRequest = {},
            onRejectRequest = {},
            onBlock = {},
            onUnblock = {},
            onRename = {},
            onDelete = {},
            onRemoveContact = {},
        ).also {
            if (name == "chat-block-dialog") BlockContactDialog(name = "Maja Sjöberg", onConfirm = {}, onDismiss = {})
            if (name == "chat-delete-dialog") DeleteChatDialog(name = "Maja Sjöberg", onConfirm = {}, onDismiss = {})
            if (name == "chat-remove-contact-dialog") RemoveContactDialog(name = "Maja Sjöberg", onConfirm = {}, onDismiss = {})
            if (name == "chat-rename-dialog") {
                RenameContactDialog(currentName = "Maja Sjöberg", theirOwnName = "Maja", onSave = {}, onDismiss = {})
            }
        }
        "call-incoming", "call-connected", "call-outgoing" -> CallContent(
            callState = when (name) {
                "call-incoming" -> SampleData.incomingCall
                "call-connected" -> SampleData.connectedCall
                else -> SampleData.outgoingCall
            },
            encryptionInfo = SampleData.PQC_INFO,
            snackbarHostState = snackbar,
            onAccept = {},
            onDecline = {},
            onHangup = {},
            onToggleMute = {},
            onToggleSpeaker = {},
        )
        "onboarding-welcome", "onboarding-relay", "onboarding-privacy", "onboarding-keys", "onboarding-name",
        "onboarding-notifications", "onboarding-generating" -> OnboardingContent(
            state = when (name) {
                "onboarding-welcome" -> OnboardingState.Welcome
                // Opened from an invite: the relay answered, the code is filled in.
                "onboarding-relay" -> OnboardingState.RelayChoice(
                    relay = SampleData.RELAY,
                    inviteRequired = true,
                    code = SampleData.INVITE_CODE,
                    inviterShareCode = SampleData.SHARE_CODE,
                )
                "onboarding-privacy" -> OnboardingState.PrivacyExplainer
                "onboarding-keys" -> OnboardingState.KeySelection
                "onboarding-name" -> OnboardingState.NameEntry
                "onboarding-notifications" -> OnboardingState.NotificationPermission
                else -> OnboardingState.CreatingIdentity
            },
            onWelcomeContinue = {},
            onPrivacyContinue = {},
            onKeySelected = {},
            onNameContinue = {},
            onNameSkip = {},
            onAllowNotifications = {},
            onSkipNotifications = {},
            onRetry = {},
        )
        "settings", "settings-name-dialog", "settings-background", "invite-sheet" -> SettingsContent(
            settings = PrivacySettings(backgroundConnection = name == "settings-background"),
            shareCode = SampleData.SHARE_CODE,
            myName = SampleData.MY_NAME,
            relayHost = SampleData.RELAY_HOST,
            isRotatingKeys = false,
            onBack = {},
            onEditName = {},
            onRotateKeys = {},
            onLockScreenPreviewChange = {},
            onReadReceiptsChange = {},
            onScreenSecurityChange = {},
            onDirectCallsChange = {},
            onBackgroundConnectionChange = {},
            backgroundConnectionRecommended = name == "settings-background",
            batteryOptimised = name == "settings-background",
            onAllowBackgroundActivity = {},
            onDisappearingChange = {},
            onRetentionChange = {},
            blockedUsers = SampleData.blockedUsers,
            onUnblock = {},
            onWipe = {},
        ).also {
            if (name == "settings-name-dialog") EditMyNameDialog(currentName = SampleData.MY_NAME, onSave = {}, onDismiss = {})
            if (name == "invite-sheet") InviteSheet(SampleData.invite, onDismiss = {})
        }
        "add-contact", "add-contact-error", "add-contact-qr" -> AddContactContent(
            shareCodeInput = if (name == "add-contact-error") "not-a-code" else "",
            state = if (name == "add-contact-error") {
                AddContactState.Error(UiText.Plain("No user found for that share code."))
            } else {
                AddContactState.Idle
            },
            myShareCode = SampleData.SHARE_CODE,
            contacts = SampleData.contacts,
            onBack = {},
            onShareCodeChange = {},
            onSubmit = {},
            onScanQr = {},
        ).also {
            if (name == "add-contact-qr") ShareCodeQrDialog(shareCode = SampleData.SHARE_CODE, onDismiss = {})
        }
        "scan-qr", "scan-qr-message", "scan-qr-permission" -> ScanQrContent(
            hasPermission = name != "scan-qr-permission",
            permissionDenied = name == "scan-qr-permission",
            torchOn = false,
            torchAvailable = true,
            message = if (name == "scan-qr-message") "That QR code isn't a Sotto share code." else null,
            onBack = {},
            onToggleTorch = {},
            onRequestPermission = {},
            onOpenSettings = {},
            // Stands in for the camera picture, which previews can't show.
            camera = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color(0xFF2A3442), Color(0xFF10151C)))),
                )
            },
        )
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL, device = "id:pixel_8")
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL, device = "id:pixel_8")
private annotation class ThemePreviews

@ThemePreviews @Composable private fun ChatsPreview() = SottoTheme { DesignSample("conversations") }
@ThemePreviews @Composable private fun ChatsEmptyPreview() = SottoTheme { DesignSample("conversations-empty") }
@ThemePreviews @Composable private fun ChatPreview() = SottoTheme { DesignSample("chat") }
@ThemePreviews @Composable private fun ChatReplyPreview() = SottoTheme { DesignSample("chat-reply") }
@ThemePreviews @Composable private fun ChatRequestPreview() = SottoTheme { DesignSample("chat-request") }
@ThemePreviews @Composable private fun ChatBlockedPreview() = SottoTheme { DesignSample("chat-blocked") }
@ThemePreviews @Composable private fun BlockDialogPreview() = SottoTheme { DesignSample("chat-block-dialog") }
@ThemePreviews @Composable private fun IncomingCallPreview() = SottoTheme { DesignSample("call-incoming") }
@ThemePreviews @Composable private fun ConnectedCallPreview() = SottoTheme { DesignSample("call-connected") }
@ThemePreviews @Composable private fun SetupNamePreview() = SottoTheme { DesignSample("onboarding-name") }
@ThemePreviews @Composable private fun WelcomePreview() = SottoTheme { DesignSample("onboarding-welcome") }
@ThemePreviews @Composable private fun KeysPreview() = SottoTheme { DesignSample("onboarding-keys") }
@ThemePreviews @Composable private fun SettingsPreview() = SottoTheme { DesignSample("settings") }
@ThemePreviews @Composable private fun InviteSheetPreview() = SottoTheme { DesignSample("invite-sheet") }
@ThemePreviews @Composable private fun JoinRelayPreview() = SottoTheme { DesignSample("onboarding-relay") }
@ThemePreviews @Composable private fun BackgroundConnectionPreview() = SottoTheme { DesignSample("settings-background") }
@ThemePreviews @Composable private fun MyNameDialogPreview() = SottoTheme { DesignSample("settings-name-dialog") }
@ThemePreviews @Composable private fun RenameDialogPreview() = SottoTheme { DesignSample("chat-rename-dialog") }
@ThemePreviews @Composable private fun AddContactPreview() = SottoTheme { DesignSample("add-contact") }
@ThemePreviews @Composable private fun MyQrDialogPreview() = SottoTheme { DesignSample("add-contact-qr") }
@ThemePreviews @Composable private fun ScanQrPreview() = SottoTheme { DesignSample("scan-qr-message") }
@ThemePreviews @Composable private fun ScanQrPermissionPreview() = SottoTheme { DesignSample("scan-qr-permission") }
