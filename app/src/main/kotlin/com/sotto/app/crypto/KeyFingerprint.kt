package com.sotto.app.crypto

import java.security.MessageDigest
import java.util.Base64

/**
 * Fingerprint over ALL of a user's public keys.
 *
 *     SHA-256("ChatControll-fingerprint-v1" || LP(identity) || LP(signing) || LP(pqcEnc) || LP(pqcSign))
 *
 * The share code is the first 12 bytes of the fingerprint (base64url, no padding),
 * so a share code obtained out-of-band (QR, in person, another messenger) commits to
 * the exact key bundle. A client that resolves a share code MUST recompute this and
 * reject a bundle that does not match — otherwise the relay server could hand out
 * keys of its own choosing.
 *
 * The server computes the same value (server/app/routers/identity.py) so it can
 * index identities by share code; it is never trusted to do so correctly.
 */
object KeyFingerprint {
    private val DOMAIN = "ChatControll-fingerprint-v1".toByteArray(Charsets.UTF_8)
    private val ROTATION_DOMAIN = "ChatControll-key-rotation-v1".toByteArray(Charsets.UTF_8)
    private const val SHARE_CODE_BYTES = 12

    fun compute(
        publicIdentityKey: ByteArray,
        publicSigningKey: ByteArray,
        pqcEncapsulationKey: ByteArray,
        pqcSigningKey: ByteArray,
    ): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(DOMAIN)
        for (part in listOf(publicIdentityKey, publicSigningKey, pqcEncapsulationKey, pqcSigningKey)) {
            md.update(lengthPrefixed(part))
        }
        return md.digest()
    }

    fun shareCode(fingerprint: ByteArray): String {
        require(fingerprint.size >= SHARE_CODE_BYTES) { "Fingerprint too short" }
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(fingerprint.copyOfRange(0, SHARE_CODE_BYTES))
    }

    /**
     * Payload signed by the OLD keys when rotating, endorsing the new key set.
     * Peers holding the old keys verify this before accepting the new bundle.
     */
    fun rotationPayload(userId: String, previousFingerprint: ByteArray, newFingerprint: ByteArray): ByteArray {
        return ROTATION_DOMAIN +
            lengthPrefixed(userId.toByteArray(Charsets.UTF_8)) +
            lengthPrefixed(previousFingerprint) +
            lengthPrefixed(newFingerprint)
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
