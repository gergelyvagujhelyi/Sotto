package com.sotto.app.group

import com.sotto.app.crypto.BouncyCastlePqcProvider
import com.sotto.app.crypto.KeyFingerprint
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.Base64

/** A test account with real keys: Ed25519, and ML-DSA-65 unless [postQuantum] is false. */
class TestAccount(val userId: String, postQuantum: Boolean = true) {
    private val edPrivate = Ed25519PrivateKeyParameters(SecureRandom())
    val edPublic: ByteArray = edPrivate.generatePublicKey().encoded
    private val mlDsa = if (postQuantum) pqc.generateSigningKeyPair() else null
    val mlDsaPublic: ByteArray = mlDsa?.publicKey ?: ByteArray(0)
    /** Stand-ins for the other two public keys, so fingerprints are like real ones. */
    val identityKey: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val kemKey: ByteArray = ByteArray(1184).also { SecureRandom().nextBytes(it) }

    val fingerprint: String = KeyFingerprint.toHex(KeyFingerprint.compute(identityKey, edPublic, kemKey, mlDsaPublic))
    val signingKeys = SigningKeys(edPublic, mlDsaPublic)

    /** Signs as this account, verifies anyone's (as [com.sotto.app.data.repository.AccountGroupSignatures] does). */
    val signatures: GroupSignatures = object : GroupSignatures {
        override fun sign(data: ByteArray): Pair<String, String> {
            val ed = Ed25519Signer().apply { init(true, edPrivate); update(data, 0, data.size) }.generateSignature()
            val pq = mlDsa?.let { pqc.sign(data, it.privateKey) }
            return b64(ed) to (pq?.let { b64(it) } ?: "")
        }

        override fun verify(data: ByteArray, signature: String, pqcSignature: String, keys: SigningKeys): Boolean =
            verifyWith(data, signature, pqcSignature, keys)
    }

    fun member(status: String = RosterMember.ACTIVE, trust: String = RosterMember.TRUST_IN_PERSON, name: String = userId) =
        RosterMember(userId, fingerprint, name, status, trust)

    companion object {
        val pqc = BouncyCastlePqcProvider()

        fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        fun verifyWith(data: ByteArray, signature: String, pqcSignature: String, keys: SigningKeys): Boolean {
            val ed = runCatching { Base64.getDecoder().decode(signature) }.getOrNull() ?: return false
            val ok = runCatching {
                Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(keys.ed25519, 0)); update(data, 0, data.size) }.verifySignature(ed)
            }.getOrDefault(false)
            if (!ok) return false
            if (keys.mlDsa.isEmpty()) return true
            if (pqcSignature.isEmpty()) return false
            val pq = runCatching { Base64.getDecoder().decode(pqcSignature) }.getOrNull() ?: return false
            return runCatching { pqc.verify(data, pq, keys.mlDsa) }.getOrDefault(false)
        }
    }
}
