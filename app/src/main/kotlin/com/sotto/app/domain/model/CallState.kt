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
    /**
     * Connected, then nothing arrived from the other phone for a minute: it's off, out of reach,
     * or Sotto stopped on it. Ended so the call doesn't go on with nobody there.
     */
    PEER_GONE,
    /** The phone has no internet connection, so the call couldn't start. */
    NO_NETWORK,
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
    /**
     * Connected, but nothing has arrived from the other phone for a while: its audio stopped, so
     * it may be off or out of reach. Shown until its audio comes back; the call ends after a
     * minute ([CallStatus.PEER_GONE]). Unlike [reconnecting], a connection that only looks back
     * doesn't clear it.
     */
    val peerUnreachable: Boolean = false,
    /** How the audio travels once connected; null until known. */
    val path: CallPath? = null,
    /** Both phones have high-quality calls on: the voice goes at the higher bitrate ("HD"). */
    val highQuality: Boolean = false,
    /** Louder is on: the other person's voice is lifted more than levelling alone does (ADR-23). */
    val louder: Boolean = false,
) {
    val isSpeakerOn: Boolean get() = audioRoute?.kind == AudioRouteKind.SPEAKER
}
