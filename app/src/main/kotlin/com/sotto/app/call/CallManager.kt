package com.sotto.app.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.sotto.app.BuildConfig
import com.sotto.app.crypto.KeyManager
import com.sotto.app.crypto.PqcProvider
import com.sotto.app.crypto.lengthPrefixed
import com.sotto.app.connection.DefaultNetworkMonitor
import com.sotto.app.data.remote.ApiHttpException
import com.sotto.app.data.remote.ApiService
import com.sotto.app.data.remote.WebSocketClient
import com.sotto.app.data.remote.dto.CallSignalDto
import com.sotto.app.data.remote.dto.CallSignalRequest
import com.sotto.app.data.remote.dto.IceServerDto
import com.sotto.app.data.local.dao.ConversationDao
import com.sotto.app.data.local.dao.MessageDao
import com.sotto.app.data.local.entity.ConversationEntity
import com.sotto.app.data.local.entity.MessageEntity
import com.sotto.app.domain.model.CallDirection
import com.sotto.app.domain.model.CallPath
import com.sotto.app.domain.model.CallState
import com.sotto.app.domain.model.CallStatus
import com.sotto.app.domain.model.MessageState
import com.sotto.app.domain.repository.CryptoEngine
import com.sotto.app.domain.repository.SessionKeys
import com.sotto.app.domain.repository.SettingsRepository
import com.sotto.app.notification.ChatNotificationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService,
    private val keyManager: KeyManager,
    private val cryptoEngine: CryptoEngine,
    private val classicalKeyAgreement: com.sotto.app.crypto.ClassicalKeyAgreement,
    private val pqcProvider: PqcProvider,
    private val webSocketClient: WebSocketClient,
    private val contactDao: com.sotto.app.data.local.dao.ContactDao,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val notificationManager: ChatNotificationManager,
    private val keyVerifier: com.sotto.app.crypto.ContactKeyVerifier,
    private val callNotifier: CallNotifier,
    private val blockedUserDao: com.sotto.app.data.local.dao.BlockedUserDao,
    private val networkMonitor: DefaultNetworkMonitor,
    private val settingsRepository: SettingsRepository,
    private val ringer: IncomingRinger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        scope.launch {
            webSocketClient.incomingCallSignals.collect { signal ->
                handleIncomingSignal(signal)
            }
        }
        // Mirror the call into system UI: ring for incoming calls even while the
        // app is in the background, and keep a microphone foreground service for
        // the whole call so audio keeps working with the screen off.
        scope.launch {
            _callState.collect { reflectInSystemUi(it) }
        }
    }

    private var callServiceRunning = false

    private fun reflectInSystemUi(state: CallState?) {
        val ringingIn = state != null && state.direction == CallDirection.INCOMING &&
            state.status == CallStatus.RINGING
        if (ringingIn) ringer.start() else ringer.stop()

        // Start the service once per call, from the first ring of an incoming one: it keeps
        // the app awake in the background while the call rings. It follows the call state
        // itself (updates its notification, stops when the call ends).
        val live = state != null && state.status !in TERMINAL_STATUSES
        if (live && !callServiceRunning) {
            callServiceRunning = startCallService()
        } else if (!live) {
            callServiceRunning = false
        }
        // The service shows the ringing notification itself; posting it here too keeps the
        // call visible if Android refused the service (the service adopts the same one).
        if (ringingIn) callNotifier.showIncoming(state!!) else callNotifier.cancelIncoming()
    }

    /**
     * The call's service again, e.g. from the call screen: in the foreground, Android lets
     * it take the microphone, which it may have refused while the call rang in the background.
     */
    fun refreshCallService() {
        val state = _callState.value ?: return
        if (state.status in TERMINAL_STATUSES) return
        callServiceRunning = startCallService()
    }

    /**
     * An outgoing or accepted call starts from the app on screen; an incoming one may ring
     * in the background, where Android allows starting it after a high-priority push or
     * while another foreground service runs. If Android refuses, the call goes on without.
     */
    private fun startCallService(): Boolean = try {
        androidx.core.content.ContextCompat.startForegroundService(
            context, android.content.Intent(context, CallService::class.java),
        )
        true
    } catch (e: Exception) {
        logWarn("Could not start call service: ${e.message}")
        false
    }

    /**
     * Call id the user accepted from the notification. Observable: the call screen
     * may already be showing (it opens as soon as a call rings), so it must react
     * when the request arrives, not only when it is first composed.
     */
    private val _autoAcceptRequest = MutableStateFlow<String?>(null)
    val autoAcceptRequest: StateFlow<String?> = _autoAcceptRequest.asStateFlow()

    fun requestAutoAccept(callId: String) {
        _autoAcceptRequest.value = callId
    }

    /** True once if the ringing call was accepted from its notification. */
    fun consumeAutoAccept(): Boolean {
        val id = _autoAcceptRequest.value ?: return false
        _autoAcceptRequest.value = null
        val st = _callState.value
        return st != null && st.callId == id && st.direction == CallDirection.INCOMING &&
            st.status == CallStatus.RINGING
    }
    private val json = CallPayloadJson

    private var webRtcEngine: WebRtcEngine? = null

    /**
     * The connection side of one call. Created with the call's state and dropped by
     * endCall(); used on the main thread only. WebRTC callbacks of an engine that belongs
     * to an earlier call find another (or no) link and are ignored.
     */
    private class CallLink(val callId: String, val isCaller: Boolean) {
        /** call_answer already taken: a replayed or repeated one must not reset the call. */
        var answered = false
        /** Callee: the caller was a contact before this call, one the user added or accepted. */
        var peerTrusted = false
        /** Caller: the offer proposed a direct call. */
        var directOffered = false
        /** Caller: the sound level its offer asked for ([CallQuality]). */
        var qualityOffered = CallQuality.STANDARD
        /** The call's agreed sound level: the lower of the two phones' ([agreedQuality]). */
        var quality = CallQuality.STANDARD
        /** This side shows the peer its addresses: both agreed to a direct call (or debug, no relay). */
        var direct = false
        /** The relay offered TURN for this call. */
        var hasTurn = false
        /** This side may show its addresses during the call: it agreed, or proposed and may switch. */
        var mayGoDirect = false
        /** Hosts of the relay's TURN servers, to tell a relayed path from a direct one. */
        var turnHosts: Set<String> = emptySet()
        /**
         * The other phone's local-network addresses, from the host candidates it sent: what a direct
         * Wi-Fi link (TDLS) would be set up with. The selected pair can't tell: WebRTC hides the
         * address of a peer-reflexive remote candidate. Never logged.
         */
        val peerLanAddresses: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        /** The ICE servers in use; TURN credentials are renewed through ICE restarts. */
        var iceServers: List<IceServerDto> = emptyList()
        /** How long the TURN credentials last from when they were fetched; times their renewal. */
        var turnLifetimeSec: Long = DEFAULT_TURN_LIFETIME_S
        /** The peer's capabilities, from its offer or answer ([CAPS_ICE_RESTART]). */
        var peerCaps = 0
        /** The peer's ICE candidates, applied in the right restart generation. */
        val gate = IceCandidateGate(MAX_PENDING_ICE_CANDIDATES)
        val restarts = RestartNegotiator(isCaller)
        /** The restart generation our new local candidates belong to. */
        var localGen = 0
        /** Caller: our non-relay candidates sent since the call went direct. */
        var directCandidatesSent = 0
        /** Orders the SDP steps of restarts: they suspend, and signals arrive meanwhile. */
        val sdpMutex = Mutex()
        /** When the connection dropped after having been up (elapsed realtime); null while up. */
        var lostAt: Long? = null
        /** Ends the call if a lost connection isn't back in time. */
        var lossJob: Job? = null
        /** Follows the phone's default network during the call. */
        var networkJob: Job? = null
        /** Caller: waits to restart ICE (grace period, fresh relay socket). */
        var restartTimer: Job? = null
        /** Caller: sends an unanswered restart offer again. */
        var retransmitJob: Job? = null
        /** Callee: asks the caller to restart while the connection is lost. */
        var requestJob: Job? = null
        /** Renews the TURN credentials before they expire. */
        var credentialJob: Job? = null
        /** Caller: checks that going direct released direct candidates. */
        var directCheckJob: Job? = null
        /** Logs how the direct attempts fared when a direct call runs over the relay. */
        var pairLogJob: Job? = null
        /** Logs the audio bitrate once, a little after the call connects, then the voice's level. */
        var audioLogJob: Job? = null
        /** Watches that audio keeps arriving from the other phone (watchPeer). */
        var peerWatchJob: Job? = null
        /** The path the call last took ([CallPath]), to notice a direct one lost. */
        var lastPath: CallPath? = null
        /** Logs what the direct pairs' checks did when a direct path was lost. */
        var directLossLogJob: Job? = null
        /** Follows the relay socket: a fresh one gets what may have gone into the dead one. */
        var sessionJob: Job? = null
        /** Beeps while the connection is lost. */
        var toneJob: Job? = null
        /** After our own network changed: clears "Reconnecting…" if the call survived the switch. */
        var recoveryJob: Job? = null

        fun cancel() {
            for (job in listOf(lossJob, networkJob, restartTimer, retransmitJob, requestJob, credentialJob, directCheckJob, pairLogJob, audioLogJob, directLossLogJob, sessionJob, toneJob, recoveryJob, peerWatchJob)) {
                job?.cancel()
            }
            recoveryJob = null
            peerWatchJob = null
            audioLogJob = null
            directLossLogJob = null
            pairLogJob = null
            sessionJob = null
            toneJob = null
            lossJob = null
            networkJob = null
            restartTimer = null
            retransmitJob = null
            requestJob = null
            credentialJob = null
            directCheckJob = null
        }
    }

    @Volatile
    private var link: CallLink? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val _callState = MutableStateFlow<CallState?>(null)
    val callState: StateFlow<CallState?> = _callState.asStateFlow()

    private val signalMutex = kotlinx.coroutines.sync.Mutex()
    private val sessionKeyMutex = kotlinx.coroutines.sync.Mutex()
    private var ringingTimeoutJob: Job? = null
    private val timeoutLock = Any()
    /** Per-call cache of derived session keys — cleared in endCall(). */
    private val _callSessionKeys = ConcurrentHashMap<String, SessionKeys>()
    /** Track seen signal signatures with timestamps to reject replays.
     *  Entries survive endCall() and are evicted after [SIGNATURE_TTL_MS].
     *  Insertion order (accessOrder=false) so time-based eviction is correct. */
    private val seenSignalSignatures = LinkedHashMap<String, Long>(64, 0.75f, false)
    /**
     * Verified ICE candidates that arrived before their call_offer, keyed by
     * "sender|callId". The caller sends candidates while the offer is still in
     * flight and signals are verified concurrently, so the offer often loses the
     * race; dropping them cost the callee the caller's candidates. Guarded by
     * [signalMutex].
     */
    private val earlyIceCandidates = LinkedHashMap<String, Pair<Long, MutableList<CallSignalDto>>>()
    /** Calls ("sender|callId") hung up before their offer was seen, so a late
     *  offer doesn't ring for a call that is already over. Guarded by [signalMutex]. */
    private val callsEndedBeforeOffer = LinkedHashMap<String, Long>()
    /** Guards [endCall] against concurrent invocation — only the first caller proceeds. */
    private val endCallGuard = AtomicBoolean(false)
    private val _callError = MutableStateFlow<String?>(null)
    val callError: StateFlow<String?> = _callError.asStateFlow()

    fun clearCallError() { _callError.value = null }

    fun initiateCall(peerId: String, peerDisplayName: String) {
        if (_callState.value != null) {
            _callError.value = "Already in a call"
            return
        }

        // Reset the guard so endCall() works for this new call. A previous
        // call's delayed cleanup coroutine may not have reset it yet.
        endCallGuard.set(false)

        // "<uuid>.<send time>": the call id is covered by our signature, so the
        // relay can't change the time, and the callee rejects stale offers.
        val callId = "${UUID.randomUUID()}.${System.currentTimeMillis()}"
        val callLink = CallLink(callId, isCaller = true)
        link = callLink
        _callState.value = CallState(
            callId = callId,
            peerId = peerId,
            peerDisplayName = peerDisplayName,
            direction = CallDirection.OUTGOING,
            status = CallStatus.CONNECTING,
        )

        scope.launch {
            try {
                callLink.directOffered = offerDirect(directCallsOn(), isTrustedContact(peerId))
                callLink.qualityOffered = qualityLevel(highQualityCallsOn())
                logDebug("Setting up WebRTC for outgoing call")
                setupWebRtc(peerId, direct = callLink.directOffered)
                logDebug("Creating SDP offer")
                val sdp = webRtcEngine?.createOffer()
                    ?: throw IllegalStateException("WebRTC engine not initialized")
                logDebug("Sending call_offer signal")
                val offer = SdpPayload(
                    sdp,
                    caps = CAPS_ICE_RESTART,
                    direct = if (callLink.directOffered) 1 else 0,
                    quality = callLink.qualityOffered,
                )
                val result = sendSignal(peerId, "call_offer", callId, json.encodeToString(offer))
                if (result == null) {
                    logWarn("call_offer failed to send — network error")
                    endCall(CallStatus.FAILED, callId)
                    return@launch
                }
                if (result) {
                    logDebug("call_offer delivered via WebSocket — waiting for call_ringing confirmation")
                } else {
                    logDebug("call_offer buffered by server — waiting for FCM to wake recipient")
                }
                startRingingTimeout(callId)
            } catch (e: Exception) {
                logError("Failed to initiate call", e)
                endCall(if (e is NoRelayException) CallStatus.NO_RELAY else CallStatus.FAILED, callId)
            }
        }
    }

    fun handleIncomingSignal(signal: CallSignalDto) {
        logDebug("Incoming signal: ${signal.signalType}")
        scope.launch {
            // --- Contact resolution + signature verification outside the mutex
            // so network requests don't block ICE candidate / hangup processing.
            val verifiedContact: com.sotto.app.data.local.entity.ContactEntity
            var needsPersist = false
            var trustedCaller = false
            try {
                val localUserId = keyManager.getUserId() ?: return@launch
                // Blocked caller: ignore silently (no ringing, no busy, no missed call).
                if (blockedUserDao.isBlocked(signal.senderId)) return@launch
                if (signal.signature.isEmpty()) {
                    logWarn("Rejecting unsigned call signal from ${signal.senderId.take(8)}")
                    return@launch
                }
                var contact = contactDao.getByUserId(signal.senderId)
                if (contact == null) {
                    // Check if we already fetched this contact for a pending call
                    // — avoids redundant network fetches for ICE candidates.
                    contact = _pendingNewContact?.takeIf { it.userId == signal.senderId }
                    if (contact == null) {
                        // Unknown sender — fetch their key bundle from the server
                        // for signature verification. Do NOT persist yet — only
                        // save after the signature is verified to prevent an
                        // attacker from polluting the contacts table.
                        logDebug("Contact not found locally for ${signal.senderId.take(8)}, fetching from server")
                        contact = fetchContactBundle(signal.senderId)
                        if (contact == null) {
                            logWarn("Rejecting call signal from unknown contact ${signal.senderId.take(8)} — server lookup failed")
                            return@launch
                        }
                        logDebug("Fetched key bundle for ${signal.senderId.take(8)}")
                    }
                    needsPersist = true
                }
                val sigPayload = lengthPrefixed(signal.senderId.toByteArray(Charsets.UTF_8)) +
                    lengthPrefixed(localUserId.toByteArray(Charsets.UTF_8)) +
                    lengthPrefixed(signal.signalType.toByteArray(Charsets.UTF_8)) +
                    lengthPrefixed(signal.callId.toByteArray(Charsets.UTF_8)) +
                    lengthPrefixed(signal.encryptedPayload.toByteArray(Charsets.UTF_8)) +
                    lengthPrefixed(signal.kemCiphertext.toByteArray(Charsets.UTF_8))
                val sig = Base64.decode(signal.signature, Base64.NO_WRAP)
                var valid = cryptoEngine.verify(sigPayload, sig, contact.publicSigningKey)
                if (!valid && !needsPersist) {
                    // Known contact: the peer may have rotated keys. Accept the new
                    // keys only if they are endorsed by the ones we trust.
                    val check = keyVerifier.check(signal.senderId)
                    if (check is com.sotto.app.crypto.KeyCheck.Known && check.rotated) {
                        contact = check.contact
                        valid = cryptoEngine.verify(sigPayload, sig, contact.publicSigningKey)
                    }
                }
                if (!valid) {
                    logWarn("Call signal signature verification failed for ${signal.senderId.take(8)}")
                    return@launch
                }
                // ML-DSA-65 post-quantum signature verification.
                // If the contact has a PQC signing key, the signature is REQUIRED —
                // allowing it to be omitted would let an attacker who breaks Ed25519
                // bypass the hybrid auth model (same enforcement as MessageRepositoryImpl).
                if (contact.pqcSigningKey.isNotEmpty() && signal.pqcSignature.isEmpty()) {
                    logWarn("ML-DSA signature missing from PQC-capable sender ${signal.senderId.take(8)}")
                    return@launch
                }
                if (signal.pqcSignature.isNotEmpty() && contact.pqcSigningKey.isNotEmpty()) {
                    try {
                        val pqcSig = Base64.decode(signal.pqcSignature, Base64.NO_WRAP)
                        val pqcValid = pqcProvider.verify(sigPayload, pqcSig, contact.pqcSigningKey)
                        if (!pqcValid) {
                            logWarn("ML-DSA signature verification failed for ${signal.senderId.take(8)}")
                            return@launch
                        }
                        logDebug("ML-DSA signature verified for ${signal.signalType} from ${signal.senderId.take(8)}")
                    } catch (e: Exception) {
                        logWarn("ML-DSA verification error for ${signal.senderId.take(8)}: ${e.message}")
                        return@launch
                    }
                }
                logDebug("Signature verified for ${signal.signalType} from ${signal.senderId.take(8)}")
                if (signal.signalType == "call_offer" && isStaleOffer(signal.callId)) {
                    // A replayed old offer (the relay can store and resend signed
                    // signals; the in-memory duplicate cache only covers 5 minutes
                    // and is gone after a restart): don't ring for it.
                    logWarn("Rejecting stale call offer from ${signal.senderId.take(8)}")
                    return@launch
                }
                if (signal.signalType == "call_offer") {
                    // Decided now: accepting the call adds an unknown caller to the contacts.
                    trustedCaller = isTrustedPeer(
                        existedBeforeCall = !needsPersist,
                        verified = contact.verified,
                        conversationApproved = conversationDao.getByContactId(signal.senderId)?.isApproved == true,
                    )
                }
                verifiedContact = contact
            } catch (e: Exception) {
                logError("Failed to verify signal: ${signal.signalType}", e)
                return@launch
            }

            // --- Replay check + lightweight validation under the mutex (no I/O).
            // All _pendingNewContact reads/writes are inside this lock so the
            // pattern is safe regardless of dispatcher.
            var earlyCandidatesForOffer: List<CallSignalDto> = emptyList()
            var missedCallToRecord: CallState? = null
            signalMutex.withLock {
                // Reject replayed signals (same signature = same signal)
                val now = System.currentTimeMillis()
                // Evict expired entries via iterator to avoid ConcurrentModificationException.
                val iter = seenSignalSignatures.entries.iterator()
                while (iter.hasNext()) {
                    if (now - iter.next().value > SIGNATURE_TTL_MS) iter.remove()
                }
                // Key by the decoded bytes: the Base64 decoder ignores junk characters,
                // so the raw string could be varied to bypass the cache.
                val sigKey = try {
                    Base64.encodeToString(Base64.decode(signal.signature, Base64.NO_WRAP), Base64.NO_WRAP)
                } catch (_: Exception) {
                    return@launch
                }
                if (seenSignalSignatures.containsKey(sigKey)) {
                    logDebug("Rejecting replayed call signal")
                    return@launch
                }
                seenSignalSignatures[sigKey] = now
                while (seenSignalSignatures.size > MAX_SEEN_SIGNATURES) {
                    seenSignalSignatures.remove(seenSignalSignatures.keys.first())
                }
                earlyIceCandidates.entries.removeAll { now - it.value.first > EARLY_SIGNAL_TTL_MS }
                callsEndedBeforeOffer.entries.removeAll { now - it.value > SIGNATURE_TTL_MS }
                val earlyKey = "${signal.senderId}|${signal.callId}"
                when (signal.signalType) {
                    "call_offer" -> {
                        if (callsEndedBeforeOffer.remove(earlyKey) != null) {
                            logDebug("Ignoring offer for a call the caller already hung up")
                            earlyIceCandidates.remove(earlyKey)
                            missedCallToRecord = CallState(
                                callId = signal.callId,
                                peerId = signal.senderId,
                                peerDisplayName = verifiedContact.displayName,
                                direction = CallDirection.INCOMING,
                                status = CallStatus.ENDED,
                            )
                        } else if (_callState.value != null) {
                            // Already in a call — flag for busy signal outside mutex
                        } else {
                            // Reset the guard for this new inbound call
                            endCallGuard.set(false)
                            val displayName = verifiedContact.displayName
                            // Only the caller of the call that is now ringing may become a
                            // contact (on accept). Other strangers' signals must not.
                            _pendingNewContact = if (needsPersist) verifiedContact else null
                            val isNew = needsPersist
                            link = CallLink(signal.callId, isCaller = false).also { it.peerTrusted = trustedCaller }
                            _callState.value = CallState(
                                callId = signal.callId,
                                peerId = signal.senderId,
                                peerDisplayName = displayName,
                                direction = CallDirection.INCOMING,
                                status = CallStatus.RINGING,
                                isNewContact = isNew,
                            )
                            _pendingOfferPayload = signal.encryptedPayload
                            earlyCandidatesForOffer = earlyIceCandidates.remove(earlyKey)?.second.orEmpty()
                            // Store KEM ciphertext for hybrid PQC key agreement
                            if (signal.kemCiphertext.isNotEmpty()) {
                                _pendingInboundKemCiphertext = Base64.decode(signal.kemCiphertext, Base64.NO_WRAP)
                            }
                        }
                    }
                    "call_answer" -> {
                        val state = _callState.value
                        if (state == null || signal.senderId != state.peerId || signal.callId != state.callId) {
                            logWarn("Ignoring answer: no matching active call")
                            return@launch
                        }
                        if (state.status in TERMINAL_STATUSES) {
                            logDebug("Ignoring call_answer: call already ended")
                            return@launch
                        }
                        val callLink = link
                        if (state.direction != CallDirection.OUTGOING || callLink?.callId != state.callId || callLink.answered) {
                            // A repeated answer would put a connected call back to connecting,
                            // and applying it again fails: that used to end the call.
                            logDebug("Ignoring call_answer: call already answered")
                            return@launch
                        }
                        callLink.answered = true
                        _callState.value = state.copy(status = CallStatus.CONNECTING)
                        // Answered: the no-answer timeout no longer applies (it would
                        // hang up a call answered just before it fired).
                        startConnectTimeout(state.callId)
                    }
                    "call_ice_candidate" -> {
                        val state = _callState.value
                        if (state == null || signal.senderId != state.peerId || signal.callId != state.callId) {
                            if (state?.callId != signal.callId) {
                                stashEarlyIceCandidate(earlyKey, signal, now)
                            } else {
                                logDebug("Ignoring ICE candidate: no matching active call")
                            }
                            return@launch
                        }
                    }
                    "call_ringing" -> {
                        val state = _callState.value
                        if (state == null || signal.senderId != state.peerId || signal.callId != state.callId) {
                            logDebug("Ignoring call_ringing: no matching active call")
                            return@launch
                        }
                        if (state.direction == CallDirection.OUTGOING && state.status == CallStatus.CONNECTING && link?.answered != true) {
                            _callState.value = state.copy(status = CallStatus.RINGING)
                            logDebug("Peer confirmed ringing")
                        }
                        return@launch
                    }
                    "call_ice_restart" -> {
                        val state = _callState.value
                        if (state == null || signal.senderId != state.peerId || signal.callId != state.callId ||
                            state.status in TERMINAL_STATUSES
                        ) {
                            logDebug("Ignoring call_ice_restart: no matching active call")
                            return@launch
                        }
                    }
                    "call_hangup", "call_reject", "call_busy" -> {
                        val state = _callState.value
                        if (state == null || signal.senderId != state.peerId || signal.callId != state.callId) {
                            if (signal.signalType == "call_hangup" && state?.callId != signal.callId) {
                                // Possibly overtook its own offer (offline buffer flush,
                                // concurrent verification): remember it for that offer.
                                callsEndedBeforeOffer[earlyKey] = now
                                earlyIceCandidates.remove(earlyKey)
                                while (callsEndedBeforeOffer.size > MAX_EARLY_CALLS) {
                                    callsEndedBeforeOffer.remove(callsEndedBeforeOffer.keys.first())
                                }
                            }
                            logDebug("Ignoring ${signal.signalType}: no matching active call")
                            return@launch
                        }
                        when (signal.signalType) {
                            "call_hangup" -> endCall(CallStatus.ENDED, signal.callId)
                            "call_reject" -> endCall(CallStatus.REJECTED, signal.callId)
                            "call_busy" -> endCall(CallStatus.BUSY, signal.callId)
                        }
                        return@launch
                    }
                }
            } // signalMutex

            // --- Heavy processing outside the mutex (network I/O, decrypt, WebRTC)
            try {
                when (signal.signalType) {
                    "call_offer" -> {
                        if (missedCallToRecord != null) {
                            recordCallEvent(missedCallToRecord!!, CallStatus.ENDED)
                        } else if (_callState.value?.callId == signal.callId) {
                            // Confirm to the caller that we're ringing
                            sendSignal(signal.senderId, "call_ringing", signal.callId, "")
                            startCalleeRingingTimeout(signal.callId)
                            if (earlyCandidatesForOffer.isNotEmpty()) {
                                logDebug("Applying ${earlyCandidatesForOffer.size} ICE candidates that arrived before the offer")
                            }
                            for (early in earlyCandidatesForOffer) {
                                try {
                                    processIceCandidatePayload(early)
                                } catch (e: Exception) {
                                    logWarn("Dropping early ICE candidate: ${e.message}")
                                }
                            }
                        } else {
                            // Already in a call — send busy outside the mutex
                            sendSignal(signal.senderId, "call_busy", signal.callId, "")
                        }
                    }
                    "call_answer" -> processAnswerPayload(signal)
                    "call_ice_candidate" -> processIceCandidatePayload(signal)
                    "call_ice_restart" -> processRestartSignal(signal)
                }
            } catch (e: Exception) {
                logError("Failed to process ${signal.signalType} payload", e)
                if (signal.signalType == "call_answer") {
                    endCall(CallStatus.FAILED, signal.callId)
                }
            }
        }
    }

    fun acceptCall() {
        val state = _callState.value ?: return
        if (state.direction != CallDirection.INCOMING || state.status != CallStatus.RINGING) return

        _callState.value = state.copy(status = CallStatus.CONNECTING, isNewContact = false)

        scope.launch {
            // Persist the new contact now that the user has approved the call.
            // Access under signalMutex to synchronize with handleIncomingSignal().
            signalMutex.withLock {
                _pendingNewContact?.takeIf { it.userId == state.peerId }?.let { contact ->
                    contactDao.upsert(contact)
                    logDebug("Persisted new contact ${contact.userId.take(8)} on call accept")
                    _pendingNewContact = null
                }
            }

            try {
                val offerPayload = _pendingOfferPayload
                if (offerPayload == null) {
                    logError("No pending offer payload — cannot accept call", null)
                    endCall(CallStatus.FAILED, state.callId)
                    return@launch
                }

                val callLink = link?.takeIf { it.callId == state.callId }
                    ?: throw IllegalStateException("No active call")
                // The offer comes first: whether it proposes a direct call decides how ours starts.
                val sdpJson = decryptPayload(state.peerId, state.callId, offerPayload)
                val sdpPayload = json.decodeFromString<SdpPayload>(sdpJson)
                logDebug("Decoded offer SDP (${sdpPayload.sdp.length} chars)")
                callLink.peerCaps = sdpPayload.caps
                val agreeDirect = answerDirect(directCallsOn(), callLink.peerTrusted, sdpPayload.direct == 1)
                if (sdpPayload.direct == 1) logInfo("Direct call proposed: ${if (agreeDirect) "agreed" else "declined, using the relay"}")

                logDebug("Setting up WebRTC...")
                setupWebRtc(state.peerId, direct = agreeDirect)
                logDebug("WebRTC setup complete")
                // HD only when both want it: the lower of the two levels, set before the offer applies.
                applyQuality(callLink, agreedQuality(qualityLevel(highQualityCallsOn()), sdpPayload.quality))

                val answerSdp = webRtcEngine?.handleRemoteOffer(sdpPayload.sdp)
                    ?: throw IllegalStateException("WebRTC engine not initialized")
                logDebug("Created answer SDP")

                applyCandidates(callLink.gate.onRemoteApplied(0))

                val answer = SdpPayload(
                    answerSdp,
                    caps = CAPS_ICE_RESTART,
                    direct = if (agreeDirect) 1 else 0,
                    quality = callLink.quality,
                )
                sendSignal(state.peerId, "call_answer", state.callId, json.encodeToString(answer))
                logDebug("call_answer sent")

                requestAudioFocusIfActive(state.callId)
            } catch (e: Exception) {
                logError("Failed to accept call: ${e::class.simpleName}: ${e.message}", e)
                endCall(if (e is NoRelayException) CallStatus.NO_RELAY else CallStatus.FAILED, state.callId)
                try {
                    sendSignal(state.peerId, "call_hangup", state.callId, "")
                } catch (_: Exception) { }
            }
        }
    }

    fun rejectCall() {
        val state = _callState.value ?: return
        endCall(CallStatus.REJECTED)
        scope.launch {
            val result = sendSignal(state.peerId, "call_reject", state.callId, "")
            if (result != true) logWarn("Reject signal not delivered — peer may not know call was declined")
        }
    }

    fun hangup() {
        val state = _callState.value ?: return
        if (state.status in TERMINAL_STATUSES) return
        endCall(CallStatus.ENDED)
        scope.launch {
            val result = sendSignal(state.peerId, "call_hangup", state.callId, "")
            if (result != true) logWarn("Hangup signal not delivered — peer may not know call ended")
        }
    }

    fun toggleMute() {
        val state = _callState.value ?: return
        val newMuted = !state.isMuted
        webRtcEngine?.setMicEnabled(!newMuted)
        _callState.update { it?.copy(isMuted = newMuted) }
    }

    /** Speaker on, or back to the phone (or the headset, if one is connected). */
    fun toggleSpeaker() {
        val state = _callState.value ?: return
        val target = if (state.audioRoute?.kind == com.sotto.app.domain.model.AudioRouteKind.SPEAKER) {
            preferredRoute(state.audioRoutes.filter { it.kind != com.sotto.app.domain.model.AudioRouteKind.SPEAKER })
        } else {
            state.audioRoutes.firstOrNull { it.kind == com.sotto.app.domain.model.AudioRouteKind.SPEAKER }
        }
        if (target != null) audioRouter?.select(target)
    }

    fun selectAudioRoute(route: com.sotto.app.domain.model.AudioRoute) {
        audioRouter?.select(route)
    }

    /** The call's audio route; exists while the call holds audio focus. */
    private var audioRouter: CallAudioRouter? = null

    @Volatile
    private var _pendingOfferPayload: String? = null
    /** KEM ciphertext received in an incoming call_offer — used by responder to decapsulate. */
    @Volatile
    private var _pendingInboundKemCiphertext: ByteArray? = null
    /** KEM ciphertext generated by the initiator — attached to the call_offer signal. */
    @Volatile
    private var _pendingOutboundKemCiphertext: String? = null
    /** Contact fetched from the server for an unknown caller — persisted only on accept. */
    @Volatile
    private var _pendingNewContact: com.sotto.app.data.local.entity.ContactEntity? = null

    /**
     * Replace the call's timeout, but only while [callId] is still the current
     * call: a late start from an earlier call must not cancel this call's timer.
     */
    private fun replaceTimeout(callId: String, block: suspend () -> Unit) = synchronized(timeoutLock) {
        if (_callState.value?.callId != callId) return@synchronized
        ringingTimeoutJob?.cancel()
        ringingTimeoutJob = scope.launch { block() }
    }

    private fun startRingingTimeout(callId: String) = replaceTimeout(callId) {
        kotlinx.coroutines.delay(RINGING_TIMEOUT_MS)
        val state = _callState.value
        // Only before an answer: a valid call_answer replaces this with the connect timeout.
        if (state != null && state.callId == callId && state.status in setOf(CallStatus.RINGING, CallStatus.CONNECTING)) {
            logWarn("Ringing timeout — no answer after ${RINGING_TIMEOUT_MS / 1000}s")
            sendSignal(state.peerId, "call_hangup", callId, "")
            endCall(CallStatus.UNAVAILABLE, callId)
        }
    }

    /** Caller side, after the answer: give up if the media path never connects. */
    private fun startConnectTimeout(callId: String) = replaceTimeout(callId) {
        kotlinx.coroutines.delay(CONNECT_TIMEOUT_MS)
        val state = _callState.value
        if (state != null && state.callId == callId && state.status == CallStatus.CONNECTING) {
            logWarn("Connect timeout — not connected ${CONNECT_TIMEOUT_MS / 1000}s after the answer")
            sendSignal(state.peerId, "call_hangup", callId, "")
            endCall(CallStatus.FAILED, callId)
        }
    }

    private fun startCalleeRingingTimeout(callId: String) = replaceTimeout(callId) {
        kotlinx.coroutines.delay(CALLEE_RINGING_TIMEOUT_MS)
        val state = _callState.value
        if (state != null && state.callId == callId && state.status == CallStatus.RINGING
            && state.direction == CallDirection.INCOMING
        ) {
            logWarn("Callee ringing timeout — not answered after ${CALLEE_RINGING_TIMEOUT_MS / 1000}s")
            endCall(CallStatus.ENDED, callId)
        }
    }

    /** Process answer payload — called outside signalMutex after validation. */
    private suspend fun processAnswerPayload(signal: CallSignalDto) {
        // Guard against call ending between mutex release and here — without this
        // check, requestAudioFocus() could leave AudioManager in MODE_IN_COMMUNICATION
        // after endCall() has already called abandonAudioFocus().
        if (webRtcEngine == null || _callState.value?.status in TERMINAL_STATUSES) return

        val callLink = link?.takeIf { it.callId == signal.callId } ?: return
        val sdpJson = decryptPayload(signal.senderId, signal.callId, signal.encryptedPayload)
        val sdpPayload = json.decodeFromString<SdpPayload>(sdpJson)
        callLink.peerCaps = sdpPayload.caps
        // The answer holds the lower of the two levels; never more than this side asked for.
        applyQuality(callLink, agreedQuality(callLink.qualityOffered, sdpPayload.quality))
        webRtcEngine?.handleRemoteAnswer(sdpPayload.sdp) ?: return

        applyCandidates(callLink.gate.onRemoteApplied(0))

        if (callLink.directOffered) {
            if (sdpPayload.direct == 1) {
                switchToDirect(callLink)
            } else {
                logInfo("Direct call declined: using the relay")
                if (!callLink.hasTurn && !callLink.direct) {
                    // We waited for the callee's agreement because there is no relay; without
                    // it there's no way to connect that both agreed to.
                    logWarn("No relay and no direct call: ending the call")
                    scope.launch { sendSignal(signal.senderId, "call_hangup", signal.callId, "") }
                    endCall(CallStatus.NO_RELAY, signal.callId)
                    return
                }
            }
        }

        requestAudioFocusIfActive(signal.callId)
    }

    /**
     * Caller: the callee agreed to a direct call. Our candidates gathered so far were held back
     * (relay-only); allowing all transports releases them to the peer, and the connection moves
     * to a direct path once one works.
     */
    private fun switchToDirect(callLink: CallLink) {
        if (callLink.direct) return
        val engine = webRtcEngine ?: return
        callLink.directCandidatesSent = 0
        val switched = engine.setIceTransportsType(PeerConnection.IceTransportsType.ALL)
        logInfo("Direct call agreed: ${if (switched) "sharing direct candidates" else "could not switch, staying on the relay"}")
        if (!switched) return
        callLink.direct = true
        // If WebRTC doesn't hand out the candidates it held back, a restart gathers them anew.
        callLink.directCheckJob?.cancel()
        callLink.directCheckJob = scope.launch {
            kotlinx.coroutines.delay(DIRECT_CHECK_MS)
            if (link === callLink && callLink.directCandidatesSent == 0) {
                scheduleRestart(callLink, "no direct candidates after going direct")
            }
        }
    }

    /**
     * The call's agreed sound level: the Opus bitrate this phone sends at (set through the
     * peer's descriptions, see [withOpusBitrate]) and the call screen's "HD".
     */
    private fun applyQuality(callLink: CallLink, quality: Int) {
        callLink.quality = quality
        webRtcEngine?.opusBitrate = opusBitrate(quality)
        logInfo("Call quality: ${if (quality >= CallQuality.HIGH) "HD" else "standard"}")
        _callState.update { if (it?.callId == callLink.callId) it.copy(highQuality = quality >= CallQuality.HIGH) else it }
    }

    private suspend fun highQualityCallsOn(): Boolean = try {
        settingsRepository.getPrivacySettings().first().highQualityCalls
    } catch (e: Exception) {
        logWarn("Could not read the high-quality calls setting: ${e.message}")
        false
    }

    /**
     * This call's sound settings: whose processing the microphone gets (ADR-25), returned for the
     * engine, and the leveler for the incoming voice (ADR-23), levelling as Settings has it and
     * Louder if it was on in the last call with this person.
     */
    private suspend fun startCallAudio(callId: String, peerId: String): VoiceProcessing {
        val settings = try {
            settingsRepository.getPrivacySettings().first()
        } catch (e: Exception) {
            logWarn("Could not read the call sound settings: ${e.message}")
            com.sotto.app.domain.model.PrivacySettings()
        }
        val louder = try {
            contactDao.getByUserId(peerId)?.louderVoice == true
        } catch (e: Exception) {
            logWarn("Could not read Louder for this contact: ${e.message}")
            false
        }
        CallAudioProcessing.leveler.startCall(levelling = settings.levelVoices, louder = louder)
        _callState.update { if (it?.callId == callId) it.copy(louder = louder) else it }
        logInfo("Voice levelling ${if (settings.levelVoices) "on" else "off"}, Louder ${if (louder) "on" else "off"}")
        return if (settings.webRtcVoiceProcessing) VoiceProcessing.WEBRTC else VoiceProcessing.PHONE
    }

    /** Louder on or off for the voice in this call; remembered for this person's next calls. */
    fun toggleLouder() {
        val state = _callState.value ?: return
        val louder = !state.louder
        CallAudioProcessing.leveler.louder = louder
        _callState.update { if (it?.callId == state.callId) it.copy(louder = louder) else it }
        logInfo("Louder ${if (louder) "on" else "off"} (${voiceLevelText()})")
        scope.launch {
            try {
                contactDao.setLouderVoice(state.peerId, louder)
            } catch (e: Exception) {
                logWarn("Could not remember Louder for this contact: ${e.message}")
            }
        }
    }

    /** "speech -31 dBFS, gain +8.0 dB": how loud the voice arrives, and what the leveler adds. */
    private fun voiceLevelText(): String {
        val leveler = CallAudioProcessing.leveler
        val speech = leveler.speechLevelDb
        val heard = if (speech.isNaN()) "no speech yet" else "speech ${speech.roundToInt()} dBFS"
        return "$heard, gain +${(leveler.gainDb * 10).roundToInt() / 10.0} dB"
    }

    private suspend fun directCallsOn(): Boolean = try {
        settingsRepository.getPrivacySettings().first().directCalls
    } catch (e: Exception) {
        logWarn("Could not read the direct calls setting: ${e.message}")
        false
    }

    /** Caller side of [isTrustedPeer]: the callee is a contact the user added or accepted. */
    private suspend fun isTrustedContact(peerId: String): Boolean {
        val contact = contactDao.getByUserId(peerId) ?: return false
        return isTrustedPeer(
            existedBeforeCall = true,
            verified = contact.verified,
            conversationApproved = conversationDao.getByContactId(peerId)?.isApproved == true,
        )
    }

    /** Candidates that waited for their remote description. */
    private fun applyCandidates(candidates: List<IceCandidateDto>) {
        if (candidates.isNotEmpty()) logDebug("Applying ${candidates.size} ICE candidates that waited for their description")
        for (candidate in candidates) {
            webRtcEngine?.addIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        }
    }

    /** Caller holds [signalMutex]. Bounded per call and in number of calls. */
    private fun stashEarlyIceCandidate(key: String, signal: CallSignalDto, now: Long) {
        val entry = earlyIceCandidates.getOrPut(key) { now to mutableListOf() }
        if (entry.second.size < MAX_PENDING_ICE_CANDIDATES) entry.second.add(signal)
        while (earlyIceCandidates.size > MAX_EARLY_CALLS) {
            earlyIceCandidates.remove(earlyIceCandidates.keys.first())
        }
        logDebug("Holding ICE candidate that arrived before its offer")
    }

    /** Process ICE candidate payload — called outside signalMutex after validation. */
    private suspend fun processIceCandidatePayload(signal: CallSignalDto) {
        val callLink = link?.takeIf { it.callId == signal.callId } ?: return
        val candidateJson = decryptPayload(signal.senderId, signal.callId, signal.encryptedPayload)
        val candidate = json.decodeFromString<IceCandidateDto>(candidateJson)
        logInfo("Remote ICE candidate: ${candidateType(candidate.sdp) ?: "?"}/${candidateFamily(candidate.sdp) ?: "?"} (generation ${candidate.gen})")
        if (candidateType(candidate.sdp) == "host") {
            candidateAddress(candidate.sdp)?.takeIf(::isLocalNetworkAddress)?.let { address ->
                callLink.peerLanAddresses += address
                // Already direct (a candidate after an ICE restart): keep TDLS off for it too.
                if (callLink.lastPath == CallPath.DIRECT) keepTdlsOff(address)
            }
        }
        // Applied now if its generation's description is; otherwise it waits (bounded) or,
        // from an older generation, is dropped.
        if (callLink.gate.offer(candidate)) {
            webRtcEngine?.addIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        }
    }

    /**
     * End the current call. Work on behalf of one call passes its [callId]: if
     * that call is already over and another has started (setup can block for
     * longer than the 2 s cleanup), the late failure must not end the new one.
     */
    private fun endCall(status: CallStatus, callId: String? = null) {
        if (callId != null && _callState.value?.callId != callId) {
            logDebug("Not ending the current call for a stale call's $status")
            return
        }
        // Only the first concurrent invocation proceeds — prevents double-dispose
        // of native WebRTC resources and double-zeroization of session keys.
        if (!endCallGuard.compareAndSet(false, true)) return
        val endingState = _callState.value
        _callState.value = endingState?.copy(status = status, reconnecting = false)
        ringingTimeoutJob?.cancel()
        ringingTimeoutJob = null
        link?.cancel()
        link = null
        releaseTones()
        val engine = webRtcEngine
        webRtcEngine = null
        _pendingOfferPayload = null
        _pendingInboundKemCiphertext = null
        _pendingOutboundKemCiphertext = null
        // Clear _pendingNewContact under signalMutex to stay consistent with
        // acceptCall() and handleIncomingSignal(). Launch a coroutine so we
        // can properly acquire the suspending Mutex instead of using tryLock,
        // which would race with signal handlers reading the field under lock.
        // Snapshot the reference so a rapid back-to-back call that sets a NEW
        // _pendingNewContact between now and when the coroutine runs is not
        // accidentally clobbered.
        val contactToClear = _pendingNewContact
        scope.launch {
            signalMutex.withLock {
                if (_pendingNewContact === contactToClear) {
                    _pendingNewContact = null
                }
            }
        }
        _callSessionKeys.values.forEach { keys ->
            keys.sendKey.fill(0)
            keys.receiveKey.fill(0)
        }
        _callSessionKeys.clear()
        synchronized(audioLock) { abandonAudioFocus() }
        scope.launch(tdlsDispatcher) { tdlsGuard.restoreAll() }

        // Dispose WebRTC resources off the main thread to avoid ANR
        if (engine != null) {
            scope.launch(Dispatchers.Default) {
                engine.dispose()
            }
        }

        // Record the call event in conversation history
        if (endingState != null) {
            scope.launch {
                recordCallEvent(endingState, status)
            }
        }

        // Clear state after a short delay so UI can show the end status.
        // Compare callId (not status) so a rapid back-to-back call isn't cleared.
        val endedCallId = endingState?.callId
        scope.launch {
            kotlinx.coroutines.delay(2000)
            if (_callState.value?.callId == endedCallId) {
                _callState.value = null
            }
            // Reset guard so the next call can use endCall()
            endCallGuard.set(false)
        }
    }

    /**
     * Insert a call event message into the conversation so calls appear
     * in the chat history alongside text messages.
     */
    private suspend fun recordCallEvent(callState: CallState, endStatus: CallStatus) {
        try {
            val localUserId = keyManager.getUserId() ?: return
            val peerId = callState.peerId

            // Don't create orphaned conversations for contacts not in the DB
            // (e.g., declined/missed calls from unknown callers)
            if (contactDao.getByUserId(peerId) == null) {
                logDebug("Skipping call event recording — contact ${peerId.take(8)} not persisted")
                return
            }

            val isOutgoing = callState.direction == CallDirection.OUTGOING
            val wasConnected = callState.connectedAt != null

            // Determine the call duration (0 if never connected)
            val durationSeconds = if (wasConnected) {
                ((System.currentTimeMillis() - callState.connectedAt!!) / 1000).coerceAtLeast(0)
            } else {
                0L
            }

            // Map direction + end status to a message state.
            // Use wasConnected (not durationSeconds) to avoid sub-second
            // connected calls being classified as missed.
            val messageState = when {
                isOutgoing && wasConnected -> MessageState.CALL_OUTGOING
                isOutgoing -> MessageState.CALL_OUTGOING_MISSED
                wasConnected -> MessageState.CALL_INCOMING
                else -> MessageState.CALL_MISSED
            }

            // Find or create conversation for this peer
            val conversationId = getOrCreateConversationId(peerId)

            val now = System.currentTimeMillis()
            val messageId = "call-${callState.callId}"

            messageDao.insert(
                MessageEntity(
                    id = messageId,
                    conversationId = conversationId,
                    senderId = if (isOutgoing) localUserId else peerId,
                    recipientId = if (isOutgoing) peerId else localUserId,
                    encryptedBody = ByteArray(0),
                    nonce = ByteArray(0),
                    plaintext = durationSeconds.toString(),
                    state = messageState.name,
                    timestamp = now,
                    expiresAt = null,
                    isOutgoing = isOutgoing,
                )
            )

            // Update the conversation preview
            val preview = when (messageState) {
                MessageState.CALL_OUTGOING -> "Voice call"
                MessageState.CALL_OUTGOING_MISSED -> when (endStatus) {
                    CallStatus.REJECTED -> "Call declined"
                    CallStatus.BUSY -> "Busy"
                    CallStatus.UNAVAILABLE -> "No answer"
                    else -> "Outgoing call"
                }
                MessageState.CALL_INCOMING -> "Voice call"
                MessageState.CALL_MISSED -> when (endStatus) {
                    CallStatus.REJECTED -> "Declined call"
                    else -> "Missed call"
                }
                else -> "Call"
            }
            updateConversationPreview(conversationId, peerId, preview, now)

            // Show missed call notification for incoming calls the user didn't answer or reject
            if (messageState == MessageState.CALL_MISSED && endStatus != CallStatus.REJECTED) {
                notificationManager.showMissedCallNotification(
                    callerId = peerId,
                    callerName = callState.peerDisplayName,
                    conversationId = conversationId,
                )
            }

            logDebug("Recorded call event: $messageState, duration=${durationSeconds}s")
        } catch (e: Exception) {
            logError("Failed to record call event", e)
        }
    }

    private suspend fun getOrCreateConversationId(contactId: String): String {
        val existing = conversationDao.getByContactId(contactId)
        if (existing != null) return existing.id

        val contact = contactDao.getByUserId(contactId)
        val displayName = contact?.displayName ?: contactId.take(8)

        val id = UUID.randomUUID().toString()
        return conversationDao.getOrInsert(
            ConversationEntity(
                id = id,
                contactId = contactId,
                contactDisplayName = displayName,
                lastMessagePreview = null,
                lastMessageTimestamp = null,
                unreadCount = 0,
                isEncrypted = true,
                isApproved = true,
            )
        )
    }

    private suspend fun updateConversationPreview(
        conversationId: String,
        contactId: String,
        preview: String,
        timestamp: Long,
    ) {
        conversationDao.updatePreview(conversationId, preview, timestamp, unreadDelta = 0)
    }

    /**
     * True if [callId] carries a send time ("<uuid>.<millis>") outside the accepted
     * window. Call ids without one (older clients) are accepted.
     */
    private fun isStaleOffer(callId: String): Boolean =
        isStaleCallOffer(callId, System.currentTimeMillis(), OFFER_MAX_SKEW_MS)

    /** No TURN relay available in a release build: refusing to connect directly (IP leak). */
    private class NoRelayException : IllegalStateException("No TURN relay available")

    /** [direct]: caller, it proposes a direct call; callee, it agreed to one (see [initialTransport]). */
    private suspend fun setupWebRtc(peerId: String, direct: Boolean) {
        val callIdAtStart = _callState.value?.callId ?: throw IllegalStateException("No active call")
        val callLink = link?.takeIf { it.callId == callIdAtStart }
            ?: throw IllegalStateException("No active call")
        val servers = try {
            apiService.getIceServers().iceServers
        } catch (e: Exception) {
            // No public fallback: a third-party STUN server would learn our address, and
            // without the relay's TURN server a release build doesn't call anyway.
            logWarn("Failed to fetch ICE servers", e)
            emptyList()
        }

        val hasTurn = servers.any { isTurnUrl(it.urls) }
        val transport = initialTransport(hasTurn, callLink.isCaller, direct, BuildConfig.DEBUG)
        if (!hasTurn) {
            logWarn("No TURN relay available")
            _callState.update { it?.copy(relayUnavailable = true) }
            // Without a relay WebRTC would send our IP addresses to the peer: only with a
            // direct call both agreed to (or in a debug build, without coturn).
            if (transport == Transport.NO_RELAY) throw NoRelayException()
        }
        // Relay-only unless both agreed to a direct call: the peer learns only the relay's address.
        val relayOnly = transport == Transport.RELAY
        callLink.hasTurn = hasTurn
        callLink.direct = !relayOnly
        // A call that may go direct also gets STUN on the relay itself, so this phone knows its
        // public address before it shares its candidates (the relay sees that address anyway).
        // A caller that proposed a direct call starts relay-only, then switches.
        callLink.mayGoDirect = !relayOnly || (callLink.isCaller && direct)
        callLink.turnHosts = turnHosts(servers)
        val iceServers = selectIceServers(servers, relayOnly = !callLink.mayGoDirect)
        callLink.iceServers = iceServers
        callLink.turnLifetimeSec = turnLifetime(iceServers)

        // The call may have ended while we were fetching ICE servers: endCall()
        // found no engine to dispose, so don't create one now (it would leak).
        if (_callState.value?.callId != callIdAtStart || endCallGuard.get()) {
            throw IllegalStateException("Call ended during setup")
        }

        logInfo("ICE servers: ${iceServers.size}, relay only: $relayOnly")
        val voiceProcessing = startCallAudio(callIdAtStart, peerId)
        val engine = WebRtcEngine(context, voiceProcessing)
        logInfo("Voice processing: ${engine.voiceProcessingDescription}")
        webRtcEngine = engine
        if (_callState.value?.callId != callIdAtStart || endCallGuard.get()) {
            // endCall() ran concurrently and may have missed this engine.
            if (webRtcEngine === engine) webRtcEngine = null
            engine.dispose()
            throw IllegalStateException("Call ended during setup")
        }

        // WebRTC calls these on its own threads: hop to the main thread, where the call's
        // state lives, and ignore them once this call is over.
        engine.onIceCandidate = { candidate ->
            scope.launch {
                if (link !== callLink) return@launch
                val type = candidateType(candidate.sdp)
                logInfo("Local ICE candidate: ${type ?: "?"}/${candidateFamily(candidate.sdp) ?: "?"} (generation ${callLink.localGen})")
                if (type != null && type != "relay") callLink.directCandidatesSent++
                val dto = IceCandidateDto(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp, gen = callLink.localGen)
                sendSignal(peerId, "call_ice_candidate", callIdAtStart, json.encodeToString(dto))
            }
        }
        engine.onConnectionStateChange = { iceState ->
            scope.launch { onIceConnectionState(callLink, iceState) }
        }
        engine.onSelectedPairChanged = { local, remote ->
            scope.launch {
                if (link !== callLink) return@launch
                val path = classifyCallPath(local.sdp, remote.sdp, callLink.turnHosts)
                // Candidate types only: never log addresses.
                logInfo("Call path: ${candidateType(local.sdp) ?: "?"} -> ${candidateType(remote.sdp) ?: "?"} ($path)")
                _callState.update { if (it?.callId == callLink.callId) it.copy(path = path) else it }
                if (path == CallPath.DIRECT) {
                    keepTdlsOff(candidateAddress(remote.sdp))
                    callLink.peerLanAddresses.forEach(::keepTdlsOff)
                }
                if (path == CallPath.RELAYED && callLink.lastPath == CallPath.DIRECT) logDirectLoss(callLink)
                callLink.lastPath = path
                if (path == CallPath.RELAYED && callLink.direct) logPairsLater(callLink)
            }
        }
        engine.onFrameCryptionStateChange = { state ->
            logDebug("Frame cryption state: $state")
        }
        engine.createPeerConnection(iceServers.map { it.toWebRtc() }, relayOnly)

        // Enable frame-level E2E encryption on media tracks
        try {
            val sessionKeys = ensureSessionKeys(peerId)
            val callId = _callState.value?.callId
                ?: throw IllegalStateException("No active call")
            val mediaKey = deriveMediaKey(sessionKeys, callId)
            val callSalt = callId.toByteArray(Charsets.UTF_8)
            engine.enableFrameEncryption(mediaKey, callSalt)
            mediaKey.fill(0)
            logDebug("Frame encryption enabled for call $callId")
        } catch (e: Exception) {
            logError("Failed to enable frame encryption", e)
            throw e // Fail the call — never allow unencrypted media
        }

        watchNetwork(callLink)
        watchSessions(callLink)
    }

    private fun IceServerDto.toWebRtc(): PeerConnection.IceServer {
        val builder = PeerConnection.IceServer.builder(urls)
        username?.let { builder.setUsername(it) }
        credential?.let { builder.setPassword(it) }
        return builder.createIceServer()
    }

    private fun onIceConnectionState(callLink: CallLink, iceState: PeerConnection.IceConnectionState) {
        // Only this engine's call, and never revive one that already ended (the engine is
        // disposed asynchronously after endCall()).
        if (link !== callLink) return
        val state = _callState.value ?: return
        if (state.callId != callLink.callId || state.status in TERMINAL_STATUSES) return
        when (iceState) {
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> {
                callLink.lossJob?.cancel()
                callLink.lossJob = null
                val recovered = callLink.lostAt != null
                callLink.lostAt?.let { logInfo("Connection back after ${SystemClock.elapsedRealtime() - it} ms") }
                callLink.lostAt = null
                callLink.requestJob?.cancel()
                callLink.requestJob = null
                // While nothing arrives from the other phone, ICE saying "connected" proves
                // nothing (it said so for a moment after every restart while the other phone
                // was gone): the beeping goes on until its audio does (watchPeer).
                if (!state.peerUnreachable) {
                    stopReconnectTone(callLink)
                    // The beeping stops and the call celebrates being back.
                    if (recovered) callSounds.playReconnected()
                }
                val firstConnect = state.connectedAt == null
                _callState.value = state.copy(
                    status = CallStatus.CONNECTED,
                    connectedAt = state.connectedAt ?: System.currentTimeMillis(),
                    reconnecting = false,
                )
                if (firstConnect) {
                    scheduleCredentialRenewal(callLink)
                    logAudioRateLater(callLink)
                    watchPeer(callLink)
                }
            }
            PeerConnection.IceConnectionState.DISCONNECTED,
            PeerConnection.IceConnectionState.FAILED -> {
                if (state.connectedAt == null) {
                    // Never connected: as before, a failure ends the call (the connect
                    // timeout covers a connection that never comes up).
                    if (iceState == PeerConnection.IceConnectionState.FAILED) {
                        endCall(if (state.relayUnavailable) CallStatus.NO_RELAY else CallStatus.FAILED, callLink.callId)
                    }
                    return
                }
                onConnectionLost(callLink, state, iceState.name)
                // What phase 1 can't heal (both phones moved, candidates lost to a dead
                // socket, the relay restarted) takes new ICE credentials and candidates.
                val failed = iceState == PeerConnection.IceConnectionState.FAILED
                if (callLink.isCaller) {
                    scheduleRestart(
                        callLink,
                        if (failed) "connection failed" else "connection lost",
                        delayMs = if (failed) 0 else DISCONNECT_GRACE_MS,
                        onlyIfLost = true,
                    )
                } else {
                    startRestartRequests(callLink, state.peerId, failed)
                }
            }
            else -> {}
        }
    }

    /**
     * A connected call lost its connection (network switch, a moment without signal). Both
     * phones keep gathering candidates on whatever network they have, so the call can come
     * back by itself; give it [CONNECTION_LOSS_TIMEOUT_MS], then end it and tell the peer.
     */
    private fun onConnectionLost(callLink: CallLink, state: CallState, reason: String) {
        if (callLink.lostAt == null) {
            callLink.lostAt = SystemClock.elapsedRealtime()
            logInfo("Connection lost ($reason): reconnecting")
        }
        if (!state.reconnecting) _callState.value = state.copy(reconnecting = true)
        startReconnectTone(callLink)
        callSounds.prepare()
        if (callLink.lossJob != null) return
        callLink.lossJob = scope.launch {
            kotlinx.coroutines.delay(CONNECTION_LOSS_TIMEOUT_MS)
            val current = _callState.value
            if (link !== callLink || current == null || current.callId != callLink.callId || !current.reconnecting) {
                return@launch
            }
            logWarn("Connection not back after ${CONNECTION_LOSS_TIMEOUT_MS / 1000} s: ending the call")
            // endCall() cancels this job: send the hangup from its own coroutine.
            scope.launch { sendSignal(current.peerId, "call_hangup", callLink.callId, "") }
            endCall(CallStatus.FAILED, callLink.callId)
        }
    }

    /**
     * Follows the default network during the call. After a switch (Wi-Fi <-> mobile) the
     * relay socket is likely dead, and the peer's candidates and hangup arrive over it.
     */
    private fun watchNetwork(callLink: CallLink) {
        if (link !== callLink) return
        callLink.networkJob?.cancel()
        callLink.networkJob = scope.launch {
            val tracker = NetworkChangeTracker()
            networkMonitor.defaultNetwork.takeWhile { link === callLink }.collect { network ->
                val event = tracker.update(network)
                if (event == NetworkEvent.NONE) return@collect
                val state = _callState.value
                // A connected call's path most likely went with that network: say so on this
                // phone now, rather than when ICE notices a second or more later.
                val connected = state != null && state.callId == callLink.callId &&
                    state.status == CallStatus.CONNECTED && state.connectedAt != null
                if (event == NetworkEvent.LOST) {
                    logInfo("Network lost during the call")
                    if (connected && state != null) onConnectionLost(callLink, state, "network lost")
                    return@collect
                }
                logInfo("Network ${if (event == NetworkEvent.SWITCHED) "changed" else "back"} during the call: reconnecting to the relay")
                webSocketClient.ensureConnected(urgent = true)
                if (connected && state != null) {
                    onConnectionLost(callLink, state, if (event == NetworkEvent.SWITCHED) "network changed" else "network back")
                    watchForRecovery(callLink)
                    onOwnNetworkChange(callLink)
                }
            }
        }
    }

    private var toneGenerator: ToneGenerator? = null
    private val callSounds = CallSounds(scope)

    /**
     * While the connection is lost, a soft double beep every [RECONNECT_TONE_INTERVAL_MS] in
     * the call's audio, so it's clear the silence is the connection and that it's being fixed.
     */
    private fun startReconnectTone(callLink: CallLink) {
        if (callLink.toneJob?.isActive == true) return
        callLink.toneJob = scope.launch {
            val tone = toneGenerator ?: try {
                ToneGenerator(AudioManager.STREAM_VOICE_CALL, RECONNECT_TONE_VOLUME).also { toneGenerator = it }
            } catch (e: RuntimeException) {
                logWarn("No reconnecting tone: ${e.message}")
                return@launch
            }
            while (link === callLink && _callState.value?.let { it.reconnecting || it.peerUnreachable } == true) {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
                kotlinx.coroutines.delay(RECONNECT_TONE_INTERVAL_MS)
            }
        }
    }

    private fun stopReconnectTone(callLink: CallLink) {
        callLink.toneJob?.cancel()
        callLink.toneJob = null
        toneGenerator?.stopTone()
    }

    private fun releaseTones() {
        toneGenerator?.release()
        toneGenerator = null
    }

    /**
     * Both phones agreed to a direct call, yet it runs over the relay: once ICE has had time to
     * try, log how the direct pairs fared (counts and states only), to tell a network that
     * blocks direct paths from a problem on our side.
     */
    /**
     * Logs the audio bitrate both ways once, a little after the call connects: shows whether HD
     * is in effect. Counts RTP payload, so the frame encryption adds about 12 kbit/s. Then logs
     * how loud the voice arrives and what the leveler adds, every half minute.
     */
    /**
     * Watches that audio keeps arriving from the other phone, and says so when it doesn't.
     *
     * ICE alone can't tell. On 2026-11-20 the other phone disappeared mid-call (an emulator that
     * was shut down). After every ICE restart, the connection reported itself back for a moment,
     * which reset the loss timeout and cleared "Reconnecting…" each time. The call went on for 17
     * minutes with nobody there, and the screen showed nothing wrong.
     *
     * The other phone sends audio as long as it's in the call ([PeerAudioWatch]). After
     * [PEER_UNREACHABLE_MS] without any, the call shows it can't reach the other phone and beeps.
     * After [PEER_GONE_MS], it ends ([CallStatus.PEER_GONE]).
     */
    private fun watchPeer(callLink: CallLink) {
        callLink.peerWatchJob?.cancel()
        callLink.peerWatchJob = scope.launch {
            val watch = PeerAudioWatch(SystemClock.elapsedRealtime())
            while (link === callLink) {
                kotlinx.coroutines.delay(PEER_CHECK_INTERVAL_MS)
                // No reading (stats didn't come): neither silence nor audio.
                val received = audioBytesNow()?.second ?: continue
                if (link !== callLink) return@launch
                val state = _callState.value ?: return@launch
                if (state.callId != callLink.callId || state.status != CallStatus.CONNECTED) continue
                val silentFor = watch.silentFor(received, SystemClock.elapsedRealtime())
                when (peerVerdict(silentFor)) {
                    PeerVerdict.PRESENT -> if (state.peerUnreachable) {
                        logInfo("Audio from the other phone again")
                        _callState.value = state.copy(peerUnreachable = false)
                        if (!state.reconnecting) {
                            stopReconnectTone(callLink)
                            callSounds.playReconnected()
                        }
                    }
                    PeerVerdict.UNREACHABLE -> if (!state.peerUnreachable) {
                        logWarn("Nothing from the other phone for ${silentFor / 1000} s")
                        _callState.value = state.copy(peerUnreachable = true)
                        callSounds.prepare()
                        startReconnectTone(callLink)
                    }
                    PeerVerdict.GONE -> {
                        logWarn("Nothing from the other phone for ${silentFor / 1000} s: ending the call")
                        // endCall() cancels this job: send the hangup from its own coroutine.
                        scope.launch { sendSignal(state.peerId, "call_hangup", callLink.callId, "") }
                        endCall(CallStatus.PEER_GONE, callLink.callId)
                        return@launch
                    }
                }
            }
        }
    }

    private fun logAudioRateLater(callLink: CallLink) {
        callLink.audioLogJob?.cancel()
        callLink.audioLogJob = scope.launch {
            kotlinx.coroutines.delay(AUDIO_LOG_DELAY_MS)
            if (link !== callLink) return@launch
            val first = audioBytesNow()
            val from = SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(AUDIO_LOG_WINDOW_MS)
            if (link !== callLink) return@launch
            val second = audioBytesNow()
            if (first != null && second != null) {
                val seconds = (SystemClock.elapsedRealtime() - from) / 1000.0
                fun kbps(bytes: Long) = (bytes * 8 / seconds / 1000).toInt()
                logInfo(
                    "Audio: sending ${kbps(second.first - first.first)} kbit/s, receiving " +
                        "${kbps(second.second - first.second)} kbit/s " +
                        "(${if (callLink.quality >= CallQuality.HIGH) "HD" else "standard"}, payload with encryption)",
                )
            }
            while (link === callLink) {
                logInfo("Voice: ${voiceLevelText()}")
                kotlinx.coroutines.delay(VOICE_LOG_INTERVAL_MS)
            }
        }
    }

    /** Audio bytes sent and received so far, or null when the stats don't come. */
    private suspend fun audioBytesNow(): Pair<Long, Long>? {
        val engine = webRtcEngine ?: return null
        val result = kotlinx.coroutines.CompletableDeferred<Pair<Long, Long>>()
        engine.audioBytes { sent, received -> result.complete(sent to received) }
        return kotlinx.coroutines.withTimeoutOrNull(STATS_TIMEOUT_MS) { result.await() }
    }

    private fun logPairsLater(callLink: CallLink) {
        callLink.pairLogJob?.cancel()
        callLink.pairLogJob = scope.launch {
            kotlinx.coroutines.delay(PAIR_LOG_DELAY_MS)
            val engine = webRtcEngine
            if (link !== callLink || engine == null || _callState.value?.path != CallPath.RELAYED) return@launch
            engine.candidatePairs { pairs ->
                scope.launch {
                    if (link === callLink) logInfo("Direct call over the relay: ${summarizeCandidatePairs(pairs, callLink.turnHosts)}")
                }
            }
        }
    }

    /**
     * A direct path was lost and the call went over the relay: log what the direct pairs' checks
     * and packets did then and a few seconds later. Whether the other phone's checks still arrive
     * tells a path broken one way from one the other phone gave up on. Counts only.
     */
    private fun logDirectLoss(callLink: CallLink) {
        callLink.directLossLogJob?.cancel()
        callLink.directLossLogJob = scope.launch {
            for ((label, wait) in listOf("now" to 0L, "${DIRECT_LOSS_RECHECK_MS / 1000} s later" to DIRECT_LOSS_RECHECK_MS)) {
                kotlinx.coroutines.delay(wait)
                val engine = webRtcEngine
                if (link !== callLink || engine == null) return@launch
                engine.candidatePairs { pairs ->
                    scope.launch {
                        if (link === callLink) {
                            logInfo("Direct path lost, $label: ${describeDirectPairs(pairs, callLink.turnHosts)}")
                        }
                    }
                }
            }
        }
    }

    // --- ICE restarts. Only between apps that both announced CAPS_ICE_RESTART. ---

    /**
     * Our own network changed: new ICE candidates on the new network right away (the caller
     * restarts, the callee asks it to). Signals sent while our relay socket is still coming
     * back wait at the relay, or are sent again once it is (see [watchSessions]).
     */
    private fun onOwnNetworkChange(callLink: CallLink) {
        if (callLink.isCaller) {
            scheduleRestart(callLink, "network change")
            return
        }
        val peerId = _callState.value?.peerId ?: return
        startRestartRequests(callLink, peerId, failed = true, fresh = true)
    }

    /**
     * We said "Reconnecting…" as soon as our network changed, before ICE noticed anything. If
     * the call carried on regardless (its path survived the switch), ICE never reports a drop,
     * and so never a recovery: check its state once a dead path would have shown as one.
     */
    private fun watchForRecovery(callLink: CallLink) {
        callLink.recoveryJob?.cancel()
        callLink.recoveryJob = scope.launch {
            kotlinx.coroutines.delay(RECOVERY_CHECK_DELAY_MS)
            while (link === callLink && _callState.value?.reconnecting == true) {
                val ice = webRtcEngine?.iceConnectionState()
                if (ice == PeerConnection.IceConnectionState.CONNECTED || ice == PeerConnection.IceConnectionState.COMPLETED) {
                    onIceConnectionState(callLink, ice)
                    break
                }
                kotlinx.coroutines.delay(RECOVERY_POLL_MS)
            }
        }
    }

    /**
     * A fresh relay socket during a call. Whatever the peer sent us meanwhile may have gone into
     * the dead one (the relay still counted it as connected): the caller sends its unanswered
     * restart offer again, the callee asks again while the connection is lost.
     */
    private fun watchSessions(callLink: CallLink) {
        if (link !== callLink) return
        callLink.sessionJob?.cancel()
        callLink.sessionJob = scope.launch {
            var last = webSocketClient.sessionEpoch.value
            webSocketClient.sessionEpoch.takeWhile { link === callLink }.collect { epoch ->
                if (epoch <= last) return@collect
                last = epoch
                val state = _callState.value ?: return@collect
                if (state.connectedAt == null || !supportsIceRestart(callLink.peerCaps)) return@collect
                if (callLink.isCaller) {
                    if (callLink.restarts.isPending) {
                        resendRestartOffer(callLink, state.peerId, callLink.restarts.currentGen)
                    }
                } else if (state.reconnecting) {
                    sendRestartRequest(callLink, state.peerId)
                }
            }
        }
    }

    /**
     * Caller: restart ICE after [delayMs]. [onlyIfLost]: not if the connection came back
     * meanwhile. A waiting restart is only replaced by an immediate one.
     */
    private fun scheduleRestart(
        callLink: CallLink,
        reason: String,
        delayMs: Long = 0,
        onlyIfLost: Boolean = false,
    ) {
        if (!callLink.isCaller || !supportsIceRestart(callLink.peerCaps) || link !== callLink) return
        val immediate = delayMs == 0L
        if (callLink.restartTimer?.isActive == true && !immediate) return
        callLink.restartTimer?.cancel()
        callLink.restartTimer = scope.launch {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            if (link !== callLink) return@launch
            if (onlyIfLost && _callState.value?.reconnecting != true) return@launch
            // Its own coroutine: replacing the timer must not cancel a restart under way.
            scope.launch { performRestart(callLink, reason) }
        }
    }

    /** Caller: new ICE credentials and candidates, offered to the callee as the next generation. */
    private suspend fun performRestart(callLink: CallLink, reason: String) {
        val peerId = _callState.value?.peerId ?: return
        val offer = callLink.sdpMutex.withLock {
            val engine = webRtcEngine
            val state = _callState.value
            if (link !== callLink || engine == null || state == null || state.status in TERMINAL_STATUSES) return
            val gen = callLink.restarts.beginRestart(SystemClock.elapsedRealtime()) ?: run {
                logInfo("ICE restart ($reason) skipped: one just started")
                return
            }
            renewTurnCredentialsIfDue(callLink, engine)
            // Before the offer is set: candidates of the new generation must carry its number.
            callLink.localGen = gen
            engine.restartIce()
            val sdp = try {
                engine.createOffer()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarn("ICE restart $gen ($reason): no offer: ${e.message}")
                return
            }
            logInfo("ICE restart $gen ($reason): sending the offer")
            IceRestartPayload(IceRestartPayload.OFFER, gen, sdp)
        }
        sendRestartSignal(callLink, peerId, offer)
        startRetransmits(callLink, peerId, offer.gen)
    }

    /** Caller: the offer may have gone into the callee's dead socket; send it again until answered. */
    private fun startRetransmits(callLink: CallLink, peerId: String, gen: Int) {
        callLink.retransmitJob?.cancel()
        callLink.retransmitJob = scope.launch {
            while (link === callLink && callLink.restarts.currentGen == gen && callLink.restarts.isRetransmitting) {
                kotlinx.coroutines.delay(RETRANSMIT_TICK_MS)
                if (callLink.restarts.retransmitDue(SystemClock.elapsedRealtime())) resendRestartOffer(callLink, peerId, gen)
            }
        }
    }

    /** Caller: the offer of [gen] again, with the candidates gathered since, unless a newer one replaced it. */
    private suspend fun resendRestartOffer(callLink: CallLink, peerId: String, gen: Int) {
        val sdp = callLink.sdpMutex.withLock {
            if (callLink.restarts.currentGen != gen || !callLink.restarts.isPending) return
            webRtcEngine?.localDescriptionSdp() ?: return
        }
        logInfo("ICE restart $gen: no answer yet, sending the offer again")
        sendRestartSignal(callLink, peerId, IceRestartPayload(IceRestartPayload.OFFER, gen, sdp))
    }

    /** A call_ice_restart from the peer: an offer (to the callee), an answer or a request (to the caller). */
    private suspend fun processRestartSignal(signal: CallSignalDto) {
        val callLink = link?.takeIf { it.callId == signal.callId } ?: return
        val payload = json.decodeFromString<IceRestartPayload>(
            decryptPayload(signal.senderId, signal.callId, signal.encryptedPayload),
        )
        when (payload.kind) {
            IceRestartPayload.OFFER -> if (!callLink.isCaller) answerRestart(callLink, signal.senderId, payload)
            IceRestartPayload.ANSWER -> if (callLink.isCaller) applyRestartAnswer(callLink, payload)
            IceRestartPayload.REQUEST -> if (callLink.isCaller) onRestartRequested(callLink, signal.senderId, payload)
            else -> logDebug("Ignoring call_ice_restart of an unknown kind")
        }
    }

    /** Callee: apply the caller's restart offer and answer it (again, if our answer got lost). */
    private suspend fun answerRestart(callLink: CallLink, peerId: String, offer: IceRestartPayload) {
        val answer = callLink.sdpMutex.withLock {
            val engine = webRtcEngine
            // Not before the call itself was answered (generation 0 applied).
            if (link !== callLink || engine == null || callLink.gate.appliedGen < 0) return
            when (callLink.restarts.onOffer(offer.gen)) {
                RestartNegotiator.OfferAction.IGNORE -> {
                    logDebug("Ignoring an old ICE restart offer")
                    return
                }
                RestartNegotiator.OfferAction.RESEND_ANSWER -> {
                    logInfo("ICE restart ${offer.gen}: offer again, sending the answer again")
                    engine.localDescriptionSdp() ?: return
                }
                RestartNegotiator.OfferAction.APPLY -> {
                    renewTurnCredentialsIfDue(callLink, engine)
                    val previousGen = callLink.localGen
                    callLink.localGen = offer.gen
                    val sdp = try {
                        engine.handleRemoteOffer(offer.sdp)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        callLink.localGen = previousGen
                        logWarn("ICE restart ${offer.gen}: could not answer: ${e.message}")
                        return
                    }
                    callLink.restarts.offerApplied(offer.gen)
                    applyCandidates(callLink.gate.onRemoteApplied(offer.gen))
                    logInfo("ICE restart ${offer.gen}: answering")
                    sdp
                }
            }
        }
        sendRestartSignal(callLink, peerId, IceRestartPayload(IceRestartPayload.ANSWER, offer.gen, answer))
    }

    /** Caller: the callee answered a restart. */
    private suspend fun applyRestartAnswer(callLink: CallLink, answer: IceRestartPayload) {
        callLink.sdpMutex.withLock {
            val engine = webRtcEngine
            if (link !== callLink || engine == null) return
            if (!callLink.restarts.onAnswer(answer.gen)) {
                logDebug("Ignoring an old ICE restart answer")
                return
            }
            callLink.retransmitJob?.cancel()
            try {
                engine.handleRemoteAnswer(answer.sdp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarn("ICE restart ${answer.gen}: could not apply the answer: ${e.message}")
                return
            }
            applyCandidates(callLink.gate.onRemoteApplied(answer.gen))
            logInfo("ICE restart ${answer.gen}: answered")
        }
    }

    /** Caller: the callee lost the connection and asks for a restart. */
    private suspend fun onRestartRequested(callLink: CallLink, peerId: String, request: IceRestartPayload) {
        val current = callLink.restarts.currentGen
        when {
            // It has our latest restart and still no connection: another one.
            request.gen >= current -> scheduleRestart(callLink, "the other phone asked")
            // It hasn't got our latest offer: send it again now.
            callLink.restarts.isPending -> resendRestartOffer(callLink, peerId, current)
            else -> logDebug("Ignoring an old ICE restart request")
        }
    }

    /**
     * Callee: while the connection is lost, ask the caller every few seconds to restart.
     * [fresh]: start over now (our network just changed), even if requests are already going.
     */
    private fun startRestartRequests(callLink: CallLink, peerId: String, failed: Boolean, fresh: Boolean = false) {
        if (callLink.isCaller || !supportsIceRestart(callLink.peerCaps)) return
        if (callLink.requestJob?.isActive == true && !fresh) return
        callLink.requestJob?.cancel()
        callLink.requestJob = scope.launch {
            // A very short drop can heal by itself.
            if (!failed) kotlinx.coroutines.delay(DISCONNECT_GRACE_MS)
            var sent = 0
            while (link === callLink && _callState.value?.reconnecting == true) {
                sendRestartRequest(callLink, peerId)
                // Quick at first, then spaced out: the relay takes 100 signals a minute.
                kotlinx.coroutines.delay(RESTART_REQUEST_INTERVALS_MS[sent.coerceAtMost(RESTART_REQUEST_INTERVALS_MS.lastIndex)])
                sent++
            }
        }
    }

    private suspend fun sendRestartRequest(callLink: CallLink, peerId: String) {
        logInfo("Asking the other phone to restart ICE")
        sendRestartSignal(callLink, peerId, IceRestartPayload(IceRestartPayload.REQUEST, callLink.restarts.currentGen))
    }

    private suspend fun sendRestartSignal(callLink: CallLink, peerId: String, payload: IceRestartPayload) {
        // Never to an app that doesn't know the signal type.
        if (link !== callLink || !supportsIceRestart(callLink.peerCaps)) return
        sendSignal(peerId, "call_ice_restart", callLink.callId, json.encodeToString(payload))
    }

    /**
     * TURN credentials last an hour, and the relay refuses an allocation, or the refresh of
     * one, with expired credentials. A restart gathers new relay candidates, so it gets fresh
     * credentials first when the current ones are close to expiring.
     */
    private suspend fun renewTurnCredentialsIfDue(callLink: CallLink, engine: WebRtcEngine) {
        val nowSec = System.currentTimeMillis() / 1000
        if (!turnCredentialExpiresSoon(callLink.iceServers, nowSec, turnRenewMarginSec(callLink.turnLifetimeSec))) return
        val servers = try {
            apiService.getIceServers().iceServers
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarn("Could not renew the TURN credentials: ${e.message}")
            return
        }
        val selected = selectIceServers(servers, relayOnly = !callLink.mayGoDirect)
        if (selected.none { isTurnUrl(it.urls) }) return
        if (engine.updateIceServers(selected.map { it.toWebRtc() })) {
            callLink.iceServers = selected
            callLink.turnLifetimeSec = turnLifetime(selected)
            callLink.turnHosts = turnHosts(servers)
            logInfo("TURN credentials renewed")
            scheduleCredentialRenewal(callLink)
        }
    }

    /** How long credentials just fetched last (by this phone's clock); an hour when unknown. */
    private fun turnLifetime(servers: List<IceServerDto>): Long {
        val expiry = turnCredentialExpiry(servers) ?: return DEFAULT_TURN_LIFETIME_S
        return (expiry - System.currentTimeMillis() / 1000).coerceAtLeast(1)
    }

    /** Before the TURN credentials expire, restart ICE to renew them (the callee asks the caller). */
    private fun scheduleCredentialRenewal(callLink: CallLink) {
        if (!supportsIceRestart(callLink.peerCaps) || link !== callLink) return
        val expiry = turnCredentialExpiry(callLink.iceServers) ?: return
        val delayMs = ((expiry - turnRenewLeadSec(callLink.turnLifetimeSec)) * 1000 - System.currentTimeMillis()).coerceAtLeast(0)
        callLink.credentialJob?.cancel()
        callLink.credentialJob = scope.launch {
            kotlinx.coroutines.delay(delayMs)
            val peerId = _callState.value?.peerId
            if (link !== callLink || peerId == null) return@launch
            logInfo("TURN credentials expire soon: renewing them with an ICE restart")
            if (callLink.isCaller) scheduleRestart(callLink, "TURN credentials") else sendRestartRequest(callLink, peerId)
        }
    }

    /**
     * Send a call signal to the peer. Retries on network errors only — if the
     * server accepted the signal but the recipient's WebSocket is down, the
     * server has already buffered the signal and sent an FCM push, so
     * client-side retries would be redundant.
     *
     * Returns true if the signal was delivered immediately via WebSocket,
     * false if accepted-but-not-delivered, null if all attempts failed.
     */
    private suspend fun sendSignal(peerId: String, signalType: String, callId: String, payload: String): Boolean? {
        val encrypted = if (payload.isNotEmpty()) encryptPayload(peerId, callId, payload) else ""
        val senderId = keyManager.getUserId() ?: return null
        // Attach KEM ciphertext to the call_offer (initiator → responder)
        val kemCt = if (signalType == "call_offer") {
            _pendingOutboundKemCiphertext.also { _pendingOutboundKemCiphertext = null } ?: ""
        } else ""
        val sigPayload = lengthPrefixed(senderId.toByteArray(Charsets.UTF_8)) +
            lengthPrefixed(peerId.toByteArray(Charsets.UTF_8)) +
            lengthPrefixed(signalType.toByteArray(Charsets.UTF_8)) +
            lengthPrefixed(callId.toByteArray(Charsets.UTF_8)) +
            lengthPrefixed(encrypted.toByteArray(Charsets.UTF_8)) +
            lengthPrefixed(kemCt.toByteArray(Charsets.UTF_8))
        val signature = Base64.encodeToString(keyManager.sign(sigPayload), Base64.NO_WRAP)
        // ML-DSA-65 post-quantum signature (if local keys are available)
        val pqcSig = try {
            val mlDsaPrivKey = keyManager.getMlDsaPrivateKey()
            if (mlDsaPrivKey != null) {
                try {
                    Base64.encodeToString(pqcProvider.sign(sigPayload, mlDsaPrivKey), Base64.NO_WRAP)
                } finally {
                    mlDsaPrivKey.fill(0)
                }
            } else ""
        } catch (e: Exception) {
            logWarn("ML-DSA signing failed, sending without PQC signature: ${e.message}")
            ""
        }
        val request = CallSignalRequest(
            recipientId = peerId,
            signalType = signalType,
            callId = callId,
            encryptedPayload = encrypted,
            signature = signature,
            pqcSignature = pqcSig,
            kemCiphertext = kemCt,
        )
        // Signals that end a call must arrive, also across a relay restart (an update takes
        // ~20 s): they keep trying for about half a minute. Others give up sooner.
        val attempts = if (signalType in MUST_ARRIVE_SIGNALS) MUST_ARRIVE_RETRIES else SIGNAL_SEND_RETRIES
        repeat(attempts) { attempt ->
            try {
                val response = apiService.sendCallSignal(request)
                if (response.delivered) return true
                // Server accepted and buffered the signal + sent FCM push.
                // No need to retry — return false to let the caller decide.
                logWarn("Signal $signalType accepted but not delivered (server buffered)")
                return false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ApiHttpException && e.isClientError) {
                    // Refused (rate limit, unknown recipient …): the same request would be refused again.
                    logWarn("Signal $signalType refused by the relay (${e.status})")
                    return null
                }
                logWarn("Failed to send signal $signalType (attempt ${attempt + 1}/$attempts): ${e.message}")
                if (attempt < attempts - 1) {
                    kotlinx.coroutines.delay(minOf(500L shl attempt, 8_000L))
                }
            }
        }
        return null
    }

    /**
     * Fetch a user's key bundle from the server WITHOUT persisting it.
     * Returns an in-memory ContactEntity for signature verification.
     * Only persist via [contactDao.upsert] after the signature is verified.
     */
    private suspend fun fetchContactBundle(userId: String): com.sotto.app.data.local.entity.ContactEntity? {
        return when (val check = keyVerifier.check(userId)) {
            is com.sotto.app.crypto.KeyCheck.Unknown -> check.keys.toNewContact(verified = false)
            is com.sotto.app.crypto.KeyCheck.Known -> check.contact
            else -> {
                logWarn("Could not obtain trusted keys for ${userId.take(8)}: $check")
                null
            }
        }
    }

    /**
     * Derive call session keys for [peerId] from current identity keys.
     *
     * Call keys are derived independently from the messaging Double Ratchet
     * to avoid corrupting ratchet state. Both sides compute the same keys
     * because:
     * 1. X25519 shared secret is symmetric (A·B == B·A)
     * 2. HKDF is deterministic
     * 3. Role assignment uses lexicographic comparison of public keys
     *
     * Keys are cached per-call to avoid redundant API calls for ICE
     * candidates. The cache is cleared in [endCall].
     */
    private suspend fun ensureSessionKeys(peerId: String): SessionKeys {
        // Fast path: already derived for this peer during this call
        _callSessionKeys[peerId]?.let { return it }

        // Serialize key derivation — concurrent ICE candidate coroutines must not
        // race through the KEM branch logic. Without this lock, a second racer
        // finds _pendingInboundKemCiphertext already consumed (null), enters the
        // encapsulate (initiator) branch, and derives different keys.
        return sessionKeyMutex.withLock {
            // Re-check inside lock — another coroutine may have derived while we waited
            _callSessionKeys[peerId]?.let { return@withLock it }

            // Use keys that passed verification against the stored contact (or
            // first-use keys for a new contact) — never raw server output.
            val verified = when (val check = keyVerifier.check(peerId)) {
                is com.sotto.app.crypto.KeyCheck.Known -> check.keys
                is com.sotto.app.crypto.KeyCheck.Unknown -> check.keys
                else -> throw IllegalStateException("No trusted keys for ${peerId.take(8)}: $check")
            }
            val remotePubIdKey = verified.publicIdentityKey

            val localKeyPair = keyManager.loadIdentityKeyPair()
                ?: throw IllegalStateException("No local identity key pair")

            // X25519 shared secret
            val classicalSecret = classicalKeyAgreement.agree(
                privateKey = localKeyPair.privateIdentityKey,
                remotePublicKey = remotePubIdKey,
            )

            // Deterministic initiator role: same comparison as RatchetSessionManager
            val isInitiator = localKeyPair.publicIdentityKey.toCallHex() < remotePubIdKey.toCallHex()
            // Private keys are only needed for the agreement above.
            localKeyPair.privateIdentityKey.fill(0)
            localKeyPair.privateSigningKey.fill(0)

            // PQC KEM handshake (initiator encapsulates, responder decapsulates)
            var pqcSecret = ByteArray(0)
            val inboundKem = _pendingInboundKemCiphertext
            _pendingInboundKemCiphertext = null

            if (inboundKem != null) {
                // Responder: decapsulate the KEM ciphertext from the call_offer
                val decapsulationKey = keyManager.getPqcDecapsulationKey()
                    ?: throw IllegalStateException("Received KEM ciphertext but no local decapsulation key")
                try {
                    pqcSecret = pqcProvider.decapsulate(inboundKem, decapsulationKey)
                } finally {
                    decapsulationKey.fill(0)
                }
            } else if (_callState.value?.direction == com.sotto.app.domain.model.CallDirection.INCOMING) {
                // Callee and the offer carried no KEM ciphertext. Only the caller may
                // encapsulate: if we did it here the two sides would derive different
                // keys (e.g. a hybrid caller calling a classical-only callee).
                val ownEk = keyManager.getPqcEncapsulationKey()
                if (ownEk != null && ownEk.isNotEmpty()) {
                    // We publish an ML-KEM key, so an honest caller encapsulates to it.
                    throw SecurityException("PQC downgrade refused: offer without KEM ciphertext")
                }
            } else if (verified.pqcEncapsulationKey.isNotEmpty()) {
                // Caller: encapsulate against peer's ML-KEM public key
                val remotePqcKey = verified.pqcEncapsulationKey
                val encapsulation = pqcProvider.encapsulate(remotePqcKey)
                pqcSecret = encapsulation.sharedSecret
                _pendingOutboundKemCiphertext = Base64.encodeToString(encapsulation.ciphertext, Base64.NO_WRAP)
            }

            val isPqcEstablished = pqcSecret.isNotEmpty()

            // Combine classical + PQC secrets via KMACXOF256 KDF, then zeroize inputs
            val ikm = if (isPqcEstablished) classicalSecret + pqcSecret else classicalSecret.copyOf()
            classicalSecret.fill(0)
            pqcSecret.fill(0)

            val sharedSecret = shake256Kdf(
                ikm = ikm,
                salt = "ChatControll-v1-call-init".toByteArray(Charsets.UTF_8),
                info = "hybrid-key-establishment".toByteArray(Charsets.UTF_8),
                length = 32,
            )
            ikm.fill(0)

            // Derive bidirectional chain keys
            val chainMaterial = shake256Kdf(
                ikm = sharedSecret,
                salt = "ChatControll-v1-call-chains".toByteArray(Charsets.UTF_8),
                info = "bidirectional-chains".toByteArray(Charsets.UTF_8),
                length = 64,
            )
            sharedSecret.fill(0)

            val chainA = chainMaterial.copyOfRange(0, 32)
            val chainB = chainMaterial.copyOfRange(32, 64)
            chainMaterial.fill(0)

            val sessionKeys = SessionKeys(
                sendKey = if (isInitiator) chainA else chainB,
                receiveKey = if (isInitiator) chainB else chainA,
                sessionId = "call-$peerId",
                pqcEstablished = isPqcEstablished,
            )
            if (isPqcEstablished) {
                logDebug("PQC hybrid key agreement established for call with ${peerId.take(8)}")
            }

            // Guard: if endCall() ran while we were suspended (e.g. during
            // fetchKeyBundle), the call is over and _callSessionKeys was already
            // zeroized+cleared. Re-inserting keys would leak un-zeroized material.
            val currentCall = _callState.value
            if (currentCall == null || currentCall.peerId != peerId ||
                currentCall.status in TERMINAL_STATUSES
            ) {
                sessionKeys.sendKey.fill(0)
                sessionKeys.receiveKey.fill(0)
                throw IllegalStateException("Call ended while deriving session keys for $peerId")
            }

            _callSessionKeys[peerId] = sessionKeys
            // Update CallState so the UI can display the actual crypto stack
            if (isPqcEstablished) {
                _callState.update { it?.copy(pqcEstablished = true) }
            }
            logDebug("Derived call session keys for ${peerId.take(8)} (initiator=$isInitiator)")
            sessionKeys
        }
    }

    /**
     * Derive a per-call ephemeral key from the session key and callId.
     * This provides call-level forward secrecy: compromise of one call's
     * key does not expose signals from other calls.
     */
    private fun deriveCallKey(sessionKey: ByteArray, callId: String): ByteArray {
        return shake256Kdf(
            ikm = sessionKey,
            salt = callId.toByteArray(Charsets.UTF_8),
            info = "ChatControll-call-signal".toByteArray(Charsets.UTF_8),
            length = 32,
        )
    }

    /**
     * Derive a symmetric media-encryption key that is identical on both peers.
     * XOR of sendKey and receiveKey is commutative (Alice's sendKey == Bob's
     * receiveKey), so both sides compute the same value.
     */
    private fun deriveMediaKey(sessionKeys: SessionKeys, callId: String): ByteArray {
        val size = minOf(sessionKeys.sendKey.size, sessionKeys.receiveKey.size)
        require(size > 0) { "Session keys are empty — cannot derive media key" }
        val xored = ByteArray(size) { i ->
            (sessionKeys.sendKey[i].toInt() xor sessionKeys.receiveKey[i].toInt()).toByte()
        }
        val key = shake256Kdf(
            ikm = xored,
            salt = callId.toByteArray(Charsets.UTF_8),
            info = "ChatControll-call-media".toByteArray(Charsets.UTF_8),
            length = 32,
        )
        xored.fill(0)
        return key
    }

    /**
     * Encrypt call signals using AES-256-GCM with a per-call ephemeral key
     * derived from the session key and callId. This bypasses the Double Ratchet
     * to avoid advancing the message chain — call signals are ephemeral and
     * may be lost/reordered.
     */
    private suspend fun encryptPayload(peerId: String, callId: String, plaintext: String): String {
        val sessionKeys = ensureSessionKeys(peerId)
        val callKey = deriveCallKey(sessionKeys.sendKey, callId)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(callKey, "AES"), GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        callKey.fill(0)
        val nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "$nonceB64.$ctB64"
    }

    private suspend fun decryptPayload(peerId: String, callId: String, encrypted: String): String {
        if (encrypted.isEmpty()) return ""
        val sessionKeys = ensureSessionKeys(peerId)
        val callKey = deriveCallKey(sessionKeys.receiveKey, callId)
        try {
            require(encrypted.contains('.')) { "Invalid encrypted payload format" }
            val parts = encrypted.split('.', limit = 2)
            val nonce = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(callKey, "AES"), GCMParameterSpec(128, nonce))
                val plaintext = cipher.doFinal(ciphertext)
                try {
                    return String(plaintext, Charsets.UTF_8)
                } finally {
                    plaintext.fill(0)
                }
            } finally {
                nonce.fill(0)
                ciphertext.fill(0)
            }
        } finally {
            callKey.fill(0)
        }
    }

    private val audioLock = Any()

    /**
     * Request audio focus only if [callId] is still the live call. The callers
     * suspend (network, WebRTC) before getting here; if the call ended meanwhile,
     * endCall() has already released focus and nothing would release it again.
     * The lock orders this against endCall()'s abandonAudioFocus().
     */
    private fun requestAudioFocusIfActive(callId: String) {
        synchronized(audioLock) {
            val st = _callState.value
            if (endCallGuard.get() || st == null || st.callId != callId || st.status in TERMINAL_STATUSES) return
            requestAudioFocus()
        }
    }

    private fun requestAudioFocus() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (audioRouter == null) {
            audioRouter = CallAudioRouter(context) { routes, current ->
                _callState.update { it?.copy(audioRoutes = routes, audioRoute = current) }
            }.also { it.start() }
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        audioFocusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        audioRouter?.stop()
        audioRouter = null
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun logDebug(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg)
    }

    /**
     * Wi-Fi's direct links between devices (TDLS) stay off with the other phone during a direct
     * call on the same Wi-Fi ([TdlsGuard]). One thread, in order, so a call's end can't overtake
     * the next call's request.
     */
    private val tdlsDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val tdlsGuard by lazy {
        val wifi = context.getSystemService(android.net.wifi.WifiManager::class.java)
        TdlsGuard { address, enabled ->
            // A phone without TDLS has nothing to turn off: counted as not done, so not logged.
            val tdlsWifi = wifi?.takeIf { it.isTdlsSupported } ?: error("no TDLS on this phone")
            tdlsWifi.setTdlsEnabled(address, enabled)
        }
    }

    private fun keepTdlsOff(remoteAddress: String?) {
        remoteAddress ?: return
        scope.launch(tdlsDispatcher) {
            // Never log the address.
            if (tdlsGuard.keepOff(remoteAddress)) logInfo("Wi-Fi direct link (TDLS) to the other phone kept off for this call")
        }
    }

    /** Shown in release builds too, to follow a call's connection: never addresses or keys. */
    private fun logInfo(msg: String) {
        Log.i(TAG, msg)
    }

    private fun logWarn(msg: String, e: Throwable? = null) {
        Log.w(TAG, msg, e)
    }

    private fun logError(msg: String, e: Throwable? = null) {
        Log.e(TAG, msg, e)
    }

    companion object {
        private const val TAG = "CallManager"
        private const val MAX_PENDING_ICE_CANDIDATES = 100
        private const val SIGNATURE_TTL_MS = 5 * 60 * 1000L // 5 minutes
        /** Offers older/newer than this are rejected (covers clock skew between phones;
         *  matches [SIGNATURE_TTL_MS] so the duplicate cache covers the rest). */
        private const val OFFER_MAX_SKEW_MS = SIGNATURE_TTL_MS
        private const val MAX_SEEN_SIGNATURES = 500
        /** Calls for which early candidates / early hangups are remembered. */
        private const val MAX_EARLY_CALLS = 8
        /** Early ICE candidates older than this are useless (offer never came). */
        private const val EARLY_SIGNAL_TTL_MS = 60_000L
        private const val SIGNAL_SEND_RETRIES = 3
        /** 0.5 + 1 + 2 + 4 + 8 + 8 + 8 s of waits: about half a minute. */
        private const val MUST_ARRIVE_RETRIES = 8
        private val MUST_ARRIVE_SIGNALS = setOf("call_hangup", "call_reject", "call_busy")
        /** How long to wait in RINGING before giving up (ms) — caller side. */
        private const val RINGING_TIMEOUT_MS = 35_000L
        /** Callee ringing timeout — longer than caller's so caller hangup arrives first. */
        private const val CALLEE_RINGING_TIMEOUT_MS = 45_000L
        /** Caller side: how long after the answer the media path may take to connect. */
        private const val CONNECT_TIMEOUT_MS = 30_000L
        /** A connected call whose connection dropped ends if it isn't back within this. */
        private const val CONNECTION_LOSS_TIMEOUT_MS = 30_000L
        /**
         * A connection that dropped gets this long before the caller restarts ICE (or the callee
         * asks it to). ICE reports a drop after a second without audio, so little more is needed.
         */
        private const val DISCONNECT_GRACE_MS = 300L
        private const val RETRANSMIT_TICK_MS = 250L
        /** Callee: gaps between its restart requests while the connection is lost. */
        private val RESTART_REQUEST_INTERVALS_MS = longArrayOf(1_500, 2_000, 3_000, 5_000, 8_000)
        /** The reconnecting beep: how often, and how loud (of the call volume, 0-100). */
        private const val RECONNECT_TONE_INTERVAL_MS = 2_000L
        private const val RECONNECT_TONE_VOLUME = 70
        /** After our network changed: when to start trusting ICE's "connected" again (past its 1 s receiving timeout). */
        private const val RECOVERY_CHECK_DELAY_MS = 1_500L
        private const val RECOVERY_POLL_MS = 500L
        /** The relay's TURN credentials normally last an hour (turnRenewLeadSec, turnRenewMarginSec). */
        private const val DEFAULT_TURN_LIFETIME_S = 60 * 60L
        /** Caller: after going direct, how long to wait for direct candidates before restarting. */
        private const val DIRECT_CHECK_MS = 3_000L
        /** How long ICE gets to try direct pairs before the log says how they fared. */
        private const val PAIR_LOG_DELAY_MS = 10_000L
        /** When, after connecting, the audio bitrate is measured, and over how long. */
        private const val AUDIO_LOG_DELAY_MS = 5_000L
        private const val AUDIO_LOG_WINDOW_MS = 10_000L
        private const val VOICE_LOG_INTERVAL_MS = 30_000L
        /** After a direct path is lost, when its pairs are looked at again. */
        private const val DIRECT_LOSS_RECHECK_MS = 5_000L
        private const val STATS_TIMEOUT_MS = 2_000L
        /** How often the call checks that audio arrives from the other phone (watchPeer). */
        private const val PEER_CHECK_INTERVAL_MS = 2_000L
        private val TERMINAL_STATUSES = setOf(
            CallStatus.ENDED, CallStatus.FAILED, CallStatus.NO_RELAY,
            CallStatus.REJECTED, CallStatus.BUSY, CallStatus.UNAVAILABLE, CallStatus.PEER_GONE,
        )
    }
}

/**
 * KMACXOF256-based key derivation for call encryption (NIST SP 800-185).
 *
 * Uses the Keccak sponge construction (SHA-3 family) instead of HMAC-SHA-256
 * so the entire post-quantum call path avoids SHA-2 dependencies.
 * KMACXOF256 provides built-in domain separation and input encoding,
 * eliminating the need for manual length-prefixing.
 *
 * Mapping: KMACXOF256(K=ikm, X=salt, S=info, L=length*8)
 *
 * Note: BC's [KMAC.doFinal(out, off, len)] uses the XOF variant (right_encode(0)),
 * which supports arbitrary output lengths.
 */
private fun shake256Kdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val kmac = org.bouncycastle.crypto.macs.KMAC(256, info)
    kmac.init(org.bouncycastle.crypto.params.KeyParameter(ikm))
    kmac.update(salt, 0, salt.size)
    val output = ByteArray(length)
    kmac.doFinal(output, 0, length)
    return output
}

private fun ByteArray.toCallHex(): String = joinToString("") { "%02x".format(it) }

/** See [CallManager]: "<uuid>.<millis>" call ids older/newer than [maxSkewMs] are stale. */
internal fun isStaleCallOffer(callId: String, nowMs: Long, maxSkewMs: Long): Boolean {
    val sentAt = callId.substringAfterLast('.', "").toLongOrNull() ?: return false
    return kotlin.math.abs(nowMs - sentAt) > maxSkewMs
}

/**
 * JSON of the encrypted call payloads. Defaults are left out and unknown keys ignored, so
 * apps of different versions understand each other (see [SdpPayload]).
 */
internal val CallPayloadJson = Json { ignoreUnknownKeys = true }

/**
 * The encrypted body of call_offer and call_answer. Flags are only written when set (the
 * JSON leaves out defaults) and older apps ignore unknown keys, so either side may be older.
 * [direct]: the caller proposes a direct call, or the callee agrees to it.
 * [quality]: the sound level ([CallQuality]) the caller wants, or in the answer the lower of
 * the two; older apps leave it out, which reads as standard.
 */
@Serializable
internal data class SdpPayload(val sdp: String, val caps: Int = 0, val direct: Int = 0, val quality: Int = 0)

@Serializable
data class IceCandidateDto(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val sdp: String,
    /** ICE restart generation: 0 (and left out) for the first exchange, and from older apps. */
    val gen: Int = 0,
)
