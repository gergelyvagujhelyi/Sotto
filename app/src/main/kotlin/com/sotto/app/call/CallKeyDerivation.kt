package com.sotto.app.call

import com.sotto.app.crypto.ClassicalKeyAgreement
import com.sotto.app.crypto.KeyManager
import com.sotto.app.crypto.PqcProvider
import com.sotto.app.crypto.VerifiedKeys
import com.sotto.app.domain.repository.SessionKeys

/**
 * The media keys of a call with one peer: X25519 between the identity keys, plus ML-KEM
 * when both sides publish it (the caller encapsulates, the callee decapsulates the
 * ciphertext from the offer), combined with KMACXOF256 into a send and a receive chain.
 * Apart from [CallManager] so it can be tested without a call.
 */
internal class CallKeyDerivation(
    private val keyManager: KeyManager,
    private val classicalKeyAgreement: ClassicalKeyAgreement,
    private val pqcProvider: PqcProvider,
) {
    /**
     * The keys; for a caller encapsulating, the KEM ciphertext to send with the offer; and
     * whether we're the initiator (the side with the smaller identity key).
     */
    class Result(val keys: SessionKeys, val outboundKemCiphertext: ByteArray?, val initiator: Boolean)

    /**
     * Keys for a call with [peerId], whose keys are [verified]. [inboundKem]: the KEM
     * ciphertext of the offer we got, if any. [incoming]: we are the callee.
     */
    fun derive(peerId: String, verified: VerifiedKeys, inboundKem: ByteArray?, incoming: Boolean): Result {
        val remotePubIdKey = verified.publicIdentityKey

        // Private keys are only needed for the X25519 agreement (wiped right after,
        // also if it throws).
        val (classicalSecret, isInitiator) = keyManager.withIdentityKeyPair { local ->
            classicalKeyAgreement.agree(
                privateKey = local.privateIdentityKey,
                remotePublicKey = remotePubIdKey,
            ) to
                // Deterministic initiator role: same comparison as RatchetSessionManager
                (local.publicIdentityKey.toCallHex() < remotePubIdKey.toCallHex())
        } ?: throw IllegalStateException("No local identity key pair")

        // PQC KEM handshake (initiator encapsulates, responder decapsulates). Whatever
        // happens from here on (a refused downgrade, a broken KEM key or ciphertext), the
        // secrets are wiped at the end: only the chain keys may outlive this.
        var pqcSecret = ByteArray(0)
        try {
            var outboundKemCiphertext: ByteArray? = null

            if (inboundKem != null) {
                // Responder: decapsulate the KEM ciphertext from the call_offer
                val decapsulationKey = keyManager.getPqcDecapsulationKey()
                    ?: throw IllegalStateException("Received KEM ciphertext but no local decapsulation key")
                try {
                    pqcSecret = pqcProvider.decapsulate(inboundKem, decapsulationKey)
                } finally {
                    decapsulationKey.fill(0)
                }
            } else if (incoming) {
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
                val encapsulation = pqcProvider.encapsulate(verified.pqcEncapsulationKey)
                pqcSecret = encapsulation.sharedSecret
                outboundKemCiphertext = encapsulation.ciphertext
            }

            val isPqcEstablished = pqcSecret.isNotEmpty()

            // Combine classical + PQC secrets via KMACXOF256 KDF (the inputs are wiped below)
            val ikm = if (isPqcEstablished) classicalSecret + pqcSecret else classicalSecret.copyOf()

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

            return Result(
                keys = SessionKeys(
                    sendKey = if (isInitiator) chainA else chainB,
                    receiveKey = if (isInitiator) chainB else chainA,
                    sessionId = "call-$peerId",
                    pqcEstablished = isPqcEstablished,
                ),
                outboundKemCiphertext = outboundKemCiphertext,
                initiator = isInitiator,
            )
        } finally {
            classicalSecret.fill(0)
            pqcSecret.fill(0)
        }
    }
}

/**
 * KMACXOF256-based KDF (NIST SP 800-185).
 *
 * KMACXOF256 provides built-in domain separation and input encoding,
 * eliminating the need for manual length-prefixing.
 *
 * Mapping: KMACXOF256(K=ikm, X=salt, S=info, L=length*8)
 *
 * Note: BC's [KMAC.doFinal(out, off, len)] uses the XOF variant (right_encode(0)),
 * which supports arbitrary output lengths.
 */
internal fun shake256Kdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val kmac = org.bouncycastle.crypto.macs.KMAC(256, info)
    kmac.init(org.bouncycastle.crypto.params.KeyParameter(ikm))
    kmac.update(salt, 0, salt.size)
    val output = ByteArray(length)
    kmac.doFinal(output, 0, length)
    return output
}

internal fun ByteArray.toCallHex(): String = joinToString("") { "%02x".format(it) }
