package com.sotto.app.crypto

import java.util.Base64

/**
 * What one phone shows the other to verify a contact in person: its account and the full
 * 256-bit fingerprint over all its public keys ([KeyFingerprint.compute]).
 *
 * Over NFC it's sent as is (version 1): NFC reaches a few centimetres, so the code can only
 * come from the phone held against this one.
 *
 *     sotto-verify:1:<user id>:<fingerprint, base64url>
 *
 * A QR code could be a picture of one passed on, so the QR code is live, as BankID's is
 * (version 2, since 0.7.11): it changes every second, carrying the time it was made and an
 * Ed25519 signature over it by the account's own key. A scanner counts it as in person only
 * while it's fresh ([InPersonVerifier]); an old screenshot still adds the person, unverified.
 *
 *     sotto-verify:2:<user id>:<fingerprint>:<unix seconds>:<Ed25519 signature, base64url>
 */
data class InPersonCode(
    val userId: String,
    val fingerprint: ByteArray,
    /** When a live (QR) code was made, in Unix seconds; null for NFC's. */
    val issuedAt: Long? = null,
    /** Ed25519 over [livePayload] by the account's signing key; null for NFC's. */
    val signature: ByteArray? = null,
) {

    val fingerprintHex: String get() = KeyFingerprint.toHex(fingerprint)

    val isLive: Boolean get() = issuedAt != null && signature != null

    fun encode(): String {
        val fp = B64.encodeToString(fingerprint)
        val at = issuedAt
        val sig = signature
        return if (at != null && sig != null) {
            "${PREFIX_LIVE}$userId:$fp:$at:${B64.encodeToString(sig)}"
        } else {
            "${PREFIX}$userId:$fp"
        }
    }

    fun toBytes(): ByteArray = encode().toByteArray(Charsets.UTF_8)

    /** What a live code's signature covers: the account, its fingerprint and the time. */
    fun livePayload(): ByteArray = livePayload(userId, fingerprint, issuedAt ?: 0L)

    override fun equals(other: Any?): Boolean {
        if (other !is InPersonCode) return false
        val sig = signature
        val otherSig = other.signature
        val sameSignature = if (sig == null || otherSig == null) sig == otherSig else sig.contentEquals(otherSig)
        return userId == other.userId && fingerprint.contentEquals(other.fingerprint) &&
            issuedAt == other.issuedAt && sameSignature
    }

    override fun hashCode(): Int =
        ((31 * userId.hashCode() + fingerprint.contentHashCode()) * 31 + (issuedAt?.hashCode() ?: 0)) * 31 +
            (signature?.contentHashCode() ?: 0)

    companion object {
        private const val PREFIX = "sotto-verify:1:"
        private const val PREFIX_LIVE = "sotto-verify:2:"
        private const val FINGERPRINT_BYTES = 32
        private const val SIGNATURE_BYTES = 64
        private val USER_ID = Regex("[a-f0-9]{16}")
        private val B64 = Base64.getUrlEncoder().withoutPadding()
        private val LIVE_DOMAIN = "Sotto-live-code-v1".toByteArray(Charsets.UTF_8)

        fun livePayload(userId: String, fingerprint: ByteArray, issuedAt: Long): ByteArray {
            val id = userId.toByteArray(Charsets.UTF_8)
            val time = ByteArray(8) { i -> (issuedAt ushr (56 - 8 * i)).toByte() }
            return LIVE_DOMAIN + lp(id) + lp(fingerprint) + time
        }

        private fun lp(data: ByteArray): ByteArray {
            val n = data.size
            return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + data
        }

        /** The code [text] holds, or null when it holds something else. */
        fun parse(text: String): InPersonCode? {
            val t = text.trim()
            val live = t.startsWith(PREFIX_LIVE)
            val body = when {
                live -> t.removePrefix(PREFIX_LIVE)
                t.startsWith(PREFIX) -> t.removePrefix(PREFIX)
                else -> return null
            }
            val parts = body.split(':')
            if (parts.size != (if (live) 4 else 2) || !USER_ID.matches(parts[0])) return null
            val fingerprint = decode(parts[1])?.takeIf { it.size == FINGERPRINT_BYTES } ?: return null
            if (!live) return InPersonCode(parts[0], fingerprint)
            val at = parts[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val sig = decode(parts[3])?.takeIf { it.size == SIGNATURE_BYTES } ?: return null
            return InPersonCode(parts[0], fingerprint, at, sig)
        }

        fun parse(bytes: ByteArray): InPersonCode? = parse(String(bytes, Charsets.UTF_8))

        private fun decode(text: String): ByteArray? = try {
            Base64.getUrlDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
