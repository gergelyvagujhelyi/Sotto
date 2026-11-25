package com.sotto.app.data.repository

import com.sotto.app.data.local.entity.MessageEntity
import com.sotto.app.data.remote.dto.SendMessageResponse

/**
 * The one-to-one channel group copies travel on (ADR-32), implemented by [MessageRepositoryImpl]
 * so they get every check a 1:1 message does: the per-peer lock, sessions bound to pinned keys,
 * the post-quantum downgrade latch, and the hybrid (Ed25519 + ML-DSA-65) envelope signature.
 */
interface GroupTransport {
    class Sealed(val ciphertext: ByteArray, val nonce: ByteArray, val signature: String, val pqcSignature: String)

    /**
     * Encrypts [plaintext] for [peerId] on their pairwise session and signs the envelope. Never
     * trusts keys on first use: [peerId] must have keys pinned (a contact, or a member vouched
     * for by a group's admin), or this throws.
     */
    suspend fun seal(peerId: String, plaintext: String): Sealed

    /** Hands a sealed copy to the relay under [clientMessageId]; a repeat is kept once (messages.py). */
    suspend fun post(peerId: String, sealed: Sealed, clientMessageId: String): SendMessageResponse

    /** Puts the file (or HD copy) of our group message [entity] in the relay's store once, for [recipients]. */
    suspend fun upload(entity: MessageEntity, recipients: List<String>): MessageEntity
}
