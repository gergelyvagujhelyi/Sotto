package com.sotto.app.domain.repository

/**
 * Abstraction over the hybrid classical + post-quantum cryptography layer.
 *
 * All implementations MUST:
 * - Never log plaintext, keys, or sensitive identifiers.
 * - Clear key material from memory when no longer needed.
 * - Support algorithm agility so classical or PQC primitives can be swapped.
 */
interface CryptoEngine {

    /** Generate a new identity keypair (signing + key-agreement). Returns the public keys. */
    suspend fun generateIdentity(): KeyPair

    /**
     * Make sure there is a session we can send on with [peer]. If none exists,
     * create one with us as initiator (fresh ephemeral ratchet key, plus ML-KEM
     * encapsulation when [PeerKeys.pqcEncapsulationKey] is present).
     *
     * [peer] MUST contain keys that were verified (see ContactKeyVerifier),
     * never raw server output.
     */
    suspend fun ensureSession(peer: PeerKeys): SessionInfo

    /** Encrypt for [peer], creating a session first if necessary. */
    suspend fun encrypt(peer: PeerKeys, plaintext: ByteArray): EncryptedEnvelope

    /**
     * Decrypt an envelope from [peer]. Session-init messages create the
     * responder side of a new session. Decryption is transactional: a message
     * that fails to decrypt never changes or deletes session state.
     *
     * @param requirePqc reject a new session from this peer that lacks ML-KEM.
     * @throws DuplicateMessageException for replays / already-consumed messages.
     */
    suspend fun decrypt(peer: PeerKeys, envelope: EncryptedEnvelope, requirePqc: Boolean = false): ByteArray

    /** Stop sending on the current session (the next send starts a new one).
     *  Older sessions are kept for a while to decrypt in-flight messages. */
    suspend fun resetSession(peerUserId: String)

    /** Forget all session state for a peer. */
    suspend fun deleteSession(peerUserId: String)

    /** Forget all session state (e.g. after our own key rotation). */
    suspend fun clearAllSessions()

    /** Info about the current session with a peer, or null if there is none. */
    suspend fun sessionInfo(peerUserId: String): SessionInfo?

    /**
     * True if [encrypt] would use an existing session for [peer] (one bound to
     * this identity key that can send), i.e. it would not need to start a new
     * session and therefore does not need the peer's ML-KEM key.
     */
    suspend fun hasSendableSession(peer: PeerKeys): Boolean

    /** Sign arbitrary data with the local signing key. */
    suspend fun sign(data: ByteArray): ByteArray

    /** Verify a signature against a public signing key. */
    suspend fun verify(data: ByteArray, signature: ByteArray, publicSigningKey: ByteArray): Boolean
}

/** Verified public keys of a peer, as needed for session setup. */
data class PeerKeys(
    val userId: String,
    val publicIdentityKey: ByteArray,
    /** ML-KEM encapsulation key (post-quantum). Empty if the peer has none. */
    val pqcEncapsulationKey: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerKeys) return false
        return userId == other.userId && publicIdentityKey.contentEquals(other.publicIdentityKey)
    }

    override fun hashCode(): Int = userId.hashCode() * 31 + publicIdentityKey.contentHashCode()
}

data class SessionInfo(val pqcEstablished: Boolean)

/** A message whose key was already used: a replay or duplicate delivery. Safe to drop silently. */
class DuplicateMessageException(message: String) : SecurityException(message)

data class KeyPair(
    val publicSigningKey: ByteArray,
    val privateSigningKey: ByteArray,
    val publicIdentityKey: ByteArray,
    val privateIdentityKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyPair) return false
        return publicIdentityKey.contentEquals(other.publicIdentityKey)
    }

    override fun hashCode(): Int = publicIdentityKey.contentHashCode()
}

data class SessionKeys(
    val sendKey: ByteArray,
    val receiveKey: ByteArray,
    val sessionId: String,
    val pqcEstablished: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionKeys) return false
        return sessionId == other.sessionId
    }

    override fun hashCode(): Int = sessionId.hashCode()
}

data class EncryptedEnvelope(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val ephemeralPublicKey: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedEnvelope) return false
        return ciphertext.contentEquals(other.ciphertext) && nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int = ciphertext.contentHashCode()
}
