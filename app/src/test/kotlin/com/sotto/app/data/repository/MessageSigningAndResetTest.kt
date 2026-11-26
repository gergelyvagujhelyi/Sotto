package com.sotto.app.data.repository

import android.content.Context
import com.sotto.app.R
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
import io.mockk.verify
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
    private val transfers = mockk<com.sotto.app.worker.FileTransfers>(relaxed = true)
    private val mediaStore = mockk<com.sotto.app.data.media.MediaStore>(relaxed = true)
    private val conversationDao = mockk<ConversationDao>(relaxed = true)
    private val contactDao = mockk<ContactDao>(relaxed = true)
    private val apiService = mockk<ApiService>(relaxed = true)
    private val cryptoEngine = mockk<CryptoEngine>(relaxed = true)
    private val keyManager = mockk<KeyManager>(relaxed = true)
    private val pqcProvider = mockk<PqcProvider>(relaxed = true)
    private val sessionResetSender = mockk<SessionResetSender>(relaxed = true)
    private val blockedUserDao = mockk<BlockedUserDao>(relaxed = true)
    private val resendScheduler = mockk<com.sotto.app.worker.ResendScheduler>(relaxed = true)
    private val backgroundOffers = mockk<com.sotto.app.connection.BackgroundOfferStore>(relaxed = true)
    private lateinit var repo: MessageRepositoryImpl

    private val pqcContact = ContactEntity(
        userId = "peer", displayName = "Astrid",
        publicIdentityKey = ByteArray(32) { 1 }, publicSigningKey = ByteArray(32) { 2 },
        pqcSigningKey = ByteArray(1952) { 3 },
    )
    private val classicalContact = pqcContact.copy(pqcSigningKey = ByteArray(0))

    private fun b64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)

    /** Group packets go here; these tests are about one-to-one messages. */
    private val groupRepo = mockk<GroupRepositoryImpl>(relaxed = true)

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
            mediaStore = mediaStore,
            fileDrop = mockk<com.sotto.app.data.media.FileDrop>(relaxed = true),
            transfers = transfers,
            context = mockk<Context>(relaxed = true) {
                // The note a message request gets instead of a photo: the note, then the caption.
                every { getString(R.string.chat_request_media_photo) } returns "📷 A photo was sent before you accepted"
                every { getString(R.string.chat_request_media_with_caption, *anyVararg()) } answers {
                    args.drop(1).flatMap { if (it is Array<*>) it.toList() else listOf(it) }.joinToString("\n\n")
                }
            },
            groups = javax.inject.Provider { groupRepo },
            resend = resendScheduler,
            backgroundOffers = backgroundOffers,
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

    // ── Automatic resend ────────────────────────────────────────────────────

    private fun failedText(id: String, to: String = "peer", writtenAt: Long = System.currentTimeMillis()) = MessageEntity(
        id = id, conversationId = "c", senderId = "me", recipientId = to,
        encryptedBody = ByteArray(0), nonce = ByteArray(0), plaintext = "Hej", state = MessageState.FAILED.name,
        timestamp = writtenAt, expiresAt = null, isOutgoing = true,
    )

    @Test
    fun `a message that fails to send is handed to the automatic resend`() = runTest {
        stubSendableSession()
        coEvery { apiService.sendMessage(any()) } throws java.io.IOException("timeout")

        runCatching { repo.sendMessage("conv", "peer", "Hej", null) }

        coVerify { messageDao.updateState(any(), MessageState.FAILED.name) }
        coVerify { resendScheduler.schedule() }
    }

    @Test
    fun `a message the automatic resend already took isn't sent again by a tap`() = runTest {
        stubSendableSession()
        every { keyManager.getUserId() } returns "me"
        coEvery { messageDao.getById("m1") } returns failedText("m1")
        coEvery { messageDao.claimState("m1", MessageState.FAILED.name, MessageState.SENDING.name) } returns 0

        repo.retryFailed("m1")

        coVerify(exactly = 0) { apiService.sendMessage(any()) }
    }

    @Test
    fun `the automatic resend sends fresh failed messages, with their own id, but not to someone blocked`() = runTest {
        stubSendableSession()
        every { keyManager.getUserId() } returns "me"
        val fresh = failedText("m2")
        val toBlocked = failedText("m3", to = "blocked")
        coEvery { messageDao.getFailedOutgoingSince(any()) } returnsMany listOf(listOf(fresh, toBlocked), listOf(toBlocked))
        coEvery { messageDao.getById("m2") } returns fresh
        coEvery { messageDao.claimState("m2", MessageState.FAILED.name, MessageState.SENDING.name) } returns 1
        coEvery { blockedUserDao.isBlocked("blocked") } returns true
        coEvery { apiService.sendMessage(any()) } returns mockk(relaxed = true)

        val waiting = repo.resendFailed()

        coVerify { apiService.sendMessage(match { it.clientMessageId == "m2" }) }
        coVerify(exactly = 0) { apiService.sendMessage(match { it.recipientId == "blocked" }) }
        // Only the blocked one is left, and it doesn't count as waiting.
        assertEquals(false, waiting)
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
        val stuck = listOf("s1", "s2").map { id ->
            mockk<MessageEntity> {
                every { this@mockk.id } returns id
                every { mediaKind } returns null
                every { groupId } returns null
            }
        }
        coEvery { messageDao.getSendingMessages() } returns stuck
        coEvery { messageDao.getDownloadingMessages() } returns emptyList()
        coEvery { messageDao.getSendingGroupMessages() } returns emptyList()

        repo.failInterruptedSends()

        coVerify { messageDao.updateState("s1", MessageState.FAILED.name) }
        coVerify { messageDao.updateState("s2", MessageState.FAILED.name) }
        coVerify(exactly = 0) { apiService.sendMessage(any()) }
    }

    @Test
    fun `a file send its background job still owns is left to it`() = runTest {
        val file = MessageEntity(
            id = "f1", conversationId = "c", senderId = "me", recipientId = "peer",
            encryptedBody = ByteArray(0), nonce = ByteArray(0), plaintext = "", state = MessageState.SENDING.name,
            timestamp = 1, expiresAt = null, isOutgoing = true, mediaKind = "file", mediaState = "uploading",
        )
        val orphan = file.copy(id = "f2")
        coEvery { messageDao.getSendingMessages() } returns listOf(file, orphan)
        coEvery { messageDao.getDownloadingMessages() } returns emptyList()
        coEvery { messageDao.getSendingGroupMessages() } returns emptyList()
        every { transfers.isQueued("f1", true) } returns true
        every { transfers.isQueued("f2", true) } returns false

        repo.failInterruptedSends()

        coVerify(exactly = 0) { messageDao.updateState("f1", any()) }
        coVerify { messageDao.updateState("f2", MessageState.FAILED.name) }
    }

    @Test
    fun `a delivered file the store refused shows as not sent and a tap retries only the file`() = runTest {
        val file = MessageEntity(
            id = "f3", conversationId = "c", senderId = "me", recipientId = "peer",
            encryptedBody = ByteArray(0), nonce = ByteArray(0), plaintext = "", state = MessageState.DELIVERED.name,
            timestamp = 1, expiresAt = null, isOutgoing = true, mediaKind = "file", mediaState = "uploading",
        )
        every { keyManager.getUserId() } returns "me"
        coEvery { messageDao.getById("f3") } returns file

        repo.markSendFailed("f3")

        // The message stays delivered (a resend would be dropped as a copy); the file is marked.
        coVerify(exactly = 0) { messageDao.updateState("f3", any()) }
        coVerify { messageDao.setMediaState("f3", "unsent") }

        coEvery { messageDao.getById("f3") } returns file.copy(mediaState = "unsent")
        repo.retryFile("f3")

        coVerify { messageDao.setMediaState("f3", "uploading") }
        coVerify { transfers.upload("f3") }
        coVerify(exactly = 0) { apiService.sendMessage(match { it.clientMessageId == "f3" }) }
    }

    @Test
    fun `a file whose message never went out isn't treated as only the file missing`() = runTest {
        val file = MessageEntity(
            id = "f5", conversationId = "c", senderId = "me", recipientId = "peer",
            encryptedBody = ByteArray(0), nonce = ByteArray(0), plaintext = "", state = MessageState.FAILED.name,
            timestamp = 1, expiresAt = null, isOutgoing = true, mediaKind = "file", mediaState = "uploading",
        )
        every { keyManager.getUserId() } returns "me"
        coEvery { messageDao.getById("f5") } returns file

        repo.markSendFailed("f5")

        coVerify(exactly = 0) { messageDao.setMediaState("f5", any()) }
        coVerify(exactly = 0) { apiService.sendMessage(any()) }
    }

    @Test
    fun `a contact's word that they have our file marks it delivered, and only that file`() = runTest {
        every { keyManager.getUserId() } returns "me"
        coEvery { contactDao.getByUserId("peer") } returns classicalContact
        coEvery { messageDao.getOutgoingFilesNotReceived("peer") } returns listOf("other", "f4")
        val theirId = ReceiptManager.relayMessageId("me", "f4")
        coEvery { cryptoEngine.decrypt(any(), any(), any()) } returns
            com.sotto.app.domain.model.FileUpdatePayload.encodeGot(theirId).toByteArray()
        coEvery { apiService.fetchPendingMessages() } returns listOf(chatDto("u1"))

        repo.fetchPendingAndWait()

        coVerify { messageDao.setMediaState("f4", "received") }
        coVerify(exactly = 0) { messageDao.setMediaState("other", any()) }
        coVerify(exactly = 0) { messageDao.insert(any()) }
    }

    // ── The pending queue ───────────────────────────────────────────────────

    @Test
    fun `a message that waited long on the relay is noticed as late, by the relay's clock`() = runTest {
        coEvery { blockedUserDao.isBlocked("peer") } returns true // dropped and ACKed, the simplest way through
        val arrived = 1_764_000_000_000L
        coEvery { apiService.fetchPendingMessages() } returnsMany listOf(
            listOf(chatDto("late").copy(timestamp = arrived), chatDto("later").copy(timestamp = arrived + 600_000)),
            emptyList(),
        )
        every { apiService.relayTimeOfLastFetch() } returns arrived + 25 * 60_000

        repo.fetchPendingAndWait()

        // The oldest one counts: 25 minutes.
        verify { backgroundOffers.noticedLate(25 * 60_000L, any()) }
    }

    @Test
    fun `a message fetched soon after it arrived, or without the relay's clock, isn't late`() = runTest {
        coEvery { blockedUserDao.isBlocked("peer") } returns true
        val arrived = 1_764_000_000_000L
        coEvery { apiService.fetchPendingMessages() } returnsMany listOf(listOf(chatDto("quick").copy(timestamp = arrived)), emptyList())
        every { apiService.relayTimeOfLastFetch() } returns arrived + 2_000
        repo.fetchPendingAndWait()

        coEvery { apiService.fetchPendingMessages() } returnsMany listOf(listOf(chatDto("unknown").copy(timestamp = arrived)), emptyList())
        every { apiService.relayTimeOfLastFetch() } returns null
        repo.fetchPendingAndWait()

        verify(exactly = 0) { backgroundOffers.noticedLate(any(), any()) }
    }

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

    // ── Message requests bring text only ────────────────────────────────────

    private fun conversation(approved: Boolean) = com.sotto.app.data.local.entity.ConversationEntity(
        id = "conv", contactId = "peer", contactDisplayName = "Peer", lastMessagePreview = null,
        lastMessageTimestamp = null, unreadCount = 0, isEncrypted = true, isApproved = approved,
    )

    private suspend fun receivePhoto(approved: Boolean) {
        coEvery { mediaStore.save(any(), any()) } returns ByteArray(32)
        coEvery { contactDao.getByUserId("peer") } returns classicalContact
        coEvery { conversationDao.getByContactId("peer") } returns conversation(approved)
        val photo = com.sotto.app.domain.model.MessageMedia(com.sotto.app.domain.model.MediaKind.PHOTO, "image/jpeg", width = 4, height = 3)
        coEvery { cryptoEngine.decrypt(any(), any(), any()) } returns
            com.sotto.app.domain.model.MediaPayload.encode(photo, byteArrayOf(9, 9, 9), "Titta").toByteArray()
        coEvery { apiService.fetchPendingMessages() } returns listOf(chatDto("p1"))
        repo.fetchPendingAndWait()
    }

    @Test
    fun `a photo in a message request is only noted, never stored`() = runTest {
        receivePhoto(approved = false)
        coVerify(exactly = 0) { mediaStore.save(any(), any()) }
        coVerify {
            messageDao.insert(match<MessageEntity> {
                it.id == "p1" && it.mediaKind == null && it.mediaKey == null &&
                    it.plaintext.startsWith("📷 A photo was sent before you accepted") && it.plaintext.endsWith("Titta")
            })
        }
    }

    @Test
    fun `once accepted, a photo arrives as a photo`() = runTest {
        receivePhoto(approved = true)
        coVerify { mediaStore.save("p1", byteArrayOf(9, 9, 9)) }
        coVerify { messageDao.insert(match<MessageEntity> { it.id == "p1" && it.mediaKind == "photo" && it.plaintext == "Titta" }) }
    }
}
