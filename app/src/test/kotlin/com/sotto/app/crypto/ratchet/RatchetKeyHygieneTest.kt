package com.sotto.app.crypto.ratchet

import com.sotto.app.crypto.AndroidClassicalKeyAgreement
import com.sotto.app.crypto.BouncyCastlePqcProvider
import com.sotto.app.crypto.KeyManager
import com.sotto.app.domain.repository.KeyPair
import com.sotto.app.domain.repository.PeerKeys
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.Security

/**
 * The session engine wipes our private keys after use (also when it fails), drops
 * sessions bound to an old identity key, and reports why every session failed.
 */
class RatchetKeyHygieneTest {

    private val classical = AndroidClassicalKeyAgreement()
    private lateinit var pqc: BouncyCastlePqcProvider

    private inner class Peer(val userId: String) {
        val identity: KeyPair
        val kemPublic: ByteArray
        private val kemPrivate: ByteArray
        val storage = mutableMapOf<String, String>()
        /** Every key pair the engine was handed, to check it wiped them. */
        val handedOut = mutableListOf<KeyPair>()
        val keyManager: KeyManager = mockk(relaxed = true)
        val engine: RatchetSessionManager

        init {
            val sign = classical.generateSigningKeyPair()
            val ka = classical.generateKeyAgreementKeyPair()
            identity = KeyPair(sign.first, sign.second, ka.first, ka.second)
            val kem = pqc.generateKemKeyPair()
            kemPublic = kem.encapsulationKey
            kemPrivate = kem.decapsulationKey
            every { keyManager.loadIdentityKeyPair() } answers {
                KeyPair(
                    identity.publicSigningKey.copyOf(), identity.privateSigningKey.copyOf(),
                    identity.publicIdentityKey.copyOf(), identity.privateIdentityKey.copyOf(),
                ).also { handedOut += it }
            }
            every { keyManager.getPqcDecapsulationKey() } answers { kemPrivate.copyOf() }
            every { keyManager.saveRatchetState(any(), any()) } answers { storage[firstArg()] = secondArg(); true }
            every { keyManager.loadRatchetState(any()) } answers { storage[firstArg()] }
            every { keyManager.removeRatchetState(any()) } answers { storage.remove(firstArg<String>()); Unit }
            engine = RatchetSessionManager(classical, pqc, keyManager)
        }

        fun keys() = PeerKeys(userId, identity.publicIdentityKey, kemPublic)
    }

    @Before
    fun setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)?.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
        pqc = BouncyCastlePqcProvider()
    }

    private suspend fun Peer.send(to: Peer, text: String) = engine.encrypt(to.keys(), text.toByteArray())
    private suspend fun Peer.receive(from: Peer, env: com.sotto.app.domain.repository.EncryptedEnvelope) = String(engine.decrypt(from.keys(), env))

    @Test
    fun `the session engine wipes every private key it loads`() = runTest {
        val alice = Peer("alice")
        val bob = Peer("bob")
        bob.receive(alice, alice.send(bob, "hej"))     // initiator + responder paths
        alice.receive(bob, bob.send(alice, "hej hej"))
        alice.engine.ensureSession(bob.keys())          // tie-break path

        val all = alice.handedOut + bob.handedOut
        assertTrue("engine should have loaded keys", all.size >= 2)
        all.forEach {
            assertTrue(it.privateIdentityKey.all { b -> b == 0.toByte() })
            assertTrue(it.privateSigningKey.all { b -> b == 0.toByte() })
        }
    }

    @Test
    fun `a session is not sendable for a peer whose identity key changed`() = runTest {
        val alice = Peer("alice")
        val bob = Peer("bob")
        bob.receive(alice, alice.send(bob, "hej"))
        alice.receive(bob, bob.send(alice, "hej hej"))

        assertTrue(alice.engine.hasSendableSession(bob.keys()))
        val rotated = PeerKeys("bob", classical.generateKeyAgreementKeyPair().first, bob.kemPublic)
        assertFalse(alice.engine.hasSendableSession(rotated))
    }

    private fun KeyPair.wiped() =
        privateIdentityKey.all { it == 0.toByte() } && privateSigningKey.all { it == 0.toByte() }

    @Test
    fun `keys are wiped even when setting up a session fails`() = runTest {
        val alice = Peer("alice")
        val bob = Peer("bob")
        // A broken ML-KEM key from the server: encapsulation throws mid-setup.
        val broken = PeerKeys("bob", bob.identity.publicIdentityKey, ByteArray(10) { 1 })

        try {
            alice.engine.encrypt(broken, "hej".toByteArray())
            fail("a broken ML-KEM key must not give a session")
        } catch (_: Exception) {
        }

        assertTrue("engine should have loaded keys", alice.handedOut.isNotEmpty())
        alice.handedOut.forEach { assertTrue("private keys left in memory", it.wiped()) }
    }

    @Test
    fun `when every session fails, every session's error is kept`() = runTest {
        val alice = Peer("alice")
        val bob = Peer("bob")
        bob.receive(alice, alice.send(bob, "1"))
        alice.receive(bob, bob.send(alice, "2"))
        val heldBack = alice.send(bob, "3")              // old session, delivered late
        alice.engine.resetSession("bob")
        bob.receive(alice, alice.send(bob, "4"))         // new session: Bob now holds two

        val tampered = com.sotto.app.domain.repository.EncryptedEnvelope(
            heldBack.ciphertext.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() },
            heldBack.nonce,
        )
        try {
            bob.receive(alice, tampered)
            fail("tampered message must not decrypt")
        } catch (e: SecurityException) {
            assertNotNull(e.cause)
            assertEquals("one error per session tried", 1, e.suppressed.size)
        }
        assertEquals("3", bob.receive(alice, heldBack)) // both sessions were there to try
    }

    @Test
    fun `nothing outside KeyManager loads our private keys without the helper that wipes them`() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("source tree not found from ${File(".").absolutePath}", sources.isNotEmpty())
        val offenders = sources
            .filter { it.name != "KeyManager.kt" }
            .flatMap { file -> file.readLines().mapIndexedNotNull { i, line ->
                if ("loadIdentityKeyPair(" in line) "${file.name}:${i + 1}" else null
            } }
        assertEquals("use keyManager.withIdentityKeyPair { } instead", emptyList<String>(), offenders)
    }
}
