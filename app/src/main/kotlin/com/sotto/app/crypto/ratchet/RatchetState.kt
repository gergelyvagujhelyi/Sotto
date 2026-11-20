package com.sotto.app.crypto.ratchet

/**
 * The mutable state of a Double Ratchet session between two peers.
 *
 * This state evolves with each sent/received message:
 * - Sending a message advances the sending chain (symmetric ratchet).
 * - Receiving a message with a new DH ratchet key triggers a DH ratchet step,
 *   creating new sending and receiving chains (providing forward secrecy).
 *
 * Security note: This state contains sensitive key material.
 * It must be stored encrypted and cleared from memory when no longer needed.
 */
data class RatchetState(
    /** Our current DH ratchet keypair (public + private, X25519). */
    var dhKeyPair: DhKeyPair,
    /** The remote party's current DH ratchet public key. Null before first message received. */
    var remoteDhPublicKey: ByteArray?,
    /** Root key — used to derive new chain keys during DH ratchet steps. */
    var rootKey: ByteArray,
    /** Current sending chain key. */
    var sendingChainKey: ChainKey?,
    /** Current receiving chain key. */
    var receivingChainKey: ChainKey?,
    /** Number of messages sent in the previous sending chain (for header). */
    var previousSendingChainLength: Int = 0,
    /** Skipped message keys we haven't used yet (for out-of-order delivery).
     *  Key: (ratchetPublicKey hex, messageNumber) → message key bytes. */
    val skippedMessageKeys: MutableMap<Pair<String, Int>, ByteArray> = mutableMapOf(),
    /** Timestamps (epoch millis) for each skipped key, for expiration. */
    val skippedKeyTimestamps: MutableMap<Pair<String, Int>, Long> = mutableMapOf(),
    /** Remote ratchet keys (hex) of chains we have already moved past. A message
     *  on one of these chains without a skipped key is a replay / duplicate. */
    val previousRemoteKeys: MutableList<String> = mutableListOf(),
    /** KEM ciphertext the initiator keeps attaching until the session is confirmed. */
    var pendingKemCiphertext: ByteArray? = null,
    /** Whether PQC secret has been mixed into this session's root key. */
    var pqcEstablished: Boolean = false,
    /** Random identifier of this session (base64), chosen by the initiator. */
    var sessionTag: String = "",
    /** True if we created this session (we are "Alice"). */
    var isInitiator: Boolean = false,
    /** Initiator only: true once the responder has replied. Until then every
     *  outgoing message carries the [SessionInit] data. */
    var confirmed: Boolean = false,
) {
    /** Independent copy (all key material duplicated) for transactional decryption. */
    fun deepCopy(): RatchetState = RatchetState(
        dhKeyPair = DhKeyPair(dhKeyPair.publicKey.copyOf(), dhKeyPair.privateKey.copyOf()),
        remoteDhPublicKey = remoteDhPublicKey?.copyOf(),
        rootKey = rootKey.copyOf(),
        sendingChainKey = sendingChainKey?.let { ChainKey(it.key.copyOf(), it.index) },
        receivingChainKey = receivingChainKey?.let { ChainKey(it.key.copyOf(), it.index) },
        previousSendingChainLength = previousSendingChainLength,
        skippedMessageKeys = skippedMessageKeys.mapValuesTo(mutableMapOf()) { it.value.copyOf() },
        skippedKeyTimestamps = skippedKeyTimestamps.toMutableMap(),
        previousRemoteKeys = previousRemoteKeys.toMutableList(),
        pendingKemCiphertext = pendingKemCiphertext?.copyOf(),
        pqcEstablished = pqcEstablished,
        sessionTag = sessionTag,
        isInitiator = isInitiator,
        confirmed = confirmed,
    )

    /** Best-effort zeroization of all secret material in this state. */
    fun wipe() {
        dhKeyPair.privateKey.fill(0)
        rootKey.fill(0)
        sendingChainKey?.key?.fill(0)
        receivingChainKey?.key?.fill(0)
        skippedMessageKeys.values.forEach { it.fill(0) }
    }

    companion object {
        /** Maximum number of skipped message keys to store per chain.
         *  Limits memory from a malicious peer claiming high message numbers,
         *  while tolerating legitimate reorder storms on flaky networks. */
        const val MAX_SKIP = 256
        /** Skipped message keys older than this are purged (7 days). */
        const val SKIPPED_KEY_TTL_MS = 7L * 24 * 60 * 60 * 1000
        /** How many past remote ratchet keys to remember for duplicate detection. */
        const val MAX_PREVIOUS_REMOTE_KEYS = 32
    }
}

data class DhKeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DhKeyPair) return false
        return publicKey.contentEquals(other.publicKey) &&
            privateKey.contentEquals(other.privateKey)
    }

    override fun hashCode(): Int = publicKey.contentHashCode() * 31 + privateKey.contentHashCode()
}
