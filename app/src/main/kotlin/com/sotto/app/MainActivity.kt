package com.sotto.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sotto.app.call.CallManager
import com.sotto.app.call.CallNotifier
import com.sotto.app.di.DatabaseModule
import com.sotto.app.domain.model.CallDirection
import com.sotto.app.domain.model.CallStatus
import com.sotto.app.domain.model.InviteLink
import com.sotto.app.domain.model.MemberInviteAction
import com.sotto.app.domain.model.actionForMember
import com.sotto.app.domain.repository.IdentityRepository
import com.sotto.app.domain.repository.SettingsRepository
import com.sotto.app.notification.ChatNotificationManager
import com.sotto.app.ui.call.OngoingCallBar
import com.sotto.app.ui.contacts.PREFILLED_SHARE_CODE_KEY
import com.sotto.app.ui.navigation.ChatNavGraph
import com.sotto.app.ui.navigation.Routes
import com.sotto.app.ui.theme.SottoTheme
import com.sotto.app.update.AppUpdateNotifier
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var identityRepository: IdentityRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var callManager: CallManager
    @Inject lateinit var backgroundConnection: com.sotto.app.connection.BackgroundConnection
    @Inject lateinit var pendingInvite: com.sotto.app.connection.PendingInvite
    @Inject lateinit var relayConfig: com.sotto.app.connection.RelayConfig
    @Inject lateinit var appUpdates: com.sotto.app.update.AppUpdates
    @Inject lateinit var webSocketClient: com.sotto.app.data.remote.WebSocketClient
    @Inject lateinit var networkMonitor: com.sotto.app.connection.DefaultNetworkMonitor
    @Inject lateinit var chatNotifications: ChatNotificationManager
    @Inject lateinit var callNotifier: CallNotifier
    @Inject lateinit var appUpdateNotifier: AppUpdateNotifier

    private val _deepLinkIntent = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Only a fresh launch carries a deep link to act on. On recreation (rotation,
        // theme change, restore after process death) `intent` is still the original
        // launch intent and the navigation state already contains that chat.
        if (savedInstanceState == null) {
            _deepLinkIntent.value = intent
            offerInvite(intent)
        }

        // Apply screen security synchronously before setContent to avoid
        // a visible frame before the flag takes effect
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        // Reactively apply/clear FLAG_SECURE whenever the setting changes
        lifecycleScope.launch {
            settingsRepository.getPrivacySettings().collect { settings ->
                if (settings.screenSecurity) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE,
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        // Notification channels keep the name they were made with. A language switch recreates
        // this activity but not the process, so name them again in the language now shown.
        chatNotifications.createNotificationChannels()
        callNotifier.createChannels()
        appUpdateNotifier.createChannel(onlyIfExists = true)

        val reduceAnimations = settingsRepository.getPrivacySettings().map { it.reduceAnimations }
        setContent {
            val reduceMotion by reduceAnimations.collectAsState(initial = false)
            SottoTheme(reduceMotion = reduceMotion) {
                var showDbResetDialog by remember {
                    mutableStateOf(DatabaseModule.databaseWasReset)
                }

                if (showDbResetDialog) {
                    AlertDialog(
                        onDismissRequest = { /* must press OK */ },
                        title = { Text(stringResource(R.string.components_db_reset_title)) },
                        text = {
                            Text(stringResource(R.string.components_db_reset_body))
                        },
                        confirmButton = {
                            TextButton(onClick = { showDbResetDialog = false }) {
                                Text(stringResource(R.string.common_ok))
                            }
                        },
                    )
                }

                var startDestination by remember { mutableStateOf<String?>(null) }

                val navController = rememberNavController()

                LaunchedEffect(Unit) {
                    startDestination = if (identityRepository.hasIdentity()) {
                        Routes.CONVERSATIONS
                    } else {
                        Routes.ONBOARDING
                    }
                }

                // Handle deep-link from notification — only after NavHost is composed
                val deepLinkIntent by _deepLinkIntent
                LaunchedEffect(deepLinkIntent, startDestination) {
                    if (startDestination != Routes.CONVERSATIONS) return@LaunchedEffect
                    val currentIntent = deepLinkIntent ?: return@LaunchedEffect
                    // The bundle of chat notifications opens the chat list.
                    if (currentIntent.getBooleanExtra(ChatNotificationManager.EXTRA_OPEN_CHATS, false)) {
                        navController.awaitGraph()
                        navController.popBackStack(Routes.CONVERSATIONS, inclusive = false)
                        _deepLinkIntent.value = null
                        return@LaunchedEffect
                    }
                    // A group's notification opens its chat (ADR-32).
                    currentIntent.getStringExtra(ChatNotificationManager.EXTRA_GROUP_ID)?.let { groupId ->
                        navController.awaitGraph()
                        val current = navController.currentBackStackEntry
                        val onGroup = current?.destination?.route == Routes.GROUP
                        if (!onGroup || current?.arguments?.getString("groupId") != groupId) {
                            navController.navigate(Routes.group(groupId)) {
                                if (onGroup) popUpTo(Routes.GROUP) { inclusive = true }
                            }
                        }
                        _deepLinkIntent.value = null
                        return@LaunchedEffect
                    }
                    val conversationId = currentIntent.getStringExtra(
                        ChatNotificationManager.EXTRA_CONVERSATION_ID
                    )
                    val contactId = currentIntent.getStringExtra(
                        ChatNotificationManager.EXTRA_CONTACT_ID
                    )
                    if (conversationId != null && contactId != null) {
                        navController.awaitGraph()
                        val current = navController.currentBackStackEntry
                        val onChat = current?.destination?.route == Routes.CHAT
                        if (!onChat || current?.arguments?.getString("conversationId") != conversationId) {
                            // launchSingleTop would keep the open chat's screen (and its
                            // ViewModel, bound to the old conversation) for another chat:
                            // replace it instead.
                            navController.navigate(Routes.chat(conversationId, contactId)) {
                                if (onChat) popUpTo(Routes.CHAT) { inclusive = true }
                            }
                        }
                    }
                    _deepLinkIntent.value = null
                }

                // Someone just scanned our live QR code and added us: swap roles. Open the
                // scanner over New chat; scanning their code verifies them and accepts the request.
                LaunchedEffect(Unit) {
                    com.sotto.app.ui.contacts.LiveQrWatch.openRequest.collect {
                        navController.awaitGraph()
                        if (navController.currentBackStackEntry?.destination?.route != Routes.ADD_CONTACT) {
                            navController.navigate(Routes.ADD_CONTACT) { launchSingleTop = true }
                        }
                        navController.currentBackStackEntry?.savedStateHandle
                            ?.set(com.sotto.app.ui.contacts.LiveQrWatch.SCAN_BACK_REQUEST_KEY, true)
                    }
                }

                // An invite link opened the app after setup: add its sender when they're on our
                // relay. (During setup, setup takes the invite itself.)
                LaunchedEffect(startDestination) {
                    if (startDestination != Routes.CONVERSATIONS) return@LaunchedEffect
                    pendingInvite.invite.filterNotNull().collect {
                        val invite = pendingInvite.take() ?: return@collect
                        navController.awaitGraph()
                        handleInviteAsMember(invite, navController)
                    }
                }

                // Navigate to CallScreen when an incoming call arrives
                val incomingCall by callManager.callState.collectAsState()
                val callLive = incomingCall?.status?.let {
                    it == CallStatus.RINGING || it == CallStatus.CONNECTING || it == CallStatus.CONNECTED
                } == true
                LaunchedEffect(incomingCall, startDestination) {
                    if (startDestination == null) return@LaunchedEffect
                    val call = incomingCall
                    if (call != null &&
                        call.direction == CallDirection.INCOMING &&
                        call.status == CallStatus.RINGING
                    ) {
                        navController.awaitGraph()
                        navController.navigate(Routes.call(call.peerId, call.peerDisplayName)) {
                            launchSingleTop = true
                        }
                    }
                }

                // While a call goes on behind the app, a bar above every screen returns to it.
                val currentEntry by navController.currentBackStackEntryAsState()
                val onCallScreen = currentEntry?.destination?.route == Routes.CALL
                val ongoingCall = incomingCall?.takeIf { callLive && !onCallScreen && startDestination == Routes.CONVERSATIONS }
                // The relay runs a newer Sotto: a bar above every screen says so (and, once the
                // app is out, opens SourceDrop). Below the call bar when both show.
                val appUpdate by appUpdates.state.collectAsState()
                val update = appUpdate.takeIf { startDestination == Routes.CONVERSATIONS } ?: com.sotto.app.update.AppUpdate.None
                // The relay out of reach for a few seconds: a bar says so (messages wait, none come in).
                val connected by webSocketClient.connected.collectAsState()
                val network by networkMonitor.defaultNetwork.collectAsState()
                val connectionProblem = com.sotto.app.ui.components.rememberConnectionProblem(connected, online = network != null)
                    .takeIf { startDestination == Routes.CONVERSATIONS }
                val anyBar = ongoingCall != null || update != com.sotto.app.update.AppUpdate.None || connectionProblem != null
                Column(Modifier.fillMaxSize()) {
                    ongoingCall?.let { call ->
                        OngoingCallBar(call, onClick = {
                            navController.navigate(Routes.call(call.peerId, call.peerDisplayName)) {
                                launchSingleTop = true
                            }
                        })
                    }
                    com.sotto.app.ui.update.AppUpdateBar(update, underStatusBar = ongoingCall == null)
                    com.sotto.app.ui.components.ConnectionBar(
                        connectionProblem,
                        underStatusBar = ongoingCall == null && update == com.sotto.app.update.AppUpdate.None,
                    )
                    // The top bar covers the status bar, so the screens below mustn't pad for it again.
                    // Clipped: the aurora backgrounds paint their glow past their top edge,
                    // which tinted the bar and its text.
                    Box(
                        Modifier
                            .weight(1f)
                            .clipToBounds()
                            .consumeWindowInsets(if (anyBar) WindowInsets.statusBars else WindowInsets(0)),
                    ) {
                        startDestination?.let { dest ->
                            ChatNavGraph(
                                navController = navController,
                                startDestination = dest,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // A start refused while the app was in the background works now that it's in front.
        backgroundConnection.ensureRunning()
        // A call going on: on screen now, its service may take the microphone.
        callManager.refreshCallService()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        _deepLinkIntent.value = intent
        offerInvite(intent)
    }

    /** An invite link (https://<relay>/invite#…, or sotto://invite?…): kept for setup, or for adding its sender (below). */
    private fun offerInvite(intent: Intent?) {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        InviteLink.find(link, com.sotto.app.connection.RelayConfig.cleartextHosts)?.let(pendingInvite::offer)
    }

    /**
     * Someone with an account opened an invite: if its sender shares our relay, Add contact opens
     * with their share code filled in, to add with a tap (not by itself: a link can come from anyone).
     * Their own invite, opened to check it, only says so: there's nobody to add.
     */
    private suspend fun handleInviteAsMember(invite: InviteLink, navController: NavHostController) {
        val myShareCode = identityRepository.getIdentity()?.shareCode
        // Toasts are short: Android shows two lines.
        val message = when (val action = invite.actionForMember(relayConfig.baseUrl, myShareCode)) {
            is MemberInviteAction.AddInviter -> {
                navController.navigate(Routes.ADD_CONTACT) { launchSingleTop = true }
                navController.currentBackStackEntry?.savedStateHandle?.set(PREFILLED_SHARE_CODE_KEY, action.shareCode)
                return
            }
            MemberInviteAction.OwnInvite -> getString(R.string.settings_invite_own)
            is MemberInviteAction.AlreadyMember -> getString(R.string.components_invite_already_member, action.relayName)
            is MemberInviteAction.OtherRelay -> getString(R.string.components_invite_other_relay, action.relayName)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

}

/**
 * Waits until the NavHost has set its graph. On a cold start (a notification tapped or a call
 * ringing while the app was closed) the effects that navigate can run before that, and
 * navigating then throws "Navigation graph has not been set", crashing the app as it opens.
 */
private suspend fun NavHostController.awaitGraph() {
    currentBackStackEntryFlow.first()
}
