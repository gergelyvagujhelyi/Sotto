package com.sotto.app.data.remote

import com.sotto.app.BuildConfig
import com.sotto.app.crypto.KeyManager
import com.sotto.app.crypto.PqcProvider
import com.sotto.app.data.remote.dto.CallSignalDto
import com.sotto.app.domain.repository.MessageRepository
import android.os.SystemClock
import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebSocket client for real-time message delivery signals.
 *
 * Connects to the relay server's /v1/ws endpoint and listens for
 * new-message signals. When a signal arrives, it triggers a fetch
 * of pending encrypted envelopes via the REST API.
 *
 * The WebSocket never carries message content — only delivery signals.
 */
@Singleton
class WebSocketClient @Inject constructor(
    private val relayConfig: com.sotto.app.connection.RelayConfig,
    private val keyManager: KeyManager,
    private val pqcProvider: PqcProvider,
    private val messageRepository: dagger.Lazy<MessageRepository>,
    private val receiptManager: dagger.Lazy<com.sotto.app.data.repository.ReceiptManager>,
    private val appUpdates: com.sotto.app.update.AppUpdates,
) {
    private companion object {
        private const val TAG = "WebSocketClient"
        private const val INITIAL_RECONNECT_DELAY = 1_000L
        /** During a call: the first retry after a failed reconnect (then doubling). */
        private const val URGENT_RECONNECT_DELAY = 250L
        private const val MAX_RECONNECT_DELAY = 60_000L
        /** On screen, the longest wait between reconnect attempts. */
        private const val ON_SCREEN_MAX_RECONNECT_DELAY = 20_000L
        private const val CALL_MAX_RECONNECT_DELAY = 10_000L
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        install(WebSockets) {
            pingIntervalMillis = 30_000
        }
        engine {
            config {
                // Give up on a dead route (a network that just went away) after 5 s, not 10.
                connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    /** Bumped each time the socket is authenticated: a fresh session after a reconnect. */
    private val _sessionEpoch = MutableStateFlow(0L)
    val sessionEpoch: StateFlow<Long> = _sessionEpoch.asStateFlow()

    private val _incomingCallSignals = MutableSharedFlow<CallSignalDto>(extraBufferCapacity = 16)
    val incomingCallSignals: SharedFlow<CallSignalDto> = _incomingCallSignals.asSharedFlow()

    private var connectionJob: Job? = null
    @Volatile
    private var reconnectDelay = INITIAL_RECONNECT_DELAY

    /** Where the connection is, for [connectUnlessWorking]. */
    @Volatile
    private var socketState = SocketState.IDLE
    /** When [socketState] last changed (elapsedRealtime). */
    @Volatile
    private var socketStateSince = 0L
    /** When the relay last sent anything on the open socket (elapsedRealtime). */
    @Volatile
    private var lastFrameAt = 0L
    /** Bumped for each connection loop, so a cancelled loop can't overwrite the current one's state. */
    @Volatile
    private var loopGeneration = 0

    /** Sotto is on screen (RelayConnectionPolicy): reconnect attempts wait at most ON_SCREEN_MAX_RECONNECT_DELAY. */
    @Volatile
    var onScreen = false

    /**
     * A call is going on (RelayConnectionPolicy): every session that signed in starts the
     * backoff over, as always, so the call's signals get quick retries (CallManager).
     */
    @Volatile
    var callActive = false

    /** When connection attempts started (elapsedRealtime), the last hour's: Settings > Diagnostics. */
    private val attempts = ArrayDeque<Long>()

    private fun recordAttempt(now: Long = SystemClock.elapsedRealtime()) = synchronized(attempts) {
        attempts.addLast(now)
        while (attempts.isNotEmpty() && attempts.first() < now - 3_600_000L) attempts.removeFirst()
    }

    /**
     * Connection attempts in the last hour, since Sotto last started: a few is normal, dozens
     * means the line keeps dropping.
     */
    fun attemptsInLastHour(): Int = synchronized(attempts) {
        val since = SystemClock.elapsedRealtime() - 3_600_000L
        attempts.count { it >= since }
    }

    /** When the current loop's session signed in (elapsedRealtime); 0 if it didn't. */
    @Volatile
    private var sessionOpenedAt = 0L

    private val _connected = MutableStateFlow(false)
    /** The socket is open and signed in: for the screens' "Connecting…" bar. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private fun markState(generation: Int, state: SocketState) {
        if (generation != loopGeneration) return
        socketState = state
        socketStateSince = SystemClock.elapsedRealtime()
        _connected.value = state == SocketState.OPEN
    }

    @Synchronized
    fun connect() {
        if (connectionJob?.isActive == true) return

        val generation = ++loopGeneration
        connectionJob = scope.launch {
            while (isActive) {
                if (generation == loopGeneration) sessionOpenedAt = 0L
                try {
                    markState(generation, SocketState.CONNECTING)
                    connectWebSocket(generation)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Ours (disconnect, ensureConnected): the loop ends. A cancelled channel inside
                    // Ktor/OkHttp (the line dropped mid-sign-in) is a failed try: the loop goes on.
                    if (!isActive) throw e
                    if (BuildConfig.DEBUG) Log.w(TAG, "WebSocket connection cancelled: ${e.message}")
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "WebSocket connection failed: ${e.message}")
                } finally {
                    markState(generation, SocketState.IDLE)
                }
                // A loop that was replaced (ensureConnected, disconnect) leaves the backoff alone.
                if (generation != loopGeneration) return@launch
                if (backoffStartsOver(sessionOpenedAt, SystemClock.elapsedRealtime(), callActive)) {
                    reconnectDelay = INITIAL_RECONNECT_DELAY
                }

                // Exponential backoff reconnect
                // On screen the wait is capped, so "Connecting…" goes soon after the relay is back;
                // off screen the backoff grows to a minute, as before.
                // During a call at most 10 s, as before 0.7.30: its signals can't wait long.
                val cap = when {
                    callActive -> CALL_MAX_RECONNECT_DELAY
                    onScreen -> ON_SCREEN_MAX_RECONNECT_DELAY
                    else -> MAX_RECONNECT_DELAY
                }
                delay(reconnectDelay.coerceAtMost(cap))
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY)
            }
        }
    }

    /**
     * Whether the socket is open and the relay spoke on it lately (a pong comes every 25 s). The
     * relay then announces every new message on it, so a screen needn't poll for them.
     */
    fun isLive(): Boolean = socketIsLive(socketState, SystemClock.elapsedRealtime() - lastFrameAt)

    /**
     * Connect, unless a connection is being made right now or is open and the relay has spoken on
     * it recently; then leave it be. For a push: a push that wakes a closed app comes just as the
     * app's start has begun connecting, and restarting that threw away a TLS handshake and a
     * post-quantum signature in the few seconds a push allows. A socket that went quiet (a frozen
     * app's, or one on a route that died) is replaced, as [ensureConnected] does.
     */
    @Synchronized
    fun connectUnlessWorking() {
        val now = SystemClock.elapsedRealtime()
        if (connectionJob?.isActive == true &&
            !shouldReconnect(socketState, now - socketStateSince, now - lastFrameAt)
        ) return
        ensureConnected()
    }

    /**
     * Force an immediate reconnect attempt. Useful when an FCM push arrives
     * while the WebSocket is in an exponential backoff cycle — the server may
     * have buffered call signals that need delivery now.
     */
    @Synchronized
    fun ensureConnected(urgent: Boolean = false) {
        if (connectionJob?.isActive == true) {
            // Already reconnecting with backoff — restart to connect immediately
            connectionJob?.cancel()
            connectionJob = null
        }
        // [urgent]: a call is waiting for this socket, so retry quickly if the first attempt fails.
        reconnectDelay = if (urgent) URGENT_RECONNECT_DELAY else INITIAL_RECONNECT_DELAY
        connect()
    }

    @Synchronized
    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        reconnectDelay = INITIAL_RECONNECT_DELAY
        loopGeneration++
        socketState = SocketState.IDLE
        socketStateSince = SystemClock.elapsedRealtime()
        _connected.value = false
    }

    private fun generateAuthToken(): String? {
        if (keyManager.getUserId() == null) return null
        return keyManager.generateAuthToken(pqcProvider)
    }

    private suspend fun connectWebSocket(generation: Int) {
        val token = generateAuthToken() ?: return
        // No relay yet (setup hasn't chosen one): nothing to connect to.
        val relay = relayConfig.baseUrl ?: return
        val baseUrl = if (relay.startsWith("https://")) {
            "wss://" + relay.removePrefix("https://")
        } else {
            "ws://" + relay.removePrefix("http://")
        }

        // A real try (an account and a relay): counted for Settings > Diagnostics.
        recordAttempt()
        client.webSocket("$baseUrl/v1/ws") {
            // Authenticate with signed token
            send(buildJsonObject {
                put("type", "auth")
                put("token", token)
            }.toString())

            val authResponse = (incoming.receive() as? Frame.Text)?.readText()
            val authMsg = json.parseToJsonElement(authResponse ?: "{}").jsonObject
            if (authMsg["type"]?.jsonPrimitive?.content != "auth_ok") {
                if (BuildConfig.DEBUG) Log.w(TAG, "WebSocket auth failed")
                return@webSocket
            }

            relayReport(authMsg)?.let { (version, ready) -> appUpdates.report(version, ready) }
            if (BuildConfig.DEBUG) Log.d(TAG, "WebSocket connected and authenticated")
            // The backoff starts over once the session has lasted (backoffStartsOver), not at
            // sign-in: a line that drops the socket after a few seconds got a reconnect every
            // few seconds, and the messages being sent were stuck behind the handshakes.
            if (generation == loopGeneration) sessionOpenedAt = SystemClock.elapsedRealtime()
            lastFrameAt = SystemClock.elapsedRealtime()
            markState(generation, SocketState.OPEN)
            _sessionEpoch.value += 1

            // new_message signals sent while we were disconnected (or to a half-open
            // socket) are gone and the server sends no push for them: catch up now.
            scope.launch {
                try {
                    messageRepository.get().fetchPendingFromServer()
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Catch-up fetch after connect failed: ${e.message}")
                }
                try {
                    receiptManager.get().fetchReceipts()
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Receipt fetch after connect failed: ${e.message}")
                }
            }

            // Start ping job
            val pingJob = launch {
                while (isActive) {
                    delay(25_000)
                    try {
                        send("""{"type":"ping"}""")
                    } catch (_: Exception) {
                        break
                    }
                }
            }

            // Listen for messages
            try {
                for (frame in incoming) {
                    lastFrameAt = SystemClock.elapsedRealtime()
                    if (frame is Frame.Text) {
                        handleMessage(frame.readText())
                    }
                }
            } finally {
                pingJob.cancel()
            }
        }
    }

    private suspend fun handleMessage(text: String) {
        try {
            val msg = json.parseToJsonElement(text).jsonObject
            when (msg["type"]?.jsonPrimitive?.content) {
                "new_message" -> {
                    // Fetch pending messages from server
                    scope.launch {
                        try {
                            messageRepository.get().fetchPendingFromServer()
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to fetch after WS signal: ${e.message}")
                        }
                    }
                }
                "receipts" -> scope.launch {
                    try {
                        receiptManager.get().fetchReceipts()
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.w(TAG, "Receipt fetch failed: ${e.message}")
                    }
                }
                "pong" -> { /* Expected keepalive response */ }
                // The app of the relay's version is out (it was on its way).
                "app_release" -> relayReport(msg)?.let { (version, ready) -> appUpdates.report(version, ready) }

                in CALL_SIGNAL_TYPES -> {
                    val signal = CallSignalDto(
                        senderId = msg["sender_id"]?.jsonPrimitive?.content ?: return,
                        signalType = msg["type"]?.jsonPrimitive?.content ?: return,
                        callId = msg["call_id"]?.jsonPrimitive?.content ?: "",
                        encryptedPayload = msg["encrypted_payload"]?.jsonPrimitive?.content ?: "",
                        signature = msg["signature"]?.jsonPrimitive?.content ?: "",
                        pqcSignature = msg["pqc_signature"]?.jsonPrimitive?.content ?: "",
                        kemCiphertext = msg["kem_ciphertext"]?.jsonPrimitive?.content ?: "",
                    )
                    // Suspend instead of tryEmit: the server flushes up to 20 buffered
                    // signals in one burst, which overflowed the buffer and lost the
                    // tail (often the hangup or the offer's last ICE candidates).
                    _incomingCallSignals.emit(signal)
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to parse WebSocket message: ${e.message}")
        }
    }

}

/**
 * What a relay socket message says about the relay: its version, and whether that version's
 * app is out. The sign-in reply says both (`relay_version`, `app_ready`); an `app_release`
 * message names the version that came out. Null for a relay that says nothing (before 0.7.3).
 */
internal fun relayReport(msg: JsonObject): Pair<String, Boolean>? =
    when (msg["type"]?.jsonPrimitive?.contentOrNull) {
        "auth_ok" -> msg["relay_version"]?.jsonPrimitive?.contentOrNull
            ?.let { it to (msg["app_ready"]?.jsonPrimitive?.booleanOrNull == true) }
        "app_release" -> msg["version"]?.jsonPrimitive?.contentOrNull?.let { it to true }
        else -> null
    }

/** Where the relay socket is: waiting to retry (or never started), connecting, or open. */
internal enum class SocketState { IDLE, CONNECTING, OPEN }

/** A connect that takes longer than this is presumed stuck (TCP gives up after 5 s; TLS and the sign-in normally take a second or two more). */
internal const val CONNECT_GRACE_MS = 10_000L

/**
 * An open socket the relay has sent nothing on for this long is presumed dead. The app pings every
 * 25 s and the relay answers, so a live socket is never quiet this long; a frozen app's is, and so
 * is one on a network route that went away.
 */
internal const val QUIET_MS = 35_000L

/** Whether a screen may skip its poll: see [WebSocketClient.isLive]. */
internal fun socketIsLive(state: SocketState, sinceFrameMs: Long): Boolean =
    state == SocketState.OPEN && sinceFrameMs <= QUIET_MS

/**
 * Whether [WebSocketClient.connectUnlessWorking] should replace the current connection: always
 * while waiting out a retry delay, a connect that has taken over [CONNECT_GRACE_MS], and an open
 * socket that has been quiet for over [QUIET_MS].
 */
internal fun shouldReconnect(state: SocketState, sinceStateMs: Long, sinceFrameMs: Long): Boolean = when (state) {
    SocketState.IDLE -> true
    SocketState.CONNECTING -> sinceStateMs > CONNECT_GRACE_MS
    SocketState.OPEN -> sinceFrameMs > QUIET_MS
}

/**
 * Every call signal type the relay forwards (server/app/services/call_signals.py). Anything
 * missing here is dropped unread: 0.7.10's call_verify was, so the code to compare never
 * appeared. WebSocketCallSignalsTest keeps the two lists the same.
 */
internal val CALL_SIGNAL_TYPES = setOf(
    "call_offer", "call_answer", "call_ice_candidate",
    "call_hangup", "call_busy", "call_reject", "call_ringing", "call_ice_restart",
    "call_verify",
)

/**
 * Whether the reconnect backoff starts over after a session ends: only one that signed in and
 * lasted [stableMs] (30 s), or any during a call (its signals need quick retries). Before, every
 * sign-in started it over, so a socket that dropped after a few seconds reconnected every few
 * seconds (0.7.29, a shaky Wi-Fi).
 */
internal fun backoffStartsOver(signedInAt: Long, now: Long, callActive: Boolean, stableMs: Long = 30_000L): Boolean =
    signedInAt > 0 && (callActive || now - signedInAt >= stableMs)
