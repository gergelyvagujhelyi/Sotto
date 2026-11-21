package com.sotto.app.data.repository

import android.util.Base64
import com.sotto.app.crypto.ContactKeyVerifier
import com.sotto.app.crypto.KeyCheck
import com.sotto.app.crypto.KeyManager
import com.sotto.app.crypto.VerifiedKeys
import com.sotto.app.crypto.PqcProvider
import com.sotto.app.crypto.SessionResetSender
import com.sotto.app.crypto.buildMessageSigPayload
import com.sotto.app.data.local.dao.ContactDao
import com.sotto.app.data.local.dao.ConversationDao
import com.sotto.app.data.local.dao.MessageDao
import com.sotto.app.data.local.entity.MessageEntity
import com.sotto.app.data.remote.ApiService
import com.sotto.app.data.remote.dto.AckRequest
import com.sotto.app.data.remote.dto.SendMessageRequest
import com.sotto.app.domain.model.Message
import com.sotto.app.domain.model.MessageState
import com.sotto.app.domain.model.ProfilePayload
import com.sotto.app.domain.model.ReactionPayload
import com.sotto.app.domain.model.ReplyPayload
import com.sotto.app.domain.model.ReplyRef
import com.sotto.app.domain.repository.CryptoEngine
import com.sotto.app.domain.repository.DuplicateMessageException
import com.sotto.app.domain.repository.EncryptedEnvelope
import com.sotto.app.domain.repository.MessageRepository
import com.sotto.app.domain.repository.PeerKeys
import com.sotto.app.notification.ChatNotificationManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val apiService: ApiService,
    private val cryptoEngine: CryptoEngine,
    private val keyManager: KeyManager,
    private val pqcProvider: PqcProvider,
    private val sessionResetSender: SessionResetSender,
    private val notificationManager: ChatNotificationManager,
    private val keyVerifier: ContactKeyVerifier,
    private val settingsRepository: javax.inject.Provider<com.sotto.app.domain.repository.SettingsRepository>,
    private val blockedUserDao: com.sotto.app.data.local.dao.BlockedUserDao,
    private val contactNames: ContactNameStore,
) : MessageRepository {

    private val peerLocks = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    /** One reaction send at a time, so quick changes reach the contact in the order made. */
    private val reactionLock = kotlinx.coroutines.sync.Mutex()
    private val headerJson = Json { ignoreUnknownKeys = true }

    /** Track consecutive decrypt failures per message to avoid infinite retry. */
    private val decryptFailCounts = ConcurrentHashMap<String, Int>()

    /** Track skipped syncs for messages missing a PQC signature (may be transient). */
    private val pqcSigMissCounts = ConcurrentHashMap<String, Int>()

    /** Track skipped syncs for control messages missing a PQC signature. */
    private val ctrlPqcSigMissCounts = ConcurrentHashMap<String, Int>()

    /** Track peers already notified with session_reset to avoid duplicate signals. */
    private val sessionResetSentTo: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The conversation currently open on screen — skip unread increment for it. */
    @Volatile
    private var activeConversationId: String? = null

    override fun setActiveConversation(conversationId: String?) {
        activeConversationId = conversationId
    }

    override fun clearActiveConversation(conversationId: String) {
        // Only clear if it's still ours: another chat screen may have taken over.
        if (activeConversationId == conversationId) activeConversationId = null
    }

    /** Expiry for a new user message under the disappearing-messages setting, or null if off. */
    private suspend fun disappearingExpiry(): Long? {
        val duration = try {
            settingsRepository.get().getPrivacySettings().first().disappearingMessagesDefault
        } catch (_: Exception) {
            return null
        }
        return duration.millis?.let { System.currentTimeMillis() + it }
    }

    override suspend fun deleteExpiredMessages() {
        val now = System.currentTimeMillis()
        var deleted = messageDao.deleteExpired(now)
        // Message-history retention (Settings → Message history).
        val retentionDays = try {
            settingsRepository.get().getPrivacySettings().first().localDataRetentionDays
        } catch (e: Exception) {
            0 // settings unreadable: skip retention, but still clear expired previews below
        }
        if (retentionDays > 0) {
            deleted += messageDao.deleteAllOlderThan(now - retentionDays * DAY_MS)
        }
        if (deleted > 0) {
            // Don't keep the plaintext of a deleted message in the conversation list.
            conversationDao.clearPreviewsWithoutMessage()
        }
    }

    /**
     * Evict stale tracking entries when maps grow beyond [PRUNE_THRESHOLD]
     * to prevent unbounded memory growth on long-lived installs.
     *
     * peerLocks is intentionally not pruned: its size is bounded by the
     * number of contacts, and removing an "unlocked" mutex races with
     * threads about to acquire it.
     */
    private fun pruneInMemoryMaps() {
        if (decryptFailCounts.size > PRUNE_THRESHOLD) {
            // Evict entries with the lowest retry counts first to ensure that
            // messages nearing the MAX_DECRYPT_RETRIES limit are correctly tombstoned.
            // Snapshot keys to avoid ConcurrentModificationException.
            val evictCount = decryptFailCounts.size - PRUNE_THRESHOLD
            val keysToEvict = decryptFailCounts.entries.toList()
                .sortedBy { it.value }
                .take(evictCount)
                .map { it.key }
            keysToEvict.forEach { decryptFailCounts.remove(it) }
        }
        if (pqcSigMissCounts.size > PRUNE_THRESHOLD) {
            val evictCount = pqcSigMissCounts.size - PRUNE_THRESHOLD
            val keysToEvict = pqcSigMissCounts.entries.toList()
                .sortedBy { it.value }
                .take(evictCount)
                .map { it.key }
            keysToEvict.forEach { pqcSigMissCounts.remove(it) }
        }
        if (ctrlPqcSigMissCounts.size > PRUNE_THRESHOLD) {
            val evictCount = ctrlPqcSigMissCounts.size - PRUNE_THRESHOLD
            val keysToEvict = ctrlPqcSigMissCounts.entries.toList()
                .sortedBy { it.value }
                .take(evictCount)
                .map { it.key }
            keysToEvict.forEach { ctrlPqcSigMissCounts.remove(it) }
        }
        if (sessionResetSentTo.size > PRUNE_THRESHOLD) {
            sessionResetSentTo.clear()
        }
    }

    override fun getMessages(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessagesForConversation(conversationId).map { entities ->
            entities.map { it.toDomain(keyManager.getUserId() ?: "") }
        }
    }

    /**
     * Not cancellable once started: leaving the chat cancels the caller's scope,
     * which would otherwise strand the message in SENDING (the FAILED update in the
     * catch is itself a suspend call) after the ratchet already advanced.
     */
    override suspend fun sendMessage(
        conversationId: String,
        recipientId: String,
        plaintext: String,
        replyTo: Message?,
    ): Message = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        sendMessageInternal(conversationId, recipientId, plaintext, replyTo)
    }

    private suspend fun sendMessageInternal(
        conversationId: String,
        recipientId: String,
        plaintext: String,
        replyTo: Message? = null,
    ): Message {
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val senderId = keyManager.getUserId() ?: throw IllegalStateException("No identity")

        // Store locally as SENDING before encryption so the message is never
        // lost even if session establishment or encryption fails.
        val entity = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderId = senderId,
            recipientId = recipientId,
            encryptedBody = ByteArray(0),
            nonce = ByteArray(0),
            plaintext = plaintext,
            state = MessageState.SENDING.name,
            timestamp = now,
            expiresAt = disappearingExpiry(),
            isOutgoing = true,
            // Only a message of this chat; never a reply to a reply's quote of something else.
            replyToId = replyTo?.takeIf { it.conversationId == conversationId }?.id,
            replyQuote = replyTo?.takeIf { it.conversationId == conversationId }?.let { ReplyPayload.quoteOf(it.plaintext) },
            replyToOwn = replyTo?.takeIf { it.conversationId == conversationId }?.isOutgoing == true,
        )
        messageDao.insert(entity)

        val envelope = try {
            encryptFor(recipientId, wirePayload(entity, senderId).toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            messageDao.updateState(messageId, MessageState.FAILED.name)
            throw e
        }

        // Update the stored message with the encrypted payload
        messageDao.updateEncryptedBody(messageId, envelope.ciphertext, envelope.nonce)

        // Sign the envelope for recipient verification
        val sigPayload = buildMessageSigPayload(senderId, recipientId, envelope.nonce, envelope.ciphertext)
        val signature = keyManager.sign(sigPayload)
        val signatureB64 = Base64.encodeToString(signature, Base64.NO_WRAP)
        val pqcSignatureB64 = signWithMlDsa(sigPayload)

        // Send to server
        try {
            val response = apiService.sendMessage(
                SendMessageRequest(
                    recipientId = recipientId,
                    encryptedBody = Base64.encodeToString(envelope.ciphertext, Base64.NO_WRAP),
                    nonce = Base64.encodeToString(envelope.nonce, Base64.NO_WRAP),
                    signature = signatureB64,
                    pqcSignature = pqcSignatureB64,
                    clientMessageId = messageId,
                )
            )
            messageDao.updateStateAndTimestamp(messageId, MessageState.SENT.name, response.timestamp)

            updateConversationPreview(conversationId, recipientId, plaintext, response.timestamp)

            return entity.toDomain(senderId).copy(
                state = MessageState.SENT,
                plaintext = plaintext,
            )
        } catch (e: Exception) {
            messageDao.updateState(messageId, MessageState.FAILED.name)
            throw e
        }
    }

    /**
     * Sent like a chat message (ratchet-encrypted and signed), so the relay sees only an
     * ordinary envelope; the receiver recognises [ProfilePayload] after decrypting. Not
     * cancellable once started: the ratchet has advanced by then.
     */
    override suspend fun sendProfile(recipientId: String, name: String) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            sendUnstored(recipientId, ProfilePayload.encode(name))
        }

    /**
     * Shown at once, then sent like a profile: a [ReactionPayload] in an ordinary envelope,
     * stored as no message on either side. If it can't be sent, the reaction goes back to
     * what it was (unless the user changed it again meanwhile). Not cancellable once
     * started: the ratchet has advanced by then.
     */
    override suspend fun sendReaction(message: Message, recipientId: String, emoji: String?) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            reactionLock.withLock {
                val me = keyManager.getUserId() ?: throw IllegalStateException("No identity")
                val entity = messageDao.getById(message.id) ?: return@withLock
                // Only a message between the user and this contact.
                val peer = if (entity.isOutgoing) entity.recipientId else entity.senderId
                require(peer == recipientId) { "Not a message of this chat" }
                val previous = entity.myReaction
                if (previous == emoji) return@withLock
                messageDao.setMyReaction(entity.id, emoji)
                val to = if (entity.isOutgoing) ReceiptManager.relayMessageId(me, entity.id) else entity.id
                try {
                    sendUnstored(recipientId, ReactionPayload.encode(to, emoji, mine = entity.isOutgoing))
                } catch (e: Exception) {
                    if (messageDao.getById(entity.id)?.myReaction == emoji) messageDao.setMyReaction(entity.id, previous)
                    throw e
                }
            }
        }

    /** Encrypt, sign and send [payload] to [recipientId] like a message, storing nothing. */
    private suspend fun sendUnstored(recipientId: String, payload: String) {
        val senderId = keyManager.getUserId() ?: throw IllegalStateException("No identity")
        val envelope = encryptFor(recipientId, payload.toByteArray(Charsets.UTF_8))
        val sigPayload = buildMessageSigPayload(senderId, recipientId, envelope.nonce, envelope.ciphertext)
        apiService.sendMessage(
            SendMessageRequest(
                recipientId = recipientId,
                encryptedBody = Base64.encodeToString(envelope.ciphertext, Base64.NO_WRAP),
                nonce = Base64.encodeToString(envelope.nonce, Base64.NO_WRAP),
                signature = Base64.encodeToString(keyManager.sign(sigPayload), Base64.NO_WRAP),
                pqcSignature = signWithMlDsa(sigPayload),
                clientMessageId = UUID.randomUUID().toString(),
            )
        )
    }

    override suspend fun markDelivered(messageId: String) {
        messageDao.updateState(messageId, MessageState.DELIVERED.name)
    }

    override suspend fun markSeen(messageId: String) {
        messageDao.updateState(messageId, MessageState.SEEN.name)
    }

    override suspend fun retryFailed(messageId: String) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { retryFailedInternal(messageId) }

    /** Messages left in SENDING by a process death can never complete: mark them FAILED (retryable). */
    override suspend fun failInterruptedSends() {
        messageDao.getSendingMessages().forEach { messageDao.updateState(it.id, MessageState.FAILED.name) }
    }

    private suspend fun retryFailedInternal(messageId: String) {
        val entity = messageDao.getById(messageId) ?: return
        // Only our own failed messages (a double tap must not resend a message
        // that the first tap already got through).
        if (!entity.isOutgoing || entity.state != MessageState.FAILED.name) return
        val plaintext = entity.plaintext ?: return // Cannot retry without plaintext
        messageDao.updateState(messageId, MessageState.SENDING.name)

        try {
            val retrySenderId = keyManager.getUserId() ?: throw IllegalStateException("No identity")
            val envelope = encryptFor(entity.recipientId, wirePayload(entity, retrySenderId).toByteArray(Charsets.UTF_8))

            val retrySigPayload = buildMessageSigPayload(retrySenderId, entity.recipientId, envelope.nonce, envelope.ciphertext)
            val retrySignature = keyManager.sign(retrySigPayload)
            val retryPqcSig = signWithMlDsa(retrySigPayload)

            val response = apiService.sendMessage(
                SendMessageRequest(
                    recipientId = entity.recipientId,
                    encryptedBody = Base64.encodeToString(envelope.ciphertext, Base64.NO_WRAP),
                    nonce = Base64.encodeToString(envelope.nonce, Base64.NO_WRAP),
                    signature = Base64.encodeToString(retrySignature, Base64.NO_WRAP),
                    pqcSignature = retryPqcSig,
                    // Same id as the first attempt: if that one reached the relay
                    // (only its response was lost) the relay keeps a single copy.
                    clientMessageId = messageId,
                )
            )
            messageDao.updateStateAndTimestamp(messageId, MessageState.SENT.name, response.timestamp)
            // The message now sorts as the newest one (relay timestamp): show it in the list.
            updateConversationPreview(entity.conversationId, entity.recipientId, plaintext, response.timestamp)
        } catch (e: Exception) {
            messageDao.updateState(messageId, MessageState.FAILED.name)
            throw e
        }
    }

    /**
     * What goes inside the encryption for our own message [entity]: its text, or for a
     * reply the [ReplyPayload] naming the original by the relay's id for it. That id is
     * the original's own id when [me] received it, or derived from our local id when we
     * sent it.
     */
    private fun wirePayload(entity: MessageEntity, me: String): String {
        val replyTo = entity.replyToId ?: return entity.plaintext
        val to = if (entity.replyToOwn) ReceiptManager.relayMessageId(me, replyTo) else replyTo
        return ReplyPayload.encode(entity.plaintext, to, entity.replyQuote.orEmpty(), mine = entity.replyToOwn)
    }

    /**
     * Our own message in [conversationId] whose relay id is [relayId], for a reply that
     * quotes it; null when it's gone. Only the newest few thousand are searched.
     */
    private suspend fun findOwnByRelayId(conversationId: String, me: String, relayId: String): String? =
        messageDao.getOutgoingIds(conversationId, OWN_REPLY_SEARCH_LIMIT)
            .firstOrNull { ReceiptManager.relayMessageId(me, it) == relayId }

    /**
     * Encrypt for a peer under the per-peer lock. Uses the existing session if
     * there is one; otherwise starts a new session with server keys that passed
     * [ContactKeyVerifier] (never raw server output).
     */
    private suspend fun encryptFor(peerId: String, plaintext: ByteArray): EncryptedEnvelope {
        val peerMutex = peerLocks.computeIfAbsent(peerId) { kotlinx.coroutines.sync.Mutex() }
        return peerMutex.withLock {
            val contact = contactDao.getByUserId(peerId)
            val existing = contact?.let { PeerKeys(peerId, it.publicIdentityKey) }
            val peer = if (existing != null && cryptoEngine.hasSendableSession(existing)) {
                // A session bound to the stored identity key can send: no new keys
                // needed. Anything else (no session, identity key differs, no
                // sending chain) starts a new session and must go through the
                // verified-keys path below so the ML-KEM key and downgrade latch apply.
                existing
            } else {
                val keys = verifiedKeysForNewSession(peerId)
                val current = contactDao.getByUserId(peerId)
                if (current?.pqcEstablished == true && keys.pqcEncapsulationKey.isEmpty()) {
                    throw SecurityException("PQC downgrade refused for ${peerId.take(8)}")
                }
                keys.toPeerKeys()
            }
            val envelope = cryptoEngine.encrypt(peer, plaintext)
            recordSessionPqcStatus(peerId)
            envelope
        }
    }

    /** Keys for starting a new outbound session. Throws if they cannot be trusted. */
    private suspend fun verifiedKeysForNewSession(peerId: String): VerifiedKeys {
        return when (val check = keyVerifier.check(peerId)) {
            is KeyCheck.Known -> {
                if (check.rotated) recordRemoteRotation(peerId)
                check.keys
            }
            is KeyCheck.Unknown -> {
                // Sending to someone never added or seen: trust on first use.
                contactDao.upsert(check.keys.toNewContact(verified = false))
                check.keys
            }
            is KeyCheck.Mismatch -> throw SecurityException(
                "Keys for ${peerId.take(8)} changed and could not be verified (${check.reason})"
            )
            KeyCheck.Unavailable -> throw IllegalStateException("Could not fetch keys for ${peerId.take(8)}")
            KeyCheck.NotFound -> throw IllegalStateException("${peerId.take(8)} no longer exists on the server")
        }
    }

    /** Mirror the current session's PQC status into the UI flag and the downgrade latch. */
    private suspend fun recordSessionPqcStatus(peerId: String) {
        val pqc = cryptoEngine.sessionInfo(peerId)?.pqcEstablished ?: return
        keyManager.setPeerPqcEstablished(peerId, pqc)
        if (pqc) {
            val contact = contactDao.getByUserId(peerId)
            if (contact != null && !contact.pqcEstablished) {
                contactDao.upsert(contact.copy(pqcEstablished = true))
            }
        }
    }

    /** Insert a "keys changed" event after a verified (endorsed) remote key rotation. */
    private suspend fun recordRemoteRotation(peerId: String) {
        val localUserId = keyManager.getUserId() ?: ""
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val conversationId = getOrCreateConversationId(peerId)
        messageDao.insert(MessageEntity(
            id = "keychange-$peerId-$now",
            conversationId = conversationId,
            senderId = peerId,
            recipientId = localUserId,
            encryptedBody = ByteArray(0),
            nonce = ByteArray(0),
            plaintext = "",
            state = MessageState.KEY_ROTATED_REMOTE.name,
            timestamp = now,
            expiresAt = null,
            isOutgoing = false,
        ))
    }

    override suspend fun deleteMessage(messageId: String) {
        messageDao.delete(messageId)
    }

    override suspend fun deleteMessagesOlderThan(conversationId: String, timestampMillis: Long) {
        messageDao.deleteOlderThan(conversationId, timestampMillis)
    }

    /** One fetch at a time; see [PendingFetchGate]. */
    private val fetchGate = PendingFetchGate(
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    )

    override suspend fun fetchPendingFromServer() = fetchGate.fetchOrRequest(::fetchPendingInternal)

    override suspend fun fetchPendingAndWait() = fetchGate.fetchAndWait(::fetchPendingInternal)

    private suspend fun fetchPendingInternal() {
        pruneInMemoryMaps()
        val localUserId = keyManager.getUserId() ?: return

        // The server returns at most one page (oldest first); keep going while
        // full pages make progress so a backlog can't starve newer messages.
        var pages = 0
        while (true) {
            val pending = apiService.fetchPendingMessages()
            if (pending.isEmpty()) return

            val receivedIds = mutableListOf<String>()
            for (dto in pending) {
                // Each message is processed without cancellation: once decrypt has
                // consumed (and persisted) the message key, the plaintext must reach
                // the database, or it is lost for good (re-delivery only yields a
                // duplicate). Cancellation takes effect between messages.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    processPendingMessage(dto, localUserId, receivedIds)
                }
            }

            if (receivedIds.isNotEmpty()) {
                apiService.acknowledgeMessages(AckRequest(receivedIds))
            }
            pages++
            if (pending.size < PENDING_PAGE_SIZE || receivedIds.isEmpty() || pages >= MAX_PAGES_PER_FETCH) return
        }
    }

    /** Verify, decrypt and store one pending message; adds its id to [receivedIds] when it may be ACKed. */
    private suspend fun processPendingMessage(
        dto: com.sotto.app.data.remote.dto.PendingMessageDto,
        localUserId: String,
        receivedIds: MutableList<String>,
    ) {
        // Blocked sender: drop (and ACK) before anything else — no decryption, no
        // notification, no message request, no new contact.
        if (blockedUserDao.isBlocked(dto.senderId)) {
            receivedIds.add(dto.messageId)
            return
        }

        // Check for control messages (session_reset / account_deleted)
        if (handleControlMessage(dto, receivedIds)) return

        // Already stored under this id: a resend after the sender lost the relay's
        // answer (the relay derives the id from the sender's local id). Keep the
        // copy we have rather than replacing it (and its seen state).
        if (messageDao.getById(dto.messageId) != null) {
            receivedIds.add(dto.messageId)
            return
        }

        val envelope = try {
            EncryptedEnvelope(
                ciphertext = Base64.decode(dto.encryptedBody, Base64.NO_WRAP),
                nonce = Base64.decode(dto.nonce, Base64.NO_WRAP),
            )
        } catch (_: IllegalArgumentException) {
            if (com.sotto.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo",
                "Rejecting message with invalid Base64 from ${dto.senderId.take(8)}")
            storeRejected(dto.messageId, dto.senderId, localUserId,
                EncryptedEnvelope(ciphertext = ByteArray(0), nonce = ByteArray(0)), dto.timestamp)
            receivedIds.add(dto.messageId)
            return
        }

        // Signatures are mandatory.
        if (dto.signature.isEmpty()) {
            if (com.sotto.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo", "Rejecting unsigned message from ${dto.senderId.take(8)}")
            storeRejected(dto.messageId, dto.senderId, localUserId, envelope, dto.timestamp)
            receivedIds.add(dto.messageId)
            return
        }

        // ── Resolve the sender's keys and verify signatures ──────────
        // Known contacts are verified against the STORED keys. The server is
        // only consulted if that fails (possible endorsed rotation) or if the
        // sender is unknown (trust on first use, contact stays unverified).
        val sigPayload = buildMessageSigPayload(dto.senderId, localUserId, envelope.nonce, envelope.ciphertext)
        var contact = contactDao.getByUserId(dto.senderId)
        var newSenderKeys: VerifiedKeys? = null
        var sigCheck = contact?.let { checkSignatures(sigPayload, dto.signature, dto.pqcSignature, it.publicSigningKey, it.pqcSigningKey) }

        if (contact == null || sigCheck == SigCheck.INVALID) {
            when (val check = keyVerifier.check(dto.senderId)) {
                is KeyCheck.Known -> {
                    if (check.rotated) {
                        recordRemoteRotation(dto.senderId)
                        contact = check.contact
                        sigCheck = checkSignatures(sigPayload, dto.signature, dto.pqcSignature,
                            check.contact.publicSigningKey, check.contact.pqcSigningKey)
                    }
                }
                is KeyCheck.Unknown -> {
                    newSenderKeys = check.keys
                    sigCheck = checkSignatures(sigPayload, dto.signature, dto.pqcSignature,
                        check.keys.publicSigningKey, check.keys.pqcSigningKey)
                }
                is KeyCheck.Mismatch -> {
                    if (com.sotto.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo",
                        "Rejecting message: unverifiable key change for ${dto.senderId.take(8)} (${check.reason})")
                    storeRejected(dto.messageId, dto.senderId, localUserId, envelope, dto.timestamp)
                    receivedIds.add(dto.messageId)
                    return
                }
                KeyCheck.NotFound -> {
                    // The sender's account no longer exists, so its keys can never be
                    // fetched and the message can never be verified. Drop (ACK) it, or
                    // it is re-fetched (and blocks the queue) until the server TTL.
                    if (contact != null) storeRejected(dto.messageId, dto.senderId, localUserId, envelope, dto.timestamp)
                    receivedIds.add(dto.messageId)
                    return
                }
                KeyCheck.Unavailable -> {
                    // Can't fetch the sender's keys right now (unknown sender, or a
                    // known one whose signature only verifies against rotated keys).
                    // Retry on the next sync without ACKing instead of rejecting a
                    // possibly genuine message because of a network blip.
                    return
                }
            }
        }

        when (sigCheck) {
            SigCheck.VALID -> Unit
            SigCheck.PQC_MISSING -> {
                // Missing ML-DSA signature from a PQC-capable sender. Possibly a
                // timing issue (sent before the sender's PQC upgrade completed):
                // skip without ACKing; permanently reject after a few syncs.
                val misses = (pqcSigMissCounts[dto.messageId] ?: 0) + 1
                pqcSigMissCounts[dto.messageId] = misses
                if (misses >= MAX_PQC_SIG_MISS_RETRIES) {
                    pqcSigMissCounts.remove(dto.messageId)
                    storeRejected(dto.messageId, dto.senderId, localUserId, envelope, dto.timestamp)
                    receivedIds.add(dto.messageId)
                }
                return
            }
            SigCheck.INVALID, null -> {
                // Forged or corrupted. This is an authentication failure, not a
                // session problem: never count it toward a session reset.
                if (com.sotto.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo", "Signature verification failed for ${dto.messageId}")
                storeRejected(dto.messageId, dto.senderId, localUserId, envelope, dto.timestamp)
                receivedIds.add(dto.messageId)
                return
            }
        }

        val senderIdentityKey = contact?.publicIdentityKey ?: newSenderKeys!!.publicIdentityKey
        // If we publish an ML-KEM key, any honest peer encapsulates to it, so a
        // classical-only session from them is a downgrade.
        val requirePqc = (keyManager.getPqcEncapsulationKey()?.isNotEmpty() == true)

        val peerMutex = peerLocks.computeIfAbsent(dto.senderId) { kotlinx.coroutines.sync.Mutex() }
        val plaintext = try {
            peerMutex.withLock {
                cryptoEngine.decrypt(PeerKeys(dto.senderId, senderIdentityKey), envelope, requirePqc)
            }
        } catch (e: DuplicateMessageException) {
            // Replay or duplicate delivery: drop silently. Must NOT trigger a
            // session reset, or the relay could break sessions at will.
            if (com.sotto.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo", "Dropping duplicate message ${dto.messageId}")
            receivedIds.add(dto.messageId)
            return
        } catch (e: Exception) {
            if (com.sotto.app.BuildConfig.DEBUG) android.util.Log.e("MessageRepo", "Decrypt failed from ${dto.senderId}: ${e.message}", e)
            countDecryptFailure(dto.messageId, dto.senderId, localUserId, envelope, dto.timestamp, receivedIds)
            return
        }
        decryptFailCounts.remove(dto.messageId)
        pqcSigMissCounts.remove(dto.messageId)

        if (newSenderKeys != null) {
            // First contact from this sender, signature and decryption both OK.
            contact = newSenderKeys.toNewContact(verified = false)
            contactDao.upsert(contact)
        }
        recordSessionPqcStatus(dto.senderId)

        val plaintextStr = String(plaintext, Charsets.UTF_8)
        // The name the sender chose, not chat text: apply it and store no message.
        if (ProfilePayload.isProfile(plaintextStr)) {
            applyReceivedProfile(dto.senderId, plaintextStr, dto.timestamp)
            receivedIds.add(dto.messageId)
            return
        }
        // A reaction: it goes on the message it names, and is no message of its own.
        if (ReactionPayload.isReaction(plaintextStr)) {
            applyReceivedReaction(dto.senderId, localUserId, plaintextStr)
            receivedIds.add(dto.messageId)
            return
        }

        val conversationId = getOrCreateConversationId(dto.senderId)

        // A reply: keep its text, and point at the original when this phone has it.
        // "mine" is the sender's: the original is theirs, so it has the relay's id here.
        val reply = ReplyPayload.decode(plaintextStr)
        val messageText = reply?.text ?: plaintextStr
        val replyToId = reply?.let {
            if (it.mine) it.to else findOwnByRelayId(conversationId, localUserId, it.to) ?: it.to
        }

        val entity = MessageEntity(
            id = dto.messageId,
            conversationId = conversationId,
            senderId = dto.senderId,
            recipientId = localUserId,
            encryptedBody = envelope.ciphertext,
            nonce = envelope.nonce,
            plaintext = messageText,
            state = MessageState.DELIVERED.name,
            timestamp = dto.timestamp,
            expiresAt = disappearingExpiry(),
            isOutgoing = false,
            replyToId = replyToId,
            replyQuote = reply?.quote,
            replyToOwn = reply != null && !reply.mine,
        )
        messageDao.insert(entity)
        receivedIds.add(dto.messageId)

        val isActive = conversationId == activeConversationId
        updateConversationPreview(
            conversationId,
            dto.senderId,
            messageText,
            dto.timestamp,
            incrementUnread = !isActive,
        )

        // Show notification for messages outside the active conversation
        if (!isActive) {
            val senderName = contactDao.getByUserId(dto.senderId)?.displayName
                ?: dto.senderId.take(8)
            notificationManager.showMessageNotification(
                senderId = dto.senderId,
                senderName = senderName,
                messageBody = messageText,
                conversationId = conversationId,
            )
        }
    }

    /**
     * A contact sent the name they chose (a nickname the user gave them still wins). If this
     * is the first we hear from them, they just added the user: their message request is
     * created now, under that name, with a notification.
     */
    private suspend fun applyReceivedProfile(senderId: String, plaintext: String, timestamp: Long) {
        val name = ProfilePayload.decodeName(plaintext) ?: return
        val firstContact = conversationDao.getByContactId(senderId) == null
        contactNames.setProfileName(senderId, name)
        if (!firstContact) return

        val conversationId = getOrCreateConversationId(senderId)
        val isActive = conversationId == activeConversationId
        updateConversationPreview(conversationId, senderId, WANTS_TO_CONNECT, timestamp, incrementUnread = !isActive)
        if (!isActive) {
            notificationManager.showMessageNotification(
                senderId = senderId,
                senderName = contactDao.getByUserId(senderId)?.displayName ?: senderId.take(8),
                messageBody = WANTS_TO_CONNECT,
                conversationId = conversationId,
            )
        }
    }

    /**
     * A contact reacted to a message of their chat (or took their reaction back). Dropped
     * when that message isn't on this phone any more, or isn't one of that chat. A reaction
     * to one of the user's own messages is notified, as on WhatsApp, but isn't counted
     * as unread and doesn't change the chat's preview.
     */
    private suspend fun applyReceivedReaction(senderId: String, me: String, plaintext: String) {
        val reaction = ReactionPayload.decode(plaintext) ?: return
        val conversation = conversationDao.getByContactId(senderId) ?: return
        // "mine" is the sender's: their own message, stored here under the relay's id.
        val targetId = if (reaction.mine) reaction.to else findOwnByRelayId(conversation.id, me, reaction.to) ?: return
        val target = messageDao.getById(targetId) ?: return
        // Only the contact's reactions arrive here (the user's own are stored when sent).
        // "mine" is theirs, so the target is the user's message exactly when it's false.
        if (target.conversationId != conversation.id || target.isOutgoing == reaction.mine) return
        if (!reaction.mine && target.recipientId != senderId) return
        if (reaction.mine && target.senderId != senderId) return
        messageDao.setTheirReaction(target.id, reaction.emoji)

        val emoji = reaction.emoji ?: return
        if (!target.isOutgoing || !conversation.isApproved || conversation.id == activeConversationId) return
        notificationManager.showMessageNotification(
            senderId = senderId,
            senderName = contactDao.getByUserId(senderId)?.displayName ?: senderId.take(8),
            messageBody = reactionNotificationText(emoji, target.plaintext),
            conversationId = conversation.id,
            isReaction = true,
        )
    }

    private enum class SigCheck { VALID, INVALID, PQC_MISSING }

    /** Verify Ed25519 and (if the sender has an ML-DSA key) ML-DSA-65 signatures. */
    private suspend fun checkSignatures(
        payload: ByteArray,
        signatureB64: String,
        pqcSignatureB64: String,
        signingKey: ByteArray,
        pqcSigningKey: ByteArray,
    ): SigCheck {
        val sig = try {
            Base64.decode(signatureB64, Base64.NO_WRAP)
        } catch (_: Exception) {
            return SigCheck.INVALID
        }
        val valid = try {
            cryptoEngine.verify(payload, sig, signingKey)
        } catch (_: Exception) { false }
        if (!valid) return SigCheck.INVALID
        // If the sender has a PQC signing key, the ML-DSA signature is REQUIRED —
        // omitting it would bypass the hybrid authentication model.
        if (pqcSigningKey.isNotEmpty()) {
            if (pqcSignatureB64.isEmpty()) return SigCheck.PQC_MISSING
            val pqcSig = try {
                Base64.decode(pqcSignatureB64, Base64.NO_WRAP)
            } catch (_: Exception) {
                return SigCheck.INVALID
            }
            val pqcValid = try {
                pqcProvider.verify(payload, pqcSig, pqcSigningKey)
            } catch (_: Exception) { false }
            if (!pqcValid) return SigCheck.INVALID
        }
        return SigCheck.VALID
    }

    private suspend fun getOrCreateConversationId(contactId: String): String {
        val existing = conversationDao.getByContactId(contactId)
        if (existing != null) return existing.id

        // New inbound conversation. Someone we added via share code (verified) goes
        // straight in; anyone else is a message request.
        val contact = contactDao.getByUserId(contactId)
        val displayName = contact?.displayName ?: contactId.take(8)

        val id = UUID.randomUUID().toString()
        return conversationDao.getOrInsert(
            com.sotto.app.data.local.entity.ConversationEntity(
                id = id,
                contactId = contactId,
                contactDisplayName = displayName,
                lastMessagePreview = null,
                lastMessageTimestamp = null,
                unreadCount = 0,
                isEncrypted = true,
                isApproved = contact?.verified == true,
            )
        )
    }

    private suspend fun countDecryptFailure(
        messageId: String,
        senderId: String,
        localUserId: String,
        envelope: EncryptedEnvelope,
        timestamp: Long,
        receivedIds: MutableList<String>,
    ) {
        val failures = (decryptFailCounts[messageId] ?: 0) + 1
        decryptFailCounts[messageId] = failures
        if (failures >= MAX_DECRYPT_RETRIES) {
            // Permanently failed — store as DECRYPT_FAILED so the user
            // sees a tombstone instead of silently losing the message.
            if (com.sotto.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo", "Giving up on message $messageId after $failures attempts")
            val conversationId = getOrCreateConversationId(senderId)
            messageDao.insert(MessageEntity(
                id = messageId,
                conversationId = conversationId,
                senderId = senderId,
                recipientId = localUserId,
                encryptedBody = envelope.ciphertext,
                nonce = envelope.nonce,
                plaintext = "",
                state = MessageState.DECRYPT_FAILED.name,
                timestamp = timestamp,
                expiresAt = null,
                isOutgoing = false,
            ))
            receivedIds.add(messageId)
            decryptFailCounts.remove(messageId)

            // The message was correctly signed by the peer but no session can
            // decrypt it: the peer's session is out of sync. Ask them to start a
            // new one.
            if (senderId !in sessionResetSentTo) {
                try {
                    sessionResetSender.send(senderId)
                    sessionResetSentTo.add(senderId)
                } catch (e: Exception) {
                    if (com.sotto.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo",
                        "Failed to send session_reset to ${senderId.take(8)}: ${e.message}")
                }
            }
        }
    }

    /** Store a tombstone for a message that was rejected outright (not retriable). */
    private suspend fun storeRejected(
        messageId: String,
        senderId: String,
        localUserId: String,
        envelope: EncryptedEnvelope,
        timestamp: Long,
    ) {
        val conversationId = getOrCreateConversationId(senderId)
        messageDao.insert(MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderId = senderId,
            recipientId = localUserId,
            encryptedBody = envelope.ciphertext,
            nonce = envelope.nonce,
            plaintext = "",
            state = MessageState.REJECTED.name,
            timestamp = timestamp,
            expiresAt = null,
            isOutgoing = false,
        ))
    }

    /**
     * Check if a pending message is a control message (session_reset / account_deleted).
     * Returns true if the message was handled and should be skipped.
     *
     * Control messages are verified against the sender's STORED keys (never keys
     * fetched from the server, unless those are an endorsed rotation), and must
     * carry a timestamp newer than the last one accepted from that sender.
     */
    private suspend fun handleControlMessage(
        dto: com.sotto.app.data.remote.dto.PendingMessageDto,
        receivedIds: MutableList<String>,
    ): Boolean {
        val nonceBytes = try {
            Base64.decode(dto.nonce, Base64.NO_WRAP)
        } catch (_: Exception) { return false }

        val nonceStr = String(nonceBytes, Charsets.UTF_8)
        if (!nonceStr.startsWith("{\"ctrl\":")) return false

        val obj = try {
            headerJson.parseToJsonElement(nonceStr) as? kotlinx.serialization.json.JsonObject
        } catch (_: Exception) { null } ?: return false
        val ctrl = (obj["ctrl"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val isAccountDeleted = ctrl == SessionResetSender.CTRL_ACCOUNT_DELETED
        if (ctrl != SessionResetSender.CTRL_SESSION_RESET && !isAccountDeleted) return false
        val ts = (obj["ts"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()

        val localUserId = keyManager.getUserId() ?: ""

        // Unsigned control messages are never valid — ACK to drop them.
        if (dto.signature.isEmpty()) {
            receivedIds.add(dto.messageId)
            return true
        }

        // Control messages from someone we have no keys for mean nothing to us.
        var contact = contactDao.getByUserId(dto.senderId)
        if (contact == null) {
            receivedIds.add(dto.messageId)
            return true
        }

        val sigPayload = buildMessageSigPayload(dto.senderId, localUserId, nonceBytes, ByteArray(0))
        var sigCheck = checkSignatures(sigPayload, dto.signature, dto.pqcSignature,
            contact.publicSigningKey, contact.pqcSigningKey)
        var rotated = false

        if (sigCheck == SigCheck.INVALID && !isAccountDeleted) {
            // The peer may have rotated keys and signed with the new ones. Accept
            // only if the new keys are endorsed by the keys we trust.
            when (val check = keyVerifier.check(dto.senderId)) {
                is KeyCheck.Known -> if (check.rotated) {
                    rotated = true
                    contact = check.contact
                    sigCheck = checkSignatures(sigPayload, dto.signature, dto.pqcSignature,
                        check.contact.publicSigningKey, check.contact.pqcSigningKey)
                }
                KeyCheck.Unavailable -> return true // transient: retry on next sync without ACK
                else -> Unit
            }
        }

        when (sigCheck) {
            SigCheck.VALID -> Unit
            SigCheck.INVALID -> {
                if (com.sotto.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo",
                    "$ctrl signature invalid from ${dto.senderId.take(8)}")
                receivedIds.add(dto.messageId)
                return true
            }
            SigCheck.PQC_MISSING -> {
                val misses = (ctrlPqcSigMissCounts[dto.messageId] ?: 0) + 1
                ctrlPqcSigMissCounts[dto.messageId] = misses
                if (misses >= MAX_PQC_SIG_MISS_RETRIES) {
                    ctrlPqcSigMissCounts.remove(dto.messageId)
                    receivedIds.add(dto.messageId)
                }
                return true
            }
        }

        // Freshness: reject replays and unversioned (legacy) control messages.
        val now = System.currentTimeMillis()
        val lastTs = keyManager.getLastControlTimestamp(dto.senderId)
        if (ts == null || ts <= lastTs || now - ts > CTRL_MAX_AGE_MS || ts - now > CTRL_MAX_FUTURE_SKEW_MS) {
            if (com.sotto.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo",
                "Rejecting stale or replayed $ctrl from ${dto.senderId.take(8)}")
            receivedIds.add(dto.messageId)
            return true
        }
        keyManager.setLastControlTimestamp(dto.senderId, ts)

        val peerMutex = peerLocks.computeIfAbsent(dto.senderId) { kotlinx.coroutines.sync.Mutex() }
        peerMutex.withLock {
            if (isAccountDeleted) {
                cryptoEngine.deleteSession(dto.senderId)
            } else {
                // Stop sending on the current session; the next message starts a
                // new one. The old session is kept to read in-flight messages.
                cryptoEngine.resetSession(dto.senderId)
            }
        }
        keyManager.clearPeerSessionFlags(dto.senderId)

        // Mark conversation as needing session re-establishment
        conversationDao.setNeedsSessionReset(dto.senderId, true)
        if (isAccountDeleted) {
            conversationDao.setPeerDeleted(dto.senderId, true)
        }

        if (isAccountDeleted || rotated) {
            val eventState = if (isAccountDeleted) MessageState.ACCOUNT_DELETED else MessageState.KEY_ROTATED_REMOTE
            val conversationId = getOrCreateConversationId(dto.senderId)
            messageDao.insert(MessageEntity(
                id = "keychange-${dto.messageId}",
                conversationId = conversationId,
                senderId = dto.senderId,
                recipientId = localUserId,
                encryptedBody = ByteArray(0),
                nonce = ByteArray(0),
                plaintext = "",
                state = eventState.name,
                timestamp = now,
                expiresAt = null,
                isOutgoing = false,
            ))
        }

        if (com.sotto.app.BuildConfig.DEBUG) android.util.Log.i("MessageRepo",
            "$ctrl received from ${dto.senderId.take(8)}, session reset")

        receivedIds.add(dto.messageId)
        return true
    }

    override suspend fun reEstablishSession(contactId: String) {
        val peerMutex = peerLocks.computeIfAbsent(contactId) { kotlinx.coroutines.sync.Mutex() }
        peerMutex.withLock {
            cryptoEngine.resetSession(contactId)
            val keys = verifiedKeysForNewSession(contactId)
            val contact = contactDao.getByUserId(contactId)
            if (contact?.pqcEstablished == true && keys.pqcEncapsulationKey.isEmpty()) {
                throw SecurityException("PQC downgrade refused for ${contactId.take(8)}")
            }
            cryptoEngine.ensureSession(keys.toPeerKeys())
            recordSessionPqcStatus(contactId)
        }

        // Clear the flag — sending is now allowed again
        conversationDao.setNeedsSessionReset(contactId, false)

        // Clear dedup tracking so future decrypt failures from this peer are handled
        sessionResetSentTo.remove(contactId)
    }

    /** Sign data with ML-DSA-65 if keys are available, returning base64 or empty string. */
    private fun signWithMlDsa(data: ByteArray): String {
        return try {
            val mlDsaPrivKey = keyManager.getMlDsaPrivateKey() ?: return ""
            try {
                Base64.encodeToString(pqcProvider.sign(data, mlDsaPrivKey), Base64.NO_WRAP)
            } finally {
                mlDsaPrivKey.fill(0)
            }
        } catch (e: Exception) {
            if (com.sotto.app.BuildConfig.DEBUG) android.util.Log.w("MessageRepo", "ML-DSA signing failed: ${e.message}")
            ""
        }
    }

    companion object {
        /** Server default page size for GET /v1/messages/pending. */
        private const val PENDING_PAGE_SIZE = 100
        /** How many of our own newest messages a received reply's original is looked for in. */
        private const val OWN_REPLY_SEARCH_LIMIT = 5_000
        /** Preview and notification text when someone adds the user and sends only their name. */
        private const val WANTS_TO_CONNECT = "Wants to connect"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MAX_PAGES_PER_FETCH = 10
        /** After this many failed decrypt attempts, acknowledge the message to
         *  prevent it from poisoning the pending queue forever. */
        private const val MAX_DECRYPT_RETRIES = 3
        /** Sync cycles to skip a message with a missing PQC signature before
         *  permanently rejecting it (gives time for key bundle propagation). */
        private const val MAX_PQC_SIG_MISS_RETRIES = 3

        /** Evict stale entries from in-memory maps when they exceed this size. */
        private const val PRUNE_THRESHOLD = 200

        /** Control messages older than this are rejected (server pending TTL is 30 days). */
        private const val CTRL_MAX_AGE_MS = 31L * 24 * 60 * 60 * 1000
        /** Tolerated clock skew for control-message timestamps from the future. */
        private const val CTRL_MAX_FUTURE_SKEW_MS = 24L * 60 * 60 * 1000
    }

    private suspend fun updateConversationPreview(
        conversationId: String,
        contactId: String,
        preview: String,
        timestamp: Long,
        incrementUnread: Boolean = false,
    ) {
        conversationDao.updatePreview(conversationId, preview.take(100), timestamp, if (incrementUnread) 1 else 0)
    }
}

/** What a reaction's notification says, e.g. `Reacted ❤️ to "See you at 6"`. */
internal fun reactionNotificationText(emoji: String, original: String): String {
    val quote = original.trim().replace(Regex("\\s+"), " ").let { if (it.length > 60) it.take(59).trimEnd() + "…" else it }
    return if (quote.isEmpty()) "Reacted $emoji to your message" else "Reacted $emoji to \"$quote\""
}

private fun MessageEntity.toDomain(localUserId: String): Message {
    return Message(
        id = id,
        conversationId = conversationId,
        senderId = senderId,
        recipientId = recipientId,
        plaintext = plaintext,
        state = MessageState.valueOf(state),
        timestamp = Instant.fromEpochMilliseconds(timestamp),
        expiresAt = expiresAt?.let { Instant.fromEpochMilliseconds(it) },
        isOutgoing = isOutgoing,
        replyTo = replyToId?.let { ReplyRef(it, replyQuote.orEmpty(), replyToOwn) },
        myReaction = myReaction,
        theirReaction = theirReaction,
    )
}
