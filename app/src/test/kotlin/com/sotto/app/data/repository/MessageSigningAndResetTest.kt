package com.sotto.app.data.repository

import com.sotto.app.crypto.ContactKeyVerifier
import com.sotto.app.crypto.KeyManager
import com.sotto.app.crypto.PqcProvider
import com.sotto.app.crypto.SessionResetSender
import com.sotto.app.data.local.dao.BlockedUserDao
import com.sotto.app.data.local.dao.ContactDao
import com.sotto.app.data.local.dao.ConversationDao
import com.sotto.app.data.local.dao.MessageDao
import com.sotto.app.data.local.entity.ContactEntity
import com.sotto.app.data.local.entity.MessageEntity
import com.sotto.app.data.remote.ApiService
import com.sotto.app.data.remote.dto.AckRequest
import com.sotto.app.data.remote.dto.PendingMessageDto
import com.sotto.app.data.remote.dto.SendMessageRequest
import com.sotto.app.data.remote.dto.SendMessageResponse
import com.sotto.app.domain.model.MessageState
import com.sotto.app.domain.repository.CryptoEngine
import com.sotto.app.domain.repository.EncryptedEnvelope
import com.sotto.app.notification.ChatNotificationManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * MessageRepositoryImpl's handling of signatures, session resets and the pending queue,
 * with the relay, database and crypto mocked.
 */
class MessageSigningAndResetTest {
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val conversationDao = mockk<ConversationDao>(relaxed = true)
    private val contactDao = mockk<ContactDao>(relaxed = true)
    private val apiService = mockk<ApiService>(relaxed = true)
    private val cryptoEngine = mockk<CryptoEngine>(relaxed = true)
    private val keyManager = mockk<KeyManager>(relaxed = true)
    private val pqcProvider = mockk<PqcProvider>(relaxed = true)
    private val sessionResetSender = mockk<SessionResetSender>(relaxed = true)
    private val blockedUserDao = mockk<BlockedUserDao>(relaxed = true)
    private lateinit var repo: MessageRepositoryImpl

    private val pqcContact = ContactEntity(
        userId = "peer", displayName = "Astrid",
        publicIdentityKey = ByteArray(32) { 1 }, publicSigningKey = ByteArray(32) { 2 },
        pqcSigningKey = ByteArray(1952) { 3 },
    )
    private val classicalContact = pqcContact.copy(pqcSigningKey = ByteArray(0))

    private fun b64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)

    @Before
    fun setUp() {
        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.encodeToString(any(), any()) } answers { b64(firstArg()) }
        every { android.util.Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0

        every { keyManager.getUserId() } returns "me"
        every { keyManager.getPqcEncapsulationKey() } returns null
        every { keyManager.getLastControlTimestamp(any()) } returns 0L
        coEvery { blockedUserDao.isBlocked(any()) } returns false
        coEvery { messageDao.getById(any()) } returns null
        coEvery { cryptoEngine.verify(any(), any(), any()) } returns true
        every { pqcProvider.verify(any(), any(), any()) } returns true
        coEvery { cryptoEngine.sessionInfo(any()) } returns null

        repo = MessageRepositoryImpl(
            messageDao = messageDao,
            conversationDao = conversationDao,
            contactDao = contactDao,
            apiService = apiService,
            cryptoEngine = cryptoEngine,
            keyManager = keyManager,
            pqcProvider = pqcProvider,
            sessionResetSender = sessionResetSender,
            notificationManager = mockk<ChatNotificationManager>(relaxed = true),
            keyVerifier = mockk<ContactKeyVerifier>(relaxed = true),
            settingsRepository = javax.inject.Provider { throw IllegalStateException("no settings in this test") },
            blockedUserDao = blockedUserDao,
            contactNames = mockk<ContactNameStore>(relaxed = true),
        )
    }

    @After
    fun tearDown() = unmockkAll()

    private fun chatDto(id: String, pqcSignature: String = b64(ByteArray(8))) = PendingMessageDto(
        messageId = id, senderId = "peer",
        encryptedBody = b64(byteArrayOf(1, 2, 3)), nonce = b64("""{"dh":"x"}""".toByteArray()),
        signature = b64(ByteArray(64)), pqcSignature = pqcSignature, timestamp = 1_000L,
    )

    private fun controlDto(id: String, json: String, pqcSignature: String = "") = PendingMessageDto(
        messageId = id, senderId = "peer", encryptedBody = "", nonce = b64(json.toByteArray()),
        signature = b64(ByteArray(64)), pqcSignature = pqcSignature, timestamp = 1_000L,
    )

    private fun stubSendableSession() {
        every { keyManager.sign(any()) } returns ByteArray(64)
        coEvery { contactDao.getByUserId("peer") } returns pqcContact
        coEvery { cryptoEngine.hasSendableSession(any()) } returns true
        coEvery { cryptoEngine.encrypt(any(), any()) } returns EncryptedEnvelope(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
    }

    // ── ML-DSA signing ──────────────────────────────────────────────────────

    @Test
    fun `a message whose ML-DSA signing fails is not sent and shows as failed`() = runTest {
        stubSendableSession()
        every { keyManager.getMlDsaPrivateKey() } returns ByteArray(32) { 7 }
        every { pqcProvider.sign(any(), any()) } throws IllegalStateException("provider broke")

        try {
            repo.sendMessage("conv", "peer", "Hej Astrid", null)
            fail("expected the send to fail")
        } catch (e: IllegalStateException) {
            assertEquals("provider broke", e.message)
        }

        coVerify(exactly = 0) { apiService.sendMessage(any()) }
        coVerify { messageDao.updateState(any(), MessageState.FAILED.name) }
    }

    @Test
    fun `a failing Ed25519 signature fails the message too, instead of leaving it sending`() = runTest {
        stubSendableSession()
        every { keyManager.sign(any()) } throws IllegalStateException("No signing key available")

        runCatching { repo.sendMessage("conv", "peer", "Hej", null) }

        coVerify(exactly = 0) { apiService.sendMessage(any()) }
        coVerify { messageDao.updateState(any(), MessageState.FAILED.name) }
    }

    @Test
    fun `with ML-DSA keys a message carries both signatures, without them only Ed25519`() = runTest {
        stubSendableSession()
        val sent = mutableListOf<SendMessageRequest>()
        coEvery { apiService.sendMessage(capture(sent)) } returns SendMessageResponse("relay-1", 2_000L)
        every { pqcProvider.sign(any(), any()) } returns ByteArray(16) { 5 }

        every { keyManager.getMlDsaPrivateKey() } returns ByteArray(32) { 7 }
        assertEquals(MessageState.SENT, repo.sendMessage("conv", "peer", "Hej", null).state)
        every { keyManager.getMlDsaPrivateKey() } returns null
        assertEquals(MessageState.SENT, repo.sendMessage("conv", "peer", "Hej igen", null).state)

        assertEquals(b64(ByteArray(16) { 5 }), sent[0].pqcSignature)
        assertEquals("", sent[1].pqcSignature)
        assertTrue(sent.all { it.signature.isNotEmpty() })
    }

    @Test
    fun `a session reset whose ML-DSA signing fails is not sent`() = runTest {
        val sender = SessionResetSender(keyManager, pqcProvider, apiService)
        every { keyManager.getMlDsaPrivateKey() } returns ByteArray(32)
        every { pqcProvider.sign(any(), any()) } throws IllegalStateException("provider broke")
        every { keyManager.sign(any()) } returns ByteArray(64)

        runCatching { sender.send("peer") }.let { assertTrue(it.isFailure) }

        coVerify(exactly = 0) { apiService.sendMessage(any()) }
    }

    @Test
    fun `a message from a PQC contact without ML-DSA is held two syncs, then rejected, never decrypted`() = runTest {
        coEvery { contactDao.getByUserId("peer") } returns pqcContact
        coEvery { apiService.fetchPendingMessages() } returns listOf(chatDto("m1", pqcSignature = ""))

        repo.fetchPendingAndWait()
        repo.fetchPendingAndWait()
        coVerify(exactly = 0) { apiService.acknowledgeMessages(any()) }

        repo.fetchPendingAndWait()
        coVerify(exactly = 1) { apiService.acknowledgeMessages(AckRequest(listOf("m1"))) }
        coVerify(exactly = 1) { messageDao.insert(match<MessageEntity> { it.id == "m1" && it.state == MessageState.REJECTED.name }) }
        coVerify(exactly = 0) { cryptoEngine.decrypt(any(), any(), any()) }
    }

    @Test
    fun `a session reset from a PQC contact without ML-DSA is never applied`() = runTest {
        coEvery { contactDao.getByUserId("peer") } returns pqcContact
        val now = System.currentTimeMillis()
        coEvery { apiService.fetchPendingMessages() } returns
            listOf(controlDto("c1", """{"ctrl":"session_reset","ts":$now,"n":"ab"}"""))

        repeat(3) { repo.fetchPendingAndWait() }

        coVerify(exactly = 1) { apiService.acknowledgeMessages(AckRequest(listOf("c1"))) }
        coVerify(exactly = 0) { cryptoEngine.resetSession(any()) }
    }

    // ── Session resets after undecryptable messages ─────────────────────────

    /** Three syncs of an undecryptable [id]: the third gives up on it (and may send a reset). */
    private suspend fun failToDecrypt(id: String) {
        coEvery { cryptoEngine.decrypt(any(), any(), any()) } throws SecurityException("No session could decrypt the message")
        coEvery { apiService.fetchPendingMessages() } returns listOf(chatDto(id))
        repeat(3) { repo.fetchPendingAndWait() }
    }

    @Test
    fun `one broken session gets one session reset, however many messages fail`() = runTest {
        coEvery { contactDao.getByUserId("peer") } returns classicalContact

        failToDecrypt("m1")
        failToDecrypt("m2")

        coVerify(exactly = 1) { messageDao.insert(match<MessageEntity> { it.id == "m2" && it.state == MessageState.DECRYPT_FAILED.name }) }
        coVerify(exactly = 1) { sessionResetSender.send("peer", any(), any()) }
    }

    @Test
    fun `a session that breaks again after working gets another reset`() = runTest {
        coEvery { contactDao.getByUserId("peer") } returns classicalContact

        failToDecrypt("m1")
        coVerify(exactly = 1) { sessionResetSender.send("peer", any(), any()) }

        // The peer started a new session: their next message decrypts.
        coEvery { cryptoEngine.decrypt(any(), any(), any()) } returns "Hej".toByteArray()
        coEvery { apiService.fetchPendingMessages() } returns listOf(chatDto("ok"))
        repo.fetchPendingAndWait()

        failToDecrypt("m2")
        coVerify(exactly = 2) { sessionResetSender.send("peer", any(), any()) }
    }

    @Test
    fun `a reset that could not be sent is tried again on the next failure`() = runTest {
        coEvery { contactDao.getByUserId("peer") } returns classicalContact
        coEvery { sessionResetSender.send(any(), any(), any()) } throws IllegalStateException("relay down") andThen Unit

        failToDecrypt("m1")
        failToDecrypt("m2")

        coVerify(exactly = 2) { sessionResetSender.send("peer", any(), any()) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun sessionResetSentTo(): MutableMap<String, Long> =
        MessageRepositoryImpl::class.java.getDeclaredField("sessionResetSentTo")
            .apply { isAccessible = true }.get(repo) as MutableMap<String, Long>

    private fun prune() = MessageRepositoryImpl::class.java.getDeclaredMethod("pruneInMemoryMaps")
        .apply { isAccessible = true }.invoke(repo)

    @Test
    fun `past 200 peers only the oldest resets are forgotten`() {
        val sent = sessionResetSentTo()
        (1..200).forEach { sent["peer-$it"] = 1_000L + it }
        prune()
        assertEquals(200, sent.size)

        sent["peer-201"] = 5_000L
        sent["peer-202"] = 5_001L
        prune()
        assertEquals(200, sent.size)
        assertTrue("peer-1" !in sent && "peer-2" !in sent)
        assertTrue("peer-3" in sent && "peer-202" in sent)
    }

    // ── Interrupted sends ───────────────────────────────────────────────────

    @Test
    fun `interrupted sends are marked failed for the user to retry`() = runTest {
        val stuck = listOf("s1", "s2").map { id -> mockk<MessageEntity> { every { this@mockk.id } returns id } }
        coEvery { messageDao.getSendingMessages() } returns stuck

        repo.failInterruptedSends()

        coVerify { messageDao.updateState("s1", MessageState.FAILED.name) }
        coVerify { messageDao.updateState("s2", MessageState.FAILED.name) }
        coVerify(exactly = 0) { apiService.sendMessage(any()) }
    }

    // ── The pending queue ───────────────────────────────────────────────────

    @Test
    fun `full pages that make progress stop after 10 pages`() = runTest {
        coEvery { blockedUserDao.isBlocked("peer") } returns true // dropped and ACKed: progress every page
        var page = 0
        coEvery { apiService.fetchPendingMessages() } answers {
            page++
            (1..100).map { chatDto("p$page-$it") }
        }

        repo.fetchPendingAndWait()

        assertEquals(10, page)
        coVerify(exactly = 10) { apiService.acknowledgeMessages(any()) }
    }

    @Test
    fun `a full page without progress stops after one page`() = runTest {
        coEvery { contactDao.getByUserId("peer") } returns pqcContact
        var page = 0
        coEvery { apiService.fetchPendingMessages() } answers {
            page++
            (1..100).map { chatDto("q$it", pqcSignature = "") } // held, not ACKed
        }

        repo.fetchPendingAndWait()

        assertEquals(1, page)
    }

    // ── Control message freshness ───────────────────────────────────────────

    @Test
    fun `a signed session reset without a timestamp is dropped`() = runTest {
        coEvery { contactDao.getByUserId("peer") } returns classicalContact
        coEvery { apiService.fetchPendingMessages() } returns
            listOf(controlDto("c1", """{"ctrl":"session_reset","n":"ab"}"""))

        repo.fetchPendingAndWait()

        coVerify(exactly = 1) { apiService.acknowledgeMessages(AckRequest(listOf("c1"))) }
        coVerify(exactly = 0) { cryptoEngine.resetSession(any()) }
    }

    @Test
    fun `a signed session reset with a timestamp is applied, and the app always sends one`() = runTest {
        coEvery { contactDao.getByUserId("peer") } returns classicalContact
        val now = System.currentTimeMillis()
        coEvery { apiService.fetchPendingMessages() } returns
            listOf(controlDto("c2", """{"ctrl":"session_reset","ts":$now,"n":"ab"}"""))

        repo.fetchPendingAndWait()
        coVerify(exactly = 1) { cryptoEngine.resetSession("peer") }

        val sender = SessionResetSender(keyManager, pqcProvider, apiService)
        every { keyManager.getMlDsaPrivateKey() } returns null
        every { keyManager.sign(any()) } returns ByteArray(64)
        val sent = slot<SendMessageRequest>()
        coEvery { apiService.sendMessage(capture(sent)) } returns SendMessageResponse("r", 1L)
        sender.send("peer")
        val ctrl = String(java.util.Base64.getDecoder().decode(sent.captured.nonce))
        assertTrue(ctrl, Regex(""""ts":\d+""").containsMatchIn(ctrl))
    }
}
