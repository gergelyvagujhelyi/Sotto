package com.sotto.app.call

import android.content.Context
import android.util.Log
import com.sotto.app.BuildConfig
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.FrameCryptor
import org.webrtc.FrameCryptorAlgorithm
import org.webrtc.FrameCryptorFactory
import org.webrtc.FrameCryptorKeyDerivationAlgorithm
import org.webrtc.FrameCryptorKeyProvider
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.audio.JavaAudioDeviceModule
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Whose processing the microphone's sound goes through before it's sent (Settings > Calls,
 * ARCHITECTURE.md ADR-25).
 */
enum class VoiceProcessing {
    /**
     * The phone's own echo cancellation and noise suppression, tuned for its hardware, and its
     * own microphone level. The default. Where a phone has no echo canceller or noise
     * suppressor of its own, WebRTC's fills in, so a call never goes without.
     */
    PHONE,

    /** WebRTC's echo cancellation, noise suppression and automatic gain, the same on every phone. */
    WEBRTC,
}

class WebRtcEngine(context: Context, private val voiceProcessing: VoiceProcessing = VoiceProcessing.PHONE) {

    private val eglBase = EglBase.create()

    /** The microphone and speaker: with the phone's own effects, or without them for WebRTC's. */
    private val audioDeviceModule: JavaAudioDeviceModule

    private val factory: PeerConnectionFactory

    @Volatile
    private var peerConnection: PeerConnection? = null
    @Volatile
    private var disposed = false
    /**
     * Orders calls into the connection from app threads against [dispose], so nothing
     * touches it once freed. WebRTC's own threads (observer callbacks) never take it:
     * dispose() holds it while waiting for those threads.
     */
    private val lifetimeLock = Any()
    /** The connection's configuration, kept to change it mid-call (transport, servers). */
    private var config: PeerConnection.RTCConfiguration? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    // Frame encryptors have their own lock. The engine's monitor used to guard them, but
    // dispose() held it across native calls that wait for WebRTC's signaling thread, while
    // onAddTrack (on that thread) waited for the monitor: a possible deadlock.
    private val cryptorLock = Any()
    private var keyProvider: FrameCryptorKeyProvider? = null
    private var senderFrameCryptor: FrameCryptor? = null
    private var receiverFrameCryptor: FrameCryptor? = null

    // Called on WebRTC's own thread.
    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    var onConnectionStateChange: ((PeerConnection.IceConnectionState) -> Unit)? = null
    var onFrameCryptionStateChange: ((FrameCryptor.FrameCryptionState) -> Unit)? = null
    /** The candidate pair carrying the call changed: (local, remote). */
    var onSelectedPairChanged: ((IceCandidate, IceCandidate) -> Unit)? = null

    init {
        initOnce(context)
        val phone = voiceProcessing == VoiceProcessing.PHONE
        audioDeviceModule = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(phone && phoneHasEchoCanceler)
            .setUseHardwareNoiseSuppressor(phone && phoneHasNoiseSuppressor)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            // The incoming voice goes through the leveler before it plays (ADR-23).
            .apply { CallAudioProcessing.factory?.let { setAudioProcessingFactory(it) } }
            .createPeerConnectionFactory()
    }

    /** For the log: which processing the microphone gets in this call. */
    val voiceProcessingDescription: String
        get() = when (voiceProcessing) {
            VoiceProcessing.WEBRTC -> "WebRTC's echo cancellation, noise suppression and mic level"
            VoiceProcessing.PHONE ->
                "the phone's: echo cancellation ${if (phoneHasEchoCanceler) "phone" else "WebRTC (phone has none)"}, " +
                    "noise suppression ${if (phoneHasNoiseSuppressor) "phone" else "WebRTC (phone has none)"}, mic level phone"
        }

    /**
     * [relayOnly]: only relay candidates, so the peer never learns this phone's IP addresses
     * (the default; see CallManager for when a call may go direct).
     */
    fun createPeerConnection(iceServers: List<PeerConnection.IceServer>, relayOnly: Boolean): Unit = synchronized(lifetimeLock) {
        check(!disposed) { "Engine disposed" }
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Keeps gathering on new networks, so a call can recover after a switch.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = if (relayOnly) {
                PeerConnection.IceTransportsType.RELAY
            } else {
                PeerConnection.IceTransportsType.ALL
            }
            // Passive TCP candidates are useless between phones and each costs a signed signal.
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            // Candidates gathered but held back while relay-only are released when a call
            // switches to direct (setIceTransportsType).
            surfaceIceCandidatesOnIceTransportTypeChanged = true
            // Recover fast from a network switch. Audio sends 50 packets a second, so a second
            // without any means the path is gone (default 2.5 s). A path stops counting as
            // usable after 3 unanswered checks in 2 s (default 5 checks, 5 s), the pair in use
            // is checked every second, and backup pairs every 2 s (default 25 s), so the
            // connection can move to another pair that already works.
            iceConnectionReceivingTimeout = 1_000
            iceUnwritableTimeMs = 2_000
            iceUnwritableMinChecks = 3
            stableWritableConnectionPingIntervalMs = 1_000
            iceBackupCandidatePairPingInterval = 2_000
            // Relay-to-relay pairs carry audio at once, before their first check completes.
            presumeWritableWhenFullyRelayed = true
        }
        this.config = config

        val pc = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                this@WebRtcEngine.onIceCandidate?.invoke(candidate)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (BuildConfig.DEBUG) Log.d(TAG, "ICE connection state: $state")
                onConnectionStateChange?.invoke(state)
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                receiver?.let { setupReceiverFrameCryptor(it) }
            }
            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
                onSelectedPairChanged?.invoke(event.local, event.remote)
            }
        }) ?: throw IllegalStateException("Could not create a peer connection")
        peerConnection = pc

        // Add audio track. Echo cancellation and noise suppression are always asked for: WebRTC
        // then uses the phone's own when the audio device module has them on (PHONE) and its
        // software ones otherwise. Its automatic gain only runs with WEBRTC: on PHONE the
        // microphone level is the phone's own.
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(
                MediaConstraints.KeyValuePair(
                    "googAutoGainControl",
                    (voiceProcessing == VoiceProcessing.WEBRTC).toString(),
                ),
            )
        }
        audioSource = factory.createAudioSource(constraints)
        localAudioTrack = factory.createAudioTrack("audio0", audioSource)
        pc.addTrack(localAudioTrack)
        Unit
    }

    // The SDP operations throw on a closed connection, and give up after SDP_TIMEOUT_MS,
    // instead of suspending forever (they used to hang if the call ended meanwhile).

    suspend fun createOffer(): String = withTimeout(SDP_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            startWhileOpen(cont) { pc -> pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    if (disposed) return cont.fail("closed")
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() { cont.succeed(sdp.description) }
                        override fun onSetFailure(error: String?) { cont.fail(error) }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, sdp)
                }
                override fun onCreateFailure(error: String?) { cont.fail(error) }
                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
            }, audioOnlyConstraints()) }
        }
    }

    /**
     * The Opus bitrate both phones agreed on ([CallQuality]), or null for WebRTC's own. Set
     * before the peer's first description; applied to it and to those of ICE restarts, which
     * would otherwise bring the usual rate back.
     */
    @Volatile var opusBitrate: Int? = null

    /** The peer's description as this phone applies it: with the agreed Opus bitrate. */
    private fun fromPeer(sdp: String): String = opusBitrate?.let { withOpusBitrate(sdp, it) } ?: sdp

    suspend fun handleRemoteOffer(sdp: String): String = withTimeout(SDP_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            startWhileOpen(cont) { pc -> pc.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    if (disposed) return cont.fail("closed")
                    pc.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(answerSdp: SessionDescription) {
                            if (disposed) return cont.fail("closed")
                            pc.setLocalDescription(object : SdpObserver {
                                override fun onSetSuccess() { cont.succeed(answerSdp.description) }
                                override fun onSetFailure(error: String?) { cont.fail(error) }
                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                override fun onCreateFailure(p0: String?) {}
                            }, answerSdp)
                        }
                        override fun onCreateFailure(error: String?) { cont.fail(error) }
                        override fun onSetSuccess() {}
                        override fun onSetFailure(p0: String?) {}
                    }, audioOnlyConstraints())
                }
                override fun onSetFailure(error: String?) { cont.fail(error) }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, SessionDescription(SessionDescription.Type.OFFER, fromPeer(sdp))) }
        }
    }

    suspend fun handleRemoteAnswer(sdp: String): Unit = withTimeout(SDP_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            startWhileOpen(cont) { pc -> pc.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() { cont.succeed(Unit) }
                override fun onSetFailure(error: String?) { cont.fail(error) }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, SessionDescription(SessionDescription.Type.ANSWER, fromPeer(sdp))) }
        }
    }

    fun addIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        whileOpen<Unit>(Unit) { pc -> pc.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, sdp)) }
    }

    /** Switch between relay-only and direct mid-call (a call agreed to go direct). */
    fun setIceTransportsType(type: PeerConnection.IceTransportsType): Boolean = whileOpen(false) { pc ->
        val cfg = config ?: return@whileOpen false
        val previous = cfg.iceTransportsType
        cfg.iceTransportsType = type
        pc.setConfiguration(cfg).also { ok -> if (!ok) cfg.iceTransportsType = previous }
    }

    /** New ICE servers (fresh TURN credentials) for the next ICE restart. */
    fun updateIceServers(iceServers: List<PeerConnection.IceServer>): Boolean = whileOpen(false) { pc ->
        val cfg = config ?: return@whileOpen false
        val previous = cfg.iceServers
        cfg.iceServers = iceServers
        pc.setConfiguration(cfg).also { ok -> if (!ok) cfg.iceServers = previous }
    }

    /** The next offer restarts ICE: new credentials, new candidates, on whatever network works now. */
    fun restartIce() {
        whileOpen<Unit>(Unit) { pc -> pc.restartIce() }
    }

    /**
     * The candidate pairs ICE formed and how far each got, from the connection's stats. For the
     * log: [onResult] runs on a WebRTC thread.
     */
    internal fun candidatePairs(onResult: (List<CandidatePairState>) -> Unit) {
        whileOpen<Unit>(Unit) { pc ->
            pc.getStats(RTCStatsCollectorCallback { report ->
                val stats = report.statsMap.values
                val candidates = stats
                    .filter { it.type == "local-candidate" || it.type == "remote-candidate" }
                    .associateBy { it.id }
                onResult(stats.filter { it.type == "candidate-pair" }.map { pair ->
                    val local = candidates[pair.members["localCandidateId"] as? String]
                    val remote = candidates[pair.members["remoteCandidateId"] as? String]
                    fun count(member: String) = (pair.members[member] as? Number)?.toLong() ?: 0L
                    CandidatePairState(
                        localType = local?.members?.get("candidateType") as? String ?: "?",
                        remoteType = remote?.members?.get("candidateType") as? String ?: "?",
                        remoteAddress = (remote?.members?.get("address") ?: remote?.members?.get("ip")) as? String,
                        state = pair.members["state"] as? String ?: "?",
                        requestsSent = count("requestsSent"),
                        responsesReceived = count("responsesReceived"),
                        requestsReceived = count("requestsReceived"),
                        responsesSent = count("responsesSent"),
                        packetsSent = count("packetsSent"),
                        packetsReceived = count("packetsReceived"),
                    )
                })
            })
        }
    }

    /**
     * Audio bytes sent and received so far (RTP payload, so with the frame encryption's
     * overhead). For the log: [onResult] runs on a WebRTC thread, and not at all once closed.
     */
    internal fun audioBytes(onResult: (sent: Long, received: Long) -> Unit) {
        whileOpen<Unit>(Unit) { pc ->
            pc.getStats(RTCStatsCollectorCallback { report ->
                val audio = report.statsMap.values.filter { it.members["kind"] == "audio" }
                fun total(type: String, member: String) = audio.filter { it.type == type }
                    .sumOf { (it.members[member] as? Number)?.toLong() ?: 0L }
                onResult(total("outbound-rtp", "bytesSent"), total("inbound-rtp", "bytesReceived"))
            })
        }
    }

    /** ICE's own view of the connection right now; null once closed. */
    fun iceConnectionState(): PeerConnection.IceConnectionState? = whileOpen(null) { pc -> pc.iceConnectionState() }

    /** The current local description, with the candidates gathered so far (resent on a lost restart). */
    fun localDescriptionSdp(): String? = whileOpen(null) { pc -> pc.localDescription?.description }

    /** Runs [block] on the open connection, or returns [closed] once it is disposed. */
    private inline fun <T> whileOpen(closed: T, block: (PeerConnection) -> T): T {
        if (disposed) return closed
        synchronized(lifetimeLock) {
            val pc = peerConnection
            if (disposed || pc == null) return closed
            return block(pc)
        }
    }

    /** Starts an SDP operation, or fails [cont] if the connection is already gone. */
    private inline fun <T> startWhileOpen(cont: CancellableContinuation<T>, start: (PeerConnection) -> Unit) {
        if (!whileOpen(false) { pc -> start(pc); true }) cont.fail("Peer connection closed")
    }

    private fun audioOnlyConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
    }

    private fun <T> CancellableContinuation<T>.succeed(value: T) {
        if (isActive) resume(value)
    }

    private fun CancellableContinuation<*>.fail(error: String?) {
        if (isActive) resumeWithException(IllegalStateException(error ?: "WebRTC operation failed"))
    }

    /**
     * Enable frame-level E2E encryption on all RTP sender/receiver tracks
     * using AES-GCM via the WebRTC FrameCryptor API.
     *
     * @param salt Per-call salt for HKDF key derivation (e.g. callId bytes).
     *             Binds derived frame keys to this specific call session.
     */
    fun enableFrameEncryption(key: ByteArray, salt: ByteArray = ByteArray(0)) = whileOpen<Unit>(Unit) { pc ->
        enableFrameEncryption(pc, key, salt)
    }

    private fun enableFrameEncryption(pc: PeerConnection, key: ByteArray, salt: ByteArray) = synchronized(cryptorLock) {

        // Signature (webrtc-sdk 144): (sharedKey, ratchetSalt, ratchetWindowSize,
        // uncryptedMagicBytes, failureTolerance, keyRingSize,
        // discardFrameWhenCryptorNotReady, kdf). The key itself is NOT a parameter:
        // it must be installed with setSharedKey, or frames are sent/received
        // without a key.
        val kp = FrameCryptorFactory.createFrameCryptorKeyProvider(
            /* sharedKey */ true,
            /* ratchetSalt (KDF salt) */ if (salt.isNotEmpty()) salt else DEFAULT_FRAME_KDF_SALT,
            /* ratchetWindowSize */ 0,
            /* uncryptedMagicBytes */ ByteArray(0),
            /* failureTolerance */ -1,
            /* keyRingSize */ 16,
            /* discardFrameWhenCryptorNotReady: never send or play unencrypted frames */ true,
            FrameCryptorKeyDerivationAlgorithm.HKDF,
        )
        check(kp.setSharedKey(0, key)) { "Failed to install the frame encryption key" }
        keyProvider = kp

        // Encrypt outgoing frames
        val senders = pc.senders
        if (senders.isNotEmpty()) {
            senderFrameCryptor = FrameCryptorFactory.createFrameCryptorForRtpSender(
                factory, senders[0], PARTICIPANT_LOCAL,
                FrameCryptorAlgorithm.AES_GCM, kp,
            ).also {
                it.setObserver { _, state ->
                    if (BuildConfig.DEBUG) Log.d(TAG, "Sender frame cryption: $state")
                    onFrameCryptionStateChange?.invoke(state)
                }
                it.setEnabled(true)
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "Sender FrameCryptor enabled")
        }

        // Decrypt incoming frames (receivers may arrive later via onAddTrack)
        for (receiver in pc.receivers) {
            setupReceiverFrameCryptor(receiver)
        }
    }

    private fun setupReceiverFrameCryptor(receiver: RtpReceiver) = synchronized(cryptorLock) {
        if (receiverFrameCryptor != null || disposed) return@synchronized
        val kp = keyProvider ?: return@synchronized

        receiverFrameCryptor = FrameCryptorFactory.createFrameCryptorForRtpReceiver(
            factory, receiver, PARTICIPANT_REMOTE,
            FrameCryptorAlgorithm.AES_GCM, kp,
        ).also {
            it.setObserver { _, state ->
                if (BuildConfig.DEBUG) Log.d(TAG, "Receiver frame cryption: $state")
                onFrameCryptionStateChange?.invoke(state)
            }
            it.setEnabled(true)
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Receiver FrameCryptor enabled")
    }

    fun setMicEnabled(enabled: Boolean) {
        whileOpen<Unit>(Unit) { localAudioTrack?.setEnabled(enabled) }
    }

    fun dispose(): Unit = synchronized(lifetimeLock) {
        if (disposed) return@synchronized
        disposed = true
        // Taken out under the cryptor lock, disposed outside it: disposing waits for
        // WebRTC's threads, and onAddTrack takes that lock on one of them.
        val cryptors = synchronized(cryptorLock) {
            listOfNotNull(senderFrameCryptor, receiverFrameCryptor).also {
                senderFrameCryptor = null
                receiverFrameCryptor = null
            }
        }
        val kp = synchronized(cryptorLock) { keyProvider.also { keyProvider = null } }
        cryptors.forEach { it.dispose() }
        kp?.dispose()
        localAudioTrack?.dispose()
        audioSource?.dispose()
        peerConnection?.dispose()
        peerConnection = null
        localAudioTrack = null
        audioSource = null
        factory.dispose()
        // Ours to release: the factory only borrowed it.
        audioDeviceModule.release()
        eglBase.release()
    }

    companion object {
        private const val TAG = "WebRtcEngine"
        private const val SDP_TIMEOUT_MS = 10_000L
        private const val PARTICIPANT_LOCAL = "local"
        private const val PARTICIPANT_REMOTE = "remote"
        private val DEFAULT_FRAME_KDF_SALT = "ChatControll-frame-v1".toByteArray(Charsets.UTF_8)

        /** The phone has its own echo canceller and noise suppressor (Android audio effects). */
        private val phoneHasEchoCanceler: Boolean by lazy { JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported() }
        private val phoneHasNoiseSuppressor: Boolean by lazy { JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported() }
        @Volatile private var initialized = false

        internal fun initOnce(context: Context) {
            if (!initialized) {
                synchronized(this) {
                    if (!initialized) {
                        PeerConnectionFactory.initialize(
                            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                                .createInitializationOptions()
                        )
                        initialized = true
                    }
                }
            }
        }
    }
}
