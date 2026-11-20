package com.sotto.app.crypto.ratchet

import com.sotto.app.crypto.AndroidClassicalKeyAgreement
import com.sotto.app.crypto.BouncyCastlePqcProvider
import com.sotto.app.crypto.KeyManager
import com.sotto.app.domain.repository.DuplicateMessageException
import com.sotto.app.domain.repository.EncryptedEnvelope
import com.sotto.app.domain.repository.KeyPair
import com.sotto.app.domain.repository.PeerKeys
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.security.Security
import java.util.Base64

/**
 * End-to-end tests of the production session engine: two independent peers,
 * real X25519 + ML-KEM-768, persistence through a fake key store.
 */
class RatchetSessionManagerTest {

    private val classical = AndroidClassicalKeyAgreement()
    private lateinit var pqc: BouncyCastlePqcProvider
    private val json = Json { ignoreUnknownKeys = true }

    private inner class Peer(val userId: String, withPqc: Boolean = true) {
        val identity: KeyPair
        val kemPublic: ByteArray
        private val kemPrivate: ByteArray?
        val storage = mutableMapOf<String, String>()
        val keyManager: KeyManager = mockk(relaxed = true)
        var engine: RatchetSessionManager

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
                )
            }
            every { keyManager.getPqcDecapsulationKey() } answers { kemPrivate?.copyOf() }
            every { keyManager.saveRatchetState(any(), any()) } answers { storage[firstArg()] = secondArg(); true }
            every { keyManager.loadRatchetState(any()) } answers { storage[firstArg()] }
            every { keyManager.removeRatchetState(any()) } answers { storage.remove(firstArg<String>()); Unit }
            every { keyManager.removeAllRatchetStates() } answers { storage.clear() }
            engine = RatchetSessionManager(classical, pqc, keyManager)
        }

        /** Keys other peers use to talk to this one (as if verified). */
        fun keys() = PeerKeys(userId, identity.publicIdentityKey, kemPublic)

        /** Simulate an app restart: new engine instance, same persisted storage. */
        fun restart() {
            engine = RatchetSessionManager(classical, pqc, keyManager)
        }
    }

    @Before
    fun setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)?.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
        pqc = BouncyCastlePqcProvider()
    }

    private suspend fun Peer.send(to: Peer, text: String): EncryptedEnvelope =
        engine.encrypt(to.keys(), text.toByteArray())

    private suspend fun Peer.receive(from: Peer, env: EncryptedEnvelope, requirePqc: Boolean = false): String =
        String(engine.decrypt(from.keys(), env, requirePqc))

    private fun header(env: EncryptedEnvelope): RatchetHeader =
        json.decodeFromString(String(env.nonce, Charsets.UTF_8))

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun `conversation round trips and the DH ratchet actually turns`() = runTest {
        val alice = Peer("alice")
        val bob = Peer("bob")

        val a1 = alice.send(bob, "hi bob")
        assertEquals("hi bob", bob.receive(alice, a1))
        val b1 = bob.send(alice, "hi alice")
        assertEquals("hi alice", alice.receive(bob, b1))
        val a2 = alice.send(bob, "how are you")
        assertEquals("how are you", bob.receive(alice, a2))
        val b2 = bob.send(alice, "fine")
        assertEquals("fine", alice.receive(bob, b2))

        // Ratchet keys are fresh ephemerals, never the long-term identity keys,
        // and they change every turn.
        val ratchetKeys = listOf(a1, b1, a2, b2).map { header(it).publicKey }
        assertFalse(ratchetKeys.contains(b64(alice.identity.publicIdentityKey)))
        assertFalse(ratchetKeys.contains(b64(bob.identity.publicIdentityKey)))
        assertEquals(4, ratchetKeys.toSet().size)

        assertTrue(alice.engine.sessionInfo("bob")!!.pqcEstablished)
        assertTrue(bob.engine.sessionInfo("alice")!!.pqcEstablished)
    }

    @Test
    fun `init data is repeated until the responder replies`() = runTest {
        val alice = Peer("alice")
        val bob = Peer("bob")

        val lost = alice.send(bob, "lost")          // never delivered
        val a2 = alice.send(bob, "second")
        assertNotNull(header(lost).init?.kemCiphertext)
        assertNotNull(header(a2).init?.kemCiphertext)

        // Bob can set up the session from the second message alone
        assertEquals("second", bob.receive(alice, a2))
        // ...and the late first message still decrypts
        assertEquals("lost", bob.receive(alice, lost))

        alice.receive(bob, bob.send(alice, "reply"))
        assertNull(header(alice.send(bob, "after reply")).init)
    }

    @Test
    fun `responder can be the first to reply without any prior state`() = runTest {
        val alice = Peer("alice")
        val bob = Peer("bob")
        // Bob initiates this time
        assertEquals("yo", alice.receive(bob, bob.send(alice, "yo")))
        assertEquals("sup", bob.receive(alice, alice.send(bob, "sup")))
    }

    @Test
    fun `replays are reported as duplicates and do not break the session`() = runTest {
        val alice = Peer("alice")
        val bob = Peer("bob")

        val a1 = alice.send(bob, "one")
        bob.receive(alice, a1)
        val b1 = bob.send(alice, "two")
        alice.receive(bob, b1)
        val a2 = alice.send(bob, "three")
        bob.receive(alice, a2)

        for ((receiver, sender, env) in listOf(Triple(bob, alice, a1), Triple(alice, bob, b1), Triple(bob, alice, a2))) {
            try {
                receiver.receive(sender, env)
                fail("replay accepted")
            } catch (_: DuplicateMessageException) {
            }
        }

        // Session still works in both directions
        assertEquals("four", alice.receive(bob, bob.send(alice, "four")))
        assertEquals("five", bob.receive(alice, alice.send(bob, "five")))
    }

    @Test
    fun `tampered message is rejected without destroying the session`() = runTest {
        val alice = Peer("alice")
        val bob = Peer("bob")
        bob.receive(alice, alice.send(bob, "setup"))

        val good = alice.send(bob, "real")
        val tampered = good.copy(ciphertext = good.ciphertext.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        try {
            bob.receive(alice, tampered)
            fail("tampered message accepted")
        } catch (e: DuplicateMessageException) {
            fail("tampered message must not be classified as duplicate")
        } catch (_: Exception) {
        }
        assertEquals("real", bob.receive(alice, good))
    }

    @Test
    fun `garbage header with a new ratchet key does not corrupt state`() = runTest {
        val alice = Peer("alice")
        val bob = Peer("bob")
        bob.receive(alice, alice.send(bob, "setup"))
        alice.receive(bob, bob.send(alice, "reply"))

        // Forged message claiming a new ratchet key (would trigger a DH step)
        val mallory = classical.generateKeyAgreementKeyPair()
        val forgedHeader = """{"publicKey":"${b64(mallory.first)}","previousChainLength":1,"messageNumber":0}"""
        val forged = EncryptedEnvelope(ciphertext = ByteArray(64), nonce = forgedHeader.toByteArray())
        runCatching { bob.receive(alice, forged) }.onSuccess { fail("forged message accepted") }

        assertEquals("still fine", bob.receive(alice, alice.send(bob, "still fine")))
    }

    @Test
    fun `simultaneous initiation converges on one session`() = runTest {
        val alice = Peer("alice")
        val bob = Peer("bob")

        // Both start a session before seeing the other's message
        val fromAlice = alice.send(bob, "hello from alice")
        val fromBob = bob.send(alice, "hello from bob")
        assertNotEquals(header(fromAlice).init!!.sessionTag, header(fromBob).init!!.sessionTag)

        assertEquals("hello from bob", alice.receive(bob, fromBob))
        assertEquals("hello from alice", bob.receive(alice, fromAlice))

        // Both now send on the same session and everything decrypts
        val a = alice.send(bob, "a")
        val b = bob.send(alice, "b")
        assertEquals("a", bob.receive(alice, a))
        assertEquals("b", alice.receive(bob, b))
        assertEquals("c", bob.receive(alice, alice.send(bob, "c")))
        assertEquals("d", alice.receive(bob, bob.send(alice, "d")))
    }

    @Test
    fun `state survives restart`() = runTest {
        val alice = Peer("alice")
        val bob = Peer("bob")
        bob.receive(alice, alice.send(bob, "1"))
        alice.receive(bob, bob.send(alice, "2"))

        alice.restart()
        bob.restart()

        assertEquals("3", bob.receive(alice, alice.send(bob, "3")))
        assertEquals("4", alice.receive(bob, bob.send(alice, "4")))
    }

    @Test
    fun `replay from a session that fell out of the history is a duplicate, not a desync`() = runTest {
        val alice = Peer("alice")
        val bob = Peer("bob")
        bob.receive(alice, alice.send(bob, "1"))
        val old = bob.send(alice, "old")
        assertEquals("old", alice.receive(bob, old))

        // Enough new sessions that the first one is dropped from Alice's history.
        repeat(6) { i ->
            alice.engine.resetSession("bob")
            bob.receive(alice, alice.send(bob, "a$i"))
            alice.receive(bob, bob.send(alice, "b$i"))
        }
        alice.restart()

        try {
            alice.receive(bob, old)
            fail("replay accepted")
        } catch (_: DuplicateMessageException) {
        }
        assertEquals("still fine", alice.receive(bob, bob.send(alice, "still fine")))
    }

    @Test
    fun `reset starts a fresh session while in-flight messages still decrypt`() = runTest {
        val alice = Peer("alice")
        val bob = Peer("bob")
        bob.receive(alice, alice.send(bob, "1"))
        alice.receive(bob, bob.send(alice, "2"))
        val oldTag = header(alice.send(bob, "3")).init?.sessionTag // null: confirmed

        val inFlightFromBob = bob.send(alice, "in flight")
        alice.engine.resetSession("bob")

        val fresh = alice.send(bob, "fresh")
        val freshTag = header(fresh).init?.sessionTag
        assertNotNull(freshTag)
        assertNotEquals(oldTag, freshTag)

        assertEquals("in flight", alice.receive(bob, inFlightFromBob))
        assertEquals("fresh", bob.receive(alice, fresh))
        assertEquals("ok", alice.receive(bob, bob.send(alice, "ok")))
    }

    @Test
    fun `classical-only session is refused when PQC is required`() = runTest {
        val alice = Peer("alice")
        val bob = Peer("bob")
        // Alice encrypts as if Bob had no ML-KEM key (downgrade)
        val downgraded = alice.engine.encrypt(PeerKeys("bob", bob.identity.publicIdentityKey), "x".toByteArray())
        assertNull(header(downgraded).init?.kemCiphertext)
        try {
            bob.receive(alice, downgraded, requirePqc = true)
            fail("downgraded session accepted")
        } catch (_: SecurityException) {
        }
    }

    @Test
    fun `two classical sessions with the same identities use different keys`() = runTest {
        val alice = Peer("alice", withPqc = false)
        val bob = Peer("bob", withPqc = false)
        val first = alice.send(bob, "same text")
        bob.receive(alice, first)

        alice.engine.deleteSession("bob")
        bob.engine.deleteSession("alice")

        val second = alice.send(bob, "same text")
        assertNotEquals(b64(first.ciphertext), b64(second.ciphertext))
        assertNotEquals(header(first).publicKey, header(second).publicKey)
        bob.receive(alice, second)
        // Replaying the old session's init after the session was deleted is still
        // recognised (seen tags are kept), not accepted as a new session.
        try {
            bob.receive(alice, first)
            fail("old-session message accepted in new session")
        } catch (_: DuplicateMessageException) {
        }
        assertEquals("still works", bob.receive(alice, alice.send(bob, "still works")))
    }

    @Test
    fun `peer identity change discards old sessions`() = runTest {
        val alice = Peer("alice")
        val bob = Peer("bob")
        bob.receive(alice, alice.send(bob, "1"))

        // Same user id, different identity key (e.g. verified rotation)
        val bob2 = Peer("bob")
        val env = alice.send(bob2, "to new keys")
        assertNotNull(header(env).init)
        assertEquals("to new keys", bob2.receive(alice, env))
    }

    @Test
    fun `failed persist on send drops sessions but keeps replay protection`() = runTest {
        val alice = Peer("alice")
        val bob = Peer("bob")

        val a1 = alice.send(bob, "first")
        assertEquals("first", bob.receive(alice, a1))

        // Bob's next save fails once: the send must fail, and the recovery save
        // must keep the session tags so the old init can't be replayed.
        var failNext = true
        every { bob.keyManager.saveRatchetState(any(), any()) } answers {
            if (failNext) { failNext = false; false } else { bob.storage[firstArg()] = secondArg(); true }
        }
        try { bob.send(alice, "lost"); fail("send must fail when the state can't be saved") } catch (_: IllegalStateException) {}
        assertFalse(bob.engine.hasSendableSession(alice.keys()))

        bob.restart()
        try { bob.receive(alice, a1); fail("replayed init must be rejected") } catch (_: DuplicateMessageException) {}

        // A new session still works afterwards.
        val b1 = bob.send(alice, "new session")
        assertEquals("new session", alice.receive(bob, b1))
    }

    @Test
    fun `hasSendableSession is bound to the identity key`() = runTest {
        val alice = Peer("alice")
        val bob = Peer("bob")
        val mallory = Peer("bob")

        assertFalse(alice.engine.hasSendableSession(bob.keys()))
        val a1 = alice.send(bob, "hi")
        assertTrue(alice.engine.hasSendableSession(bob.keys()))
        // Same user id, different identity key: would start a new session.
        assertFalse(alice.engine.hasSendableSession(mallory.keys()))

        bob.receive(alice, a1)
        assertTrue(bob.engine.hasSendableSession(alice.keys()))
    }
}
