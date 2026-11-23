package com.sotto.app.ui.navigation

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.sotto.app.connection.RelayConfig
import com.sotto.app.domain.model.InviteLink
import com.sotto.app.ui.call.CallScreen
import com.sotto.app.ui.chat.ChatScreen
import com.sotto.app.ui.components.blockTouches
import com.sotto.app.ui.components.isExiting
import com.sotto.app.ui.contacts.AddContactScreen
import com.sotto.app.ui.contacts.PREFILLED_SHARE_CODE_KEY
import com.sotto.app.ui.contacts.SCANNED_INVITE_KEY
import com.sotto.app.ui.contacts.SCANNED_SHARE_CODE_KEY
import com.sotto.app.ui.contacts.ScanQrScreen
import com.sotto.app.ui.contacts.SCANNED_VERIFY_CODE_KEY
import com.sotto.app.ui.contacts.VerifyInPersonScreen
import com.sotto.app.ui.conversations.ConversationsScreen
import com.sotto.app.ui.onboarding.OnboardingScreen
import com.sotto.app.ui.settings.SettingsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val CONVERSATIONS = "conversations"
    const val CHAT = "chat/{conversationId}/{contactId}"
    const val ADD_CONTACT = "add_contact"
    const val SCAN_QR = "scan_qr"
    const val SCAN_INVITE = "scan_invite"
    const val SETTINGS = "settings"
    const val CALL = "call/{contactId}/{displayName}"
    const val VERIFY = "verify/{contactId}"
    const val SCAN_VERIFY = "scan_verify"

    fun verify(contactId: String) = "verify/$contactId"

    fun chat(conversationId: String, contactId: String) =
        "chat/$conversationId/$contactId"

    fun call(contactId: String, displayName: String) = "call/$contactId/${android.net.Uri.encode(displayName)}"
}

private const val NAV_DURATION = 260
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * A destination whose screen ignores touches while it animates out. A popped
 * screen stays on top for the whole exit, so without this a tap aimed at the
 * screen being revealed could land on the one leaving (and, say, flip a setting).
 */
private fun NavGraphBuilder.screen(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(route = route, arguments = arguments) { entry ->
        Box(Modifier.fillMaxSize().blockTouches(isExiting)) {
            content(entry)
        }
    }
}

@Composable
fun ChatNavGraph(
    navController: NavHostController,
    startDestination: String,
) {
    // Pages glide in with an emphasized ease; the call screen zooms in over the
    // chat instead (the chat just fades beneath it). Fades last as long as the
    // slides, so a screen is visible for as long as it is on screen at all.
    val slide = tween<IntOffset>(NAV_DURATION, easing = EmphasizedEasing)
    val fade = tween<Float>(NAV_DURATION)
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            if (targetState.destination.route == Routes.CALL) {
                fadeIn(tween(NAV_DURATION)) + scaleIn(tween(NAV_DURATION, easing = EmphasizedEasing), initialScale = 0.92f)
            } else {
                slideInHorizontally(slide) { it / 5 } + fadeIn(fade)
            }
        },
        exitTransition = {
            if (targetState.destination.route == Routes.CALL) {
                fadeOut(tween(NAV_DURATION))
            } else {
                slideOutHorizontally(slide) { -it / 8 } + fadeOut(fade)
            }
        },
        popEnterTransition = {
            if (initialState.destination.route == Routes.CALL) {
                fadeIn(tween(NAV_DURATION))
            } else {
                slideInHorizontally(slide) { -it / 8 } + fadeIn(fade)
            }
        },
        popExitTransition = {
            if (initialState.destination.route == Routes.CALL) {
                fadeOut(tween(NAV_DURATION)) + scaleOut(tween(NAV_DURATION), targetScale = 0.96f)
            } else {
                slideOutHorizontally(slide) { it / 5 } + fadeOut(fade)
            }
        },
    ) {
        screen(Routes.ONBOARDING) { entry ->
            // The invite scanner hands its result back through this entry's SavedStateHandle.
            val scannedInvite by entry.savedStateHandle
                .getStateFlow<String?>(SCANNED_INVITE_KEY, null)
                .collectAsState()
            OnboardingScreen(
                onComplete = {
                    navController.navigate(Routes.CONVERSATIONS) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
                onScanInvite = { navController.navigate(Routes.SCAN_INVITE) { launchSingleTop = true } },
                scannedInvite = scannedInvite,
                onScannedInviteHandled = { entry.savedStateHandle[SCANNED_INVITE_KEY] = null },
            )
        }

        screen(Routes.CONVERSATIONS) {
            // Intercept system back so it finishes the activity instead of
            // popping the start destination and leaving an empty NavHost.
            val activity = LocalContext.current as? Activity
            BackHandler { activity?.finish() }

            ConversationsScreen(
                onConversationClick = { conversationId, contactId ->
                    navController.navigate(Routes.chat(conversationId, contactId))
                },
                onAddContact = {
                    navController.navigate(Routes.ADD_CONTACT)
                },
                onSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
            )
        }

        screen(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType },
                navArgument("contactId") { type = NavType.StringType },
            ),
        ) {
            ChatScreen(
                onBack = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Routes.CONVERSATIONS) {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                    }
                },
                onCallClick = { contactId, displayName ->
                    navController.navigate(Routes.call(contactId, displayName)) {
                        launchSingleTop = true
                    }
                },
                onVerifyClick = { contactId ->
                    navController.navigate(Routes.verify(contactId)) { launchSingleTop = true }
                },
            )
        }

        screen(
            route = Routes.VERIFY,
            arguments = listOf(navArgument("contactId") { type = NavType.StringType }),
        ) { entry ->
            // The scanner hands its result back through this entry's SavedStateHandle.
            val scannedCode by entry.savedStateHandle
                .getStateFlow<String?>(SCANNED_VERIFY_CODE_KEY, null)
                .collectAsState()
            VerifyInPersonScreen(
                onBack = {
                    if (navController.currentBackStackEntry?.destination?.route == Routes.VERIFY &&
                        navController.previousBackStackEntry != null
                    ) {
                        navController.popBackStack()
                    }
                },
                onScan = { navController.navigate(Routes.SCAN_VERIFY) { launchSingleTop = true } },
                onVerifyByPhone = { contactId, name ->
                    // The call screen asks for the microphone and dials; the check opens once
                    // the call connects (and the request lapses if it never does).
                    com.sotto.app.call.VerifyOnConnect.arm(contactId)
                    navController.navigate(Routes.call(contactId, name)) { launchSingleTop = true }
                },
                scannedCode = scannedCode,
                onScannedCodeHandled = { entry.savedStateHandle[SCANNED_VERIFY_CODE_KEY] = null },
            )
        }

        screen(Routes.SCAN_VERIFY) {
            ScanQrScreen(
                onBack = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    }
                },
                onScanned = { code ->
                    navController.previousBackStackEntry?.savedStateHandle?.set(SCANNED_VERIFY_CODE_KEY, code)
                    navController.popBackStack()
                },
                accept = { text -> com.sotto.app.crypto.InPersonCode.parse(text)?.encode() },
                title = "Scan their code",
                hint = "Point the camera at the code on their Verify in person screen.",
                rejectMessage = "That isn't a Verify in person code. On their phone: open your chat, then ⋮ → Verify in person.",
            )
        }

        screen(
            route = Routes.CALL,
            arguments = listOf(
                navArgument("contactId") { type = NavType.StringType },
                navArgument("displayName") { type = NavType.StringType },
            ),
        ) {
            val activity = LocalContext.current as? android.app.Activity
            CallScreen(
                onMinimize = {
                    if (navController.previousBackStackEntry != null) navController.popBackStack()
                    else activity?.moveTaskToBack(true)
                },
                onCallEnded = {
                    // Only while the call screen is still on top: Back may already have
                    // closed it during "Call ended", and popping again would close the
                    // chat underneath as well.
                    if (navController.currentBackStackEntry?.destination?.route == Routes.CALL &&
                        navController.previousBackStackEntry != null
                    ) {
                        navController.popBackStack()
                    }
                },
            )
        }

        screen(Routes.ADD_CONTACT) { entry ->
            // The scanner hands its result back through this entry's SavedStateHandle.
            val scannedShareCode by entry.savedStateHandle
                .getStateFlow<String?>(SCANNED_SHARE_CODE_KEY, null)
                .collectAsState()
            // An opened invite link's sender, filled in for the user to confirm.
            val prefilledShareCode by entry.savedStateHandle
                .getStateFlow<String?>(PREFILLED_SHARE_CODE_KEY, null)
                .collectAsState()
            AddContactScreen(
                onBack = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    }
                },
                onContactAdded = { conversationId, contactId ->
                    navController.navigate(Routes.chat(conversationId, contactId)) {
                        popUpTo(Routes.CONVERSATIONS)
                    }
                },
                onScanQr = { navController.navigate(Routes.SCAN_QR) { launchSingleTop = true } },
                scannedShareCode = scannedShareCode,
                onScannedShareCodeHandled = { entry.savedStateHandle[SCANNED_SHARE_CODE_KEY] = null },
                prefilledShareCode = prefilledShareCode,
                onPrefilledShareCodeHandled = { entry.savedStateHandle[PREFILLED_SHARE_CODE_KEY] = null },
            )
        }

        screen(Routes.SCAN_QR) {
            ScanQrScreen(
                onBack = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    }
                },
                onScanned = { shareCode ->
                    navController.previousBackStackEntry?.savedStateHandle?.set(SCANNED_SHARE_CODE_KEY, shareCode)
                    navController.popBackStack()
                },
                // Their full code (0.7.11 and later) adds and verifies in person; a bare share code just adds.
                accept = { text ->
                    com.sotto.app.crypto.InPersonCode.parse(text)?.encode()
                        ?: com.sotto.app.ui.contacts.shareCodeFromQr(text)
                },
            )
        }

        screen(Routes.SCAN_INVITE) {
            ScanQrScreen(
                onBack = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    }
                },
                onScanned = { link ->
                    navController.previousBackStackEntry?.savedStateHandle?.set(SCANNED_INVITE_KEY, link)
                    navController.popBackStack()
                },
                accept = { text -> InviteLink.find(text, RelayConfig.cleartextHosts)?.toLink() },
                title = "Scan an invite",
                hint = "Point the camera at the invite QR on the other person's phone (their Settings, Invite someone).",
                rejectMessage = "That QR code isn't a Sotto invite.",
            )
        }

        screen(Routes.SETTINGS) {
            SettingsScreen(
                onBack = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    }
                },
                onWiped = {
                    navController.navigate(Routes.ONBOARDING) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
    }
}
