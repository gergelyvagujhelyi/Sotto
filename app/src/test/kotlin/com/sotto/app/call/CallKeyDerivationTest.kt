package com.sotto.app.call

import com.sotto.app.crypto.AndroidClassicalKeyAgreement
import com.sotto.app.crypto.BouncyCastlePqcProvider
import com.sotto.app.crypto.ClassicalKeyAgreement
import com.sotto.app.crypto.KeyManager
import com.sotto.app.crypto.VerifiedKeys
import com.sotto.app.domain.repository.KeyPair
import io.mockk.every
import io.mockk.mockk
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.security.Security

/** Call keys between two real sides (X25519 + ML-KEM-768), and what's left in memory after. */
class CallKeyDerivationTest {
    private val classical = AndroidClassicalKeyAgreement()
    private lateinit var pqc: BouncyCastlePqcProvider

    @Before
    fun setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)?.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
        pqc = BouncyCastlePqcProvider()
    }

    private inner class Side(val userId: String, withPqc: Boolean = true) {
        val identity: KeyPair
        val kemPublic: ByteArray
        var kemPrivate: ByteArray?
        /** Every identity key pair handed out, to check its private keys were wiped. */
        val handedOut = mutableListOf<KeyPair>()
        /** Every X25519 shared secret produced, to check it was wiped. */
        val secrets = mutableListOf<ByteArray>()
        private val keyManager = mockk<KeyManager>(relaxed = true)
        val derivation: CallKeyDerivation

        init {
            val sign = classical.generateSigningKeyPair()
            val ka = classical.generateKeyAgreementKeyPair()
            identity = KeyPair(sign.first, sign.second, ka.first, ka.second)
            if (withPqc) {
                val kem = pqc.generateKemKeyPair()
                kemPublic = kem.encapsulationKey
                kemPrivate = kem.decapsulationKey
            } else {
                kemPublic = ByteArray(0)
                kemPrivate = null
            }
            every { keyManager.loadIdentityKeyPair() } answers {
                KeyPair(
                    identity.publicSigningKey.copyOf(), identity.privateSigningKey.copyOf(),
                    identity.publicIdentityKey.copyOf(), identity.privateIdentityKey.copyOf(),
                ).also { handedOut += it }
            }
            every { keyManager.getPqcDecapsulationKey() } answers { kemPrivate?.copyOf() }
            every { keyManager.getPqcEncapsulationKey() } answers { kemPublic.takeIf { it.isNotEmpty() } }
            val recording = object : ClassicalKeyAgreement by classical {
                override fun agree(privateKey: ByteArray, remotePublicKey: ByteArray): ByteArray =
                    classical.agree(privateKey, remotePublicKey).also { secrets += it }
            }
            derivation = CallKeyDerivation(keyManager, recording, pqc)
        }

        fun verified(kem: ByteArray = kemPublic) =
            VerifiedKeys(userId, identity.publicIdentityKey, identity.publicSigningKey, kem, ByteArray(0))

        fun privateKeysWiped(): Boolean = handedOut.isNotEmpty() &&
            handedOut.all { it.privateIdentityKey.all { b -> b == 0.toByte() } && it.privateSigningKey.all { b -> b == 0.toByte() } }

        fun secretsWiped(): Boolean = secrets.isNotEmpty() && secrets.all { s -> s.all { it == 0.toByte() } }

        fun nothingSecretLeft(): Boolean = privateKeysWiped() && secretsWiped()
    }

    // ── The keys ───────────────────────────────────────────────────────────

    @Test
    fun `caller and callee get matching keys, with ML-KEM`() {
        val caller = Side("alice")
        val callee = Side("bob")
        val a = caller.derivation.derive("bob", callee.verified(), inboundKem = null, incoming = false)
        val ct = assertNotNull(a.outboundKemCiphertext).let { a.outboundKemCiphertext!! }
        val b = callee.derivation.derive("alice", caller.verified(), inboundKem = ct, incoming = true)

        assertArrayEquals(a.keys.sendKey, b.keys.receiveKey)
        assertArrayEquals(a.keys.receiveKey, b.keys.sendKey)
        assertFalse(a.keys.sendKey.contentEquals(a.keys.receiveKey))
        assertTrue(a.keys.pqcEstablished && b.keys.pqcEstablished)
        assertTrue(a.initiator != b.initiator)
    }

    @Test
    fun `caller and callee get matching keys without ML-KEM`() {
        val caller = Side("alice", withPqc = false)
        val callee = Side("bob", withPqc = false)
        val a = caller.derivation.derive("bob", callee.verified(), inboundKem = null, incoming = false)
        assertNull(a.outboundKemCiphertext)
        val b = callee.derivation.derive("alice", caller.verified(), inboundKem = null, incoming = true)

        assertArrayEquals(a.keys.sendKey, b.keys.receiveKey)
        assertFalse(a.keys.pqcEstablished || b.keys.pqcEstablished)
    }

    @Test
    fun `after a call's keys are made, no private key or shared secret is left`() {
        val caller = Side("alice")
        val callee = Side("bob")
        val a = caller.derivation.derive("bob", callee.verified(), inboundKem = null, incoming = false)
        callee.derivation.derive("alice", caller.verified(), inboundKem = a.outboundKemCiphertext, incoming = true)
        assertTrue(caller.nothingSecretLeft())
        assertTrue(callee.nothingSecretLeft())
    }

    // ── When making them fails ─────────────────────────────────────────────

    private fun failsLeavingNothing(side: Side, attempt: () -> Unit) {
        try {
            attempt()
            fail("expected the key setup to fail")
        } catch (_: Exception) {
        }
        assertTrue("private keys left in memory", side.privateKeysWiped())
        assertTrue("the X25519 shared secret left in memory", side.secretsWiped())
    }

    @Test
    fun `a downgraded offer (no ML-KEM ciphertext) is refused, leaving nothing`() {
        val caller = Side("alice")
        val callee = Side("bob")
        failsLeavingNothing(callee) {
            callee.derivation.derive("alice", caller.verified(), inboundKem = null, incoming = true)
        }
    }

    @Test
    fun `a garbled ML-KEM ciphertext fails, leaving nothing`() {
        val caller = Side("alice")
        val callee = Side("bob")
        failsLeavingNothing(callee) {
            callee.derivation.derive("alice", caller.verified(), inboundKem = ByteArray(10) { 1 }, incoming = true)
        }
    }

    @Test
    fun `a broken ML-KEM key from the peer fails, leaving nothing`() {
        val caller = Side("alice")
        val callee = Side("bob")
        failsLeavingNothing(caller) {
            caller.derivation.derive("bob", callee.verified(kem = ByteArray(10) { 1 }), inboundKem = null, incoming = false)
        }
    }

    @Test
    fun `a ciphertext without our ML-KEM key fails, leaving nothing`() {
        val caller = Side("alice")
        val callee = Side("bob")
        val ct = caller.derivation.derive("bob", callee.verified(), inboundKem = null, incoming = false).outboundKemCiphertext!!
        callee.kemPrivate = null
        failsLeavingNothing(callee) {
            callee.derivation.derive("alice", caller.verified(), inboundKem = ct, incoming = true)
        }
    }
}
