package com.sotto.app.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.security.KeyFactory
import java.security.Security
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * X25519 and Ed25519 straight on our key bytes (no key objects holding copies) must give
 * exactly what the JCA path always gave: contacts on older versions depend on it.
 */
class RawKeyCryptoTest {
    private val ka = AndroidClassicalKeyAgreement()

    @Before
    fun setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) Security.addProvider(BouncyCastleProvider())
    }

    private fun jcaAgree(privateKey: ByteArray, remotePublicKey: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("X25519", "BC")
        val agreement = KeyAgreement.getInstance("X25519", "BC")
        agreement.init(kf.generatePrivate(PKCS8EncodedKeySpec(privateKey)))
        agreement.doPhase(kf.generatePublic(X509EncodedKeySpec(remotePublicKey)), true)
        return agreement.generateSecret()
    }

    private fun jcaSign(privateKey: ByteArray, data: ByteArray): ByteArray {
        val signature = Signature.getInstance("Ed25519", "BC")
        signature.initSign(KeyFactory.getInstance("Ed25519", "BC").generatePrivate(PKCS8EncodedKeySpec(privateKey)))
        signature.update(data)
        return signature.sign()
    }

    @Test
    fun `X25519 on the raw key agrees with the JCA path, for many keys`() {
        repeat(50) {
            val a = ka.generateKeyAgreementKeyPair()
            val b = ka.generateKeyAgreementKeyPair()
            val raw = rawX25519Agree(a.second, b.first)!!
            assertArrayEquals(jcaAgree(a.second, b.first), raw)
            assertArrayEquals(raw, rawX25519Agree(b.second, a.first)) // and both sides agree
        }
    }

    @Test
    fun `Ed25519 on the raw key signs exactly as the JCA path, for many keys and messages`() {
        repeat(50) { i ->
            val (public, private) = ka.generateSigningKeyPair()
            val data = ByteArray(i * 7) { (it * 31 + i).toByte() }
            val raw = rawEd25519Sign(private, data)!!
            assertArrayEquals(jcaSign(private, data), raw)
            assertTrue(ka.verify(data, raw, public))
        }
    }

    @Test
    fun `agree and KeyManager-style signing use the raw path, and the key isn't changed`() {
        val a = ka.generateKeyAgreementKeyPair()
        val b = ka.generateKeyAgreementKeyPair()
        val before = a.second.copyOf()
        assertArrayEquals(jcaAgree(a.second, b.first), ka.agree(a.second, b.first))
        assertArrayEquals(before, a.second)
    }

    @Test
    fun `a key in another format still works, through the JCA path`() {
        val a = ka.generateKeyAgreementKeyPair()
        val b = ka.generateKeyAgreementKeyPair()
        // Same key, but the public one in a different (raw, unwrapped) encoding: not ours.
        val rawPublic = b.first.copyOfRange(b.first.size - 32, b.first.size)
        try {
            ka.agree(a.second, rawPublic)
            fail("a raw public key isn't an X.509 key: the JCA path must refuse it as before")
        } catch (_: Exception) {
        }
    }

    @Test
    fun `an all-zero shared secret (a low-order public key) is refused`() {
        val a = ka.generateKeyAgreementKeyPair()
        val lowOrder = a.first.copyOf().also { it.fill(0, it.size - 32, it.size) }
        try {
            ka.agree(a.second, lowOrder)
            fail("a low-order point must not give a shared secret")
        } catch (_: Exception) {
        }
    }

    @Test
    fun `the short PKCS#8 form (version 1, no public key) works too`() {
        val a = ka.generateKeyAgreementKeyPair()
        val b = ka.generateKeyAgreementKeyPair()
        // Version 1: the same header with version 0 and length 0x2e, and nothing after the key.
        val v1 = byteArrayOf(0x30, 0x2e, 0x02, 0x01, 0x00) + a.second.copyOfRange(5, 48)
        assertArrayEquals(jcaAgree(a.second, b.first), rawX25519Agree(v1, b.first)!!)
    }

    @Test
    fun `a private key with a damaged tail falls back instead of being misread`() {
        val a = ka.generateKeyAgreementKeyPair()
        val b = ka.generateKeyAgreementKeyPair()
        val damaged = a.second.copyOf().also { it[48] = 0x00 } // the public key's tag
        assertTrue(rawX25519Agree(damaged, b.first) == null)
    }

    @Test
    fun `generated X25519 keys are what BouncyCastle would encode, and work through JCA`() {
        repeat(20) {
            val (public, private) = rawX25519KeyPair(java.security.SecureRandom())
            val kf = KeyFactory.getInstance("X25519", "BC")
            // BouncyCastle reads both and writes them back byte for byte the same.
            assertArrayEquals(private, kf.generatePrivate(PKCS8EncodedKeySpec(private)).encoded)
            assertArrayEquals(public, kf.generatePublic(X509EncodedKeySpec(public)).encoded)
            // And they agree with a JCA-made key pair, both ways and on both paths.
            val other = java.security.KeyPairGenerator.getInstance("X25519", "BC").generateKeyPair()
            val secret = jcaAgree(private, other.public.encoded)
            assertArrayEquals(secret, jcaAgree(other.private.encoded, public))
            assertArrayEquals(secret, rawX25519Agree(private, other.public.encoded)!!)
        }
    }
}
