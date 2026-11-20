package com.sotto.app.domain.model

enum class CallDirection { OUTGOING, INCOMING }

/** How a connected call's audio travels. */
enum class CallPath {
    /** Straight between the phones: both turned on direct calls. */
    DIRECT,
    /** Through the relay's TURN server. */
    RELAYED,
}

enum class CallStatus {
    RINGING,
    CONNECTING,
    CONNECTED,
    ENDED,
    FAILED,
    /** ICE connection failed because no TURN relay was available. */
    NO_RELAY,
    REJECTED,
    BUSY,
    /** Recipient could not be reached (offline / signal not delivered). */
    UNAVAILABLE,
}

data class CallState(
    val callId: String,
    val peerId: String,
    val peerDisplayName: String,
    val direction: CallDirection,
    val status: CallStatus,
    val startedAt: Long = System.currentTimeMillis(),
    val connectedAt: Long? = null,
    val isMuted: Boolean = false,
    /** Where the call's audio can go, and where it goes now (null before the call has audio). */
    val audioRoutes: List<AudioRoute> = emptyList(),
    val audioRoute: AudioRoute? = null,
    /** True when no TURN relay server is available (STUN-only fallback). */
    val relayUnavailable: Boolean = false,
    /** True when the caller is not a known contact (fetched on demand). */
    val isNewContact: Boolean = false,
    /** True when the call uses hybrid PQC (ML-KEM-768 + X25519). */
    val pqcEstablished: Boolean = false,
    /** Connected before, but the connection dropped: trying to get it back (network change). */
    val reconnecting: Boolean = false,
    /** How the audio travels once connected; null until known. */
    val path: CallPath? = null,
    /** Both phones have high-quality calls on: the voice goes at the higher bitrate ("HD"). */
    val highQuality: Boolean = false,
    /** Louder is on: the other person's voice is lifted more than levelling alone does (ADR-23). */
    val louder: Boolean = false,
) {
    val isSpeakerOn: Boolean get() = audioRoute?.kind == AudioRouteKind.SPEAKER
}
