package com.sotto.app.crypto

import java.util.Base64

/**
 * What one phone shows the other to verify a contact in person: its account and the full
 * 256-bit fingerprint over all its public keys ([KeyFingerprint.compute]).
 *
 * Unlike a share code (the first 96 bits, which may have travelled through another app), this
 * is read straight off the other person's screen or phone, by QR code or NFC, so it can only
 * have come from the phone in front of you.
 *
 *     sotto-verify:1:<user id>:<fingerprint, base64url without padding>
 *
 * The QR code holds that text; over NFC it's sent as its UTF-8 bytes.
 */
data class InPersonCode(val userId: String, val fingerprint: ByteArray) {

    val fingerprintHex: String get() = KeyFingerprint.toHex(fingerprint)

    fun encode(): String =
        "$PREFIX$userId:" + Base64.getUrlEncoder().withoutPadding().encodeToString(fingerprint)

    fun toBytes(): ByteArray = encode().toByteArray(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean =
        other is InPersonCode && userId == other.userId && fingerprint.contentEquals(other.fingerprint)

    override fun hashCode(): Int = 31 * userId.hashCode() + fingerprint.contentHashCode()

    companion object {
        private const val PREFIX = "sotto-verify:1:"
        private const val FINGERPRINT_BYTES = 32
        private val USER_ID = Regex("[a-f0-9]{16}")

        /** The code [text] holds, or null when it holds something else. */
        fun parse(text: String): InPersonCode? {
            val body = text.trim().takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
            val parts = body.split(':')
            if (parts.size != 2 || !USER_ID.matches(parts[0])) return null
            val fingerprint = try {
                Base64.getUrlDecoder().decode(parts[1])
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (fingerprint.size != FINGERPRINT_BYTES) return null
            return InPersonCode(parts[0], fingerprint)
        }

        fun parse(bytes: ByteArray): InPersonCode? = parse(String(bytes, Charsets.UTF_8))
    }
}
