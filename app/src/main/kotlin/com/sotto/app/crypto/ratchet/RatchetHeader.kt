package com.sotto.app.crypto.ratchet

import kotlinx.serialization.Serializable

/**
 * Header attached to each Double Ratchet message.
 *
 * Contains the sender's current ratchet public key and chain indices,
 * allowing the receiver to perform DH ratchet steps and derive the
 * correct message key. The serialized header is the AEAD associated data,
 * so every field (including [init]) is authenticated.
 */
@Serializable
data class RatchetHeader(
    /** Sender's current DH ratchet public key (X25519, X.509 encoded, base64). */
    val publicKey: String,
    /** Index in the previous sending chain (for skipped message handling). */
    val previousChainLength: Int,
    /** Index in the current sending chain. */
    val messageNumber: Int,
    /**
     * Session-setup data. Present on every message the session initiator sends
     * until the responder has replied, so the responder can build the session
     * even if earlier messages were lost.
     */
    val init: SessionInit? = null,
)

@Serializable
data class SessionInit(
    /** Random 128-bit identifier of this session (base64). */
    val sessionTag: String,
    /** ML-KEM-768 ciphertext encapsulated to the responder (base64), if hybrid PQ. */
    val kemCiphertext: String? = null,
)
