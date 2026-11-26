package com.sotto.app.connection

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.sotto.app.call.CallManager
import com.sotto.app.call.NetworkChangeTracker
import com.sotto.app.call.NetworkEvent
import com.sotto.app.data.remote.WebSocketClient
import com.sotto.app.domain.repository.SettingsRepository
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keep the relay socket only while the app can read it. If nothing may hold the
 * socket, keep it only while the app is on screen (see [keepRelaySocket]).
 *
 * Android pauses ("freezes") background apps that have no foreground service, about 10 s
 * after they leave the screen, but their sockets stay open. The relay then counts calls and
 * messages as delivered over a socket nobody reads and holds back the push that would wake
 * the phone. So without the background connection the socket closes shortly after the app
 * leaves the screen, before the freeze, and the relay pushes right away. A push opens it
 * again (FcmService) for as long as the work takes; a call keeps it open.
 *
 * When the phone's network changes (Wi-Fi to mobile, or back after none), a socket that is
 * kept moves to the new network at once. The old one is tied to the old network, which may
 * be gone, and would only notice when a ping times out, up to a minute later, missing
 * messages and calls meanwhile.
 */
@Singleton
class RelayConnectionPolicy @Inject constructor(
    private val webSocketClient: WebSocketClient,
    private val settingsRepository: SettingsRepository,
    private val callManager: Lazy<CallManager>,
    private val networkMonitor: DefaultNetworkMonitor,
    private val reachability: RelayReachability,
) : Application.ActivityLifecycleCallbacks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var startedActivities = 0
    private var started = false
    private var closeJob: Job? = null

    @Volatile
    private var backgroundConnectionOn = false

    fun start(application: Application) {
        if (started) return
        started = true
        application.registerActivityLifecycleCallbacks(this)
        scope.launch {
            settingsRepository.getPrivacySettings()
                .map { it.backgroundConnection }
                .distinctUntilChanged()
                .collect { on ->
                    backgroundConnectionOn = on
                    if (on) cancelClose() else scheduleClose(AWAY_GRACE_MS)
                }
        }
        // A session opened while the app is away (a push woke it): close again once the
        // work had time to finish, unless a call came in meanwhile.
        scope.launch {
            webSocketClient.sessionEpoch.collect { scheduleClose(WAKE_LINGER_MS) }
        }
        scope.launch {
            callManager.get().callState.collect { call ->
                if (call != null) cancelClose() else scheduleClose(AWAY_GRACE_MS)
            }
        }
        // The relay answered a request while the socket is down (waiting out its backoff after an
        // outage): it's reachable, so connect now rather than when the backoff ends. Not during a
        // call: CallManager retries the socket itself, faster.
        scope.launch {
            reachability.answered.collect {
                if (keepNow() && !webSocketClient.connected.value && callManager.get().callState.value == null) {
                    webSocketClient.connectUnlessWorking()
                }
            }
        }
        scope.launch {
            val tracker = NetworkChangeTracker()
            networkMonitor.defaultNetwork.collect { network ->
                val event = tracker.update(network)
                // A call moves the socket itself, with quicker retries (CallManager).
                if (reconnectAfter(event) && keepNow() && callManager.get().callState.value == null) {
                    Log.i(TAG, "Network ${event.name.lowercase()}: reconnecting to the relay")
                    webSocketClient.ensureConnected()
                }
            }
        }
    }

    private fun keepNow(): Boolean = keepRelaySocket(
        onScreen = startedActivities > 0,
        backgroundConnectionOn = backgroundConnectionOn,
        callActive = callManager.get().callState.value != null,
    )

    private fun scheduleClose(delayMs: Long) {
        if (keepNow()) return
        closeJob?.cancel()
        closeJob = scope.launch {
            delay(delayMs)
            if (!keepNow()) webSocketClient.disconnect()
        }
    }

    private fun cancelClose() {
        closeJob?.cancel()
        closeJob = null
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        if (startedActivities == 1) {
            webSocketClient.onScreen = true
            cancelClose()
            // Connect, or cut short a long wait from the background (connected or connecting: no-op).
            webSocketClient.connectUnlessWorking()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) {
            webSocketClient.onScreen = false
            scheduleClose(AWAY_GRACE_MS)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    private companion object {
        const val TAG = "RelayConnectionPolicy"
        /** After the app leaves the screen: well before Android's freeze (about 10 s). */
        const val AWAY_GRACE_MS = 2_000L
        /** After a push opened the socket in the background: time for buffered signals to arrive. */
        const val WAKE_LINGER_MS = 6_000L
    }
}

/** A new default network (another one, or one back after none): the socket must move to it. */
internal fun reconnectAfter(event: NetworkEvent): Boolean =
    event == NetworkEvent.SWITCHED || event == NetworkEvent.RESTORED

/** The relay socket stays open while the app is on screen, the background connection is on, or a call runs. */
internal fun keepRelaySocket(onScreen: Boolean, backgroundConnectionOn: Boolean, callActive: Boolean): Boolean =
    onScreen || backgroundConnectionOn || callActive
