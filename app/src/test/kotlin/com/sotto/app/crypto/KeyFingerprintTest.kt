package com.sotto.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class KeyFingerprintTest {

    private val identity = ByteArray(44) { it.toByte() }
    private val signing = ByteArray(44) { (it + 100).toByte() }
    private val pqcEnc = ByteArray(1184) { (it * 7).toByte() }
    private val pqcSign = ByteArray(1952) { (it * 13).toByte() }

    /**
     * Shared test vector with the server (server/tests/test_identity.py
     * `test_share_code_matches_client_fingerprint_vector`). If this changes,
     * clients and server disagree on share codes.
     */
    @Test
    fun `fingerprint and share code match the cross-platform test vector`() {
        val fp = KeyFingerprint.compute(identity, signing, pqcEnc, pqcSign)
        assertEquals(EXPECTED_FINGERPRINT_HEX, KeyFingerprint.toHex(fp))
        assertEquals(EXPECTED_SHARE_CODE, KeyFingerprint.shareCode(fp))
        assertEquals(16, KeyFingerprint.shareCode(fp).length)
    }

    @Test
    fun `every key is covered by the fingerprint`() {
        val base = KeyFingerprint.compute(identity, signing, pqcEnc, pqcSign)
        fun flipped(b: ByteArray) = b.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNotEquals(base.toList(), KeyFingerprint.compute(flipped(identity), signing, pqcEnc, pqcSign).toList())
        assertNotEquals(base.toList(), KeyFingerprint.compute(identity, flipped(signing), pqcEnc, pqcSign).toList())
        assertNotEquals(base.toList(), KeyFingerprint.compute(identity, signing, flipped(pqcEnc), pqcSign).toList())
        assertNotEquals(base.toList(), KeyFingerprint.compute(identity, signing, pqcEnc, flipped(pqcSign)).toList())
        // Dropping the PQC keys changes the fingerprint too (no silent downgrade)
        assertNotEquals(base.toList(), KeyFingerprint.compute(identity, signing, ByteArray(0), ByteArray(0)).toList())
    }

    @Test
    fun `length prefixes prevent field-boundary ambiguity`() {
        val a = KeyFingerprint.compute(byteArrayOf(1, 2), byteArrayOf(3), ByteArray(0), ByteArray(0))
        val b = KeyFingerprint.compute(byteArrayOf(1), byteArrayOf(2, 3), ByteArray(0), ByteArray(0))
        assertNotEquals(a.toList(), b.toList())
    }

    companion object {
        const val EXPECTED_FINGERPRINT_HEX = "d6eb09fc8af328b0dbd7946a1439837861930c9351c29797f6c04e6fb600090e"
        const val EXPECTED_SHARE_CODE = "1usJ_IrzKLDb15Rq"
    }
}
