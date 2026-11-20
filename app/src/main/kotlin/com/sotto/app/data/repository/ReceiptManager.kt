package com.sotto.app.data.repository

import android.util.Base64
import android.util.Log
import com.sotto.app.BuildConfig
import com.sotto.app.crypto.KeyCheck
import com.sotto.app.crypto.KeyManager
import com.sotto.app.crypto.lengthPrefixed
import com.sotto.app.data.local.dao.BlockedUserDao
import com.sotto.app.data.local.dao.ContactDao
import com.sotto.app.data.local.dao.ConversationDao
import com.sotto.app.data.local.dao.MessageDao
import com.sotto.app.data.remote.ApiService
import com.sotto.app.data.remote.dto.ReceiptAckRequest
import com.sotto.app.data.remote.dto.ReceiptRequest
import com.sotto.app.domain.repository.CryptoEngine
import com.sotto.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Read receipts, and the relay's delivery receipts.
 *
 * Delivery receipts come from the relay itself (it knows when the recipient's phone
 * fetched a message), unsigned and whatever the read receipts setting: they mark our
 * sent messages DELIVERED (the grey double tick).
 *
 * Mutual and off by default: we only send receipts, and only show that our
 * messages were read, while the user has "Read receipts" on. Never sent for a
 * message request or to a blocked user.
 *
 * Receipts carry relay message ids. The reader stores incoming messages under
 * those ids; the sender derives them from its own local ids the way the relay
 * does (first 32 hex chars of SHA-256("<sender id>:<local id>")). They travel
 * through /v1/receipts, never the message queue, so older clients don't see
 * them, and are signed by the reader so the relay can't forge them.
 */
@Singleton
class ReceiptManager @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val blockedUserDao: BlockedUserDao,
    private val apiService: ApiService,
    private val keyManager: KeyManager,
    private val cryptoEngine: CryptoEngine,
    private val settingsRepository: Provider<SettingsRepository>,
    private val keyVerifier: com.sotto.app.crypto.ContactKeyVerifier,
) {
    private val fetchMutex = Mutex()
    private val markMutex = Mutex()

    private suspend fun receiptsEnabled(): Boolean =
        settingsRepository.get().getPrivacySettings().first().readReceipts

    /**
     * The user is looking at [conversationId]: mark its messages seen and tell the
     * sender. A message is marked seen only once its receipt went out (or no
     * receipt is due), so a failed send is retried the next time instead of the
     * receipt being lost. Receipts cover recent messages only: history from
     * before receipts were turned on is marked seen without telling anyone.
     */
    suspend fun markConversationRead(conversationId: String) = markMutex.withLock {
        val conversation = conversationDao.getByIdOnce(conversationId)
        val peerId = conversation?.contactId
        val sendReceipts = conversation != null && conversation.isApproved && peerId != null &&
            receiptsEnabled() && !blockedUserDao.isBlocked(peerId)
        val me = keyManager.getUserId()

        if (!sendReceipts || me == null || peerId == null) {
            messageDao.markIncomingSeen(conversationId, before = Long.MAX_VALUE)
            return@withLock
        }
        val since = System.currentTimeMillis() - RECEIPT_WINDOW_MS
        // History from before the window is marked seen without a receipt.
        messageDao.markIncomingSeen(conversationId, before = since)
        val recent = messageDao.getRecentUnseenIncomingIds(conversationId, since, MAX_IDS_PER_RECEIPT)
        if (recent.isEmpty()) return@withLock
        try {
            val signature = keyManager.sign(signedPayload(reader = me, sender = peerId, ids = recent))
            apiService.sendReceipt(
                ReceiptRequest(peerId, recent, Base64.encodeToString(signature, Base64.NO_WRAP))
            )
        } catch (e: Exception) {
            // Leave them unseen: the next look at the chat retries.
            if (BuildConfig.DEBUG) Log.w(TAG, "Sending read receipt failed: ${e.message}")
            return@withLock
        }
        // Exactly the messages the receipt covered (one arriving meanwhile still gets its own).
        messageDao.markSeen(recent)
    }

    /** Number of received messages in [conversationId] the user hasn't seen. */
    fun observeUnseen(conversationId: String): kotlinx.coroutines.flow.Flow<Int> =
        messageDao.observeUnseenIncomingCount(conversationId)

    /** Fetch receipts for our messages: mark the matching ones seen, or delivered. */
    suspend fun fetchReceipts() = fetchMutex.withLock {
        try {
            pageThrough { fetchReceiptPage() }
        } catch (e: Exception) {
            // Best effort (database or settings errors included): never crash the caller.
            if (BuildConfig.DEBUG) Log.w(TAG, "Processing receipts failed: ${e.message}")
        }
        try {
            pageThrough { fetchDeliveryPage() }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Processing delivery receipts failed: ${e.message}")
        }
    }

    /** Fetch pages until one comes back short, fails, or [MAX_PAGES] were read. */
    private inline fun pageThrough(fetchPage: () -> Boolean?) {
        repeat(MAX_PAGES) {
            val full = fetchPage() ?: return
            if (!full) return
        }
    }

    /** One page of delivery receipts; null on a network error, otherwise whether the page was full. */
    private suspend fun fetchDeliveryPage(): Boolean? {
        val me = keyManager.getUserId() ?: return null
        val receipts = try {
            apiService.fetchDeliveryReceipts()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Fetching delivery receipts failed: ${e.message}")
            return null
        }
        if (receipts.isEmpty()) return false
        for (receipt in receipts) applyDelivery(me, receipt)
        try {
            apiService.ackDeliveryReceipts(ReceiptAckRequest(receipts.map { it.id }))
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Acking delivery receipts failed: ${e.message}")
            return null
        }
        return receipts.size >= SERVER_PAGE_SIZE
    }

    /** The relay says these messages of ours reached [receipt]'s sender's phone. */
    private suspend fun applyDelivery(me: String, receipt: com.sotto.app.data.remote.dto.DeliveryReceiptDto) {
        if (contactDao.getByUserId(receipt.senderId) == null) return
        if (blockedUserDao.isBlocked(receipt.senderId)) return
        val wanted = receipt.messageIds.toHashSet()
        val since = System.currentTimeMillis() - MATCH_WINDOW_MS
        val matched = messageDao.getUndeliveredOutgoingIds(receipt.senderId, since)
            .filter { relayMessageId(me, it) in wanted }
        if (matched.isNotEmpty()) matched.chunked(500).forEach { messageDao.markDelivered(it) }
    }

    /** One page; null on a network error, otherwise whether the page was full. */
    private suspend fun fetchReceiptPage(): Boolean? {
        val me = keyManager.getUserId() ?: return null
        val receipts = try {
            apiService.fetchPendingReceipts()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Fetching receipts failed: ${e.message}")
            return null
        }
        if (receipts.isEmpty()) return false
        val showReads = receiptsEnabled()
        val handled = mutableListOf<Long>()
        for (receipt in receipts) {
            when (if (showReads) applyReceipt(me, receipt) else ReceiptResult.Done) {
                ReceiptResult.Done -> handled += receipt.id
                ReceiptResult.Retry -> Unit // keys couldn't be checked now: keep it on the server
            }
        }
        if (handled.isEmpty()) return null // nothing could be handled now; don't spin
        try {
            apiService.ackReceipts(ReceiptAckRequest(handled))
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Acking receipts failed: ${e.message}")
            return null
        }
        return receipts.size >= SERVER_PAGE_SIZE
    }

    private enum class ReceiptResult { Done, Retry }

    private suspend fun applyReceipt(me: String, receipt: com.sotto.app.data.remote.dto.ReceiptDto): ReceiptResult {
        val contact = contactDao.getByUserId(receipt.senderId) ?: return ReceiptResult.Done
        if (blockedUserDao.isBlocked(receipt.senderId)) return ReceiptResult.Done
        val payload = signedPayload(reader = receipt.senderId, sender = me, ids = receipt.messageIds)
        val signature = try {
            Base64.decode(receipt.signature, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return ReceiptResult.Done
        }
        var valid = cryptoEngine.verify(payload, signature, contact.publicSigningKey)
        if (!valid) {
            // The peer may have rotated keys and signed with the new ones: accept
            // only keys endorsed by the ones we trust (as for messages and calls).
            when (val check = keyVerifier.check(receipt.senderId)) {
                is KeyCheck.Known -> if (check.rotated) {
                    valid = cryptoEngine.verify(payload, signature, check.contact.publicSigningKey)
                }
                KeyCheck.Unavailable -> return ReceiptResult.Retry
                else -> Unit
            }
        }
        if (!valid) return ReceiptResult.Done // forged or garbage: drop
        val wanted = receipt.messageIds.toHashSet()
        val since = System.currentTimeMillis() - MATCH_WINDOW_MS
        val matched = messageDao.getUnreadOutgoingIds(receipt.senderId, since)
            .filter { relayMessageId(me, it) in wanted }
        if (matched.isNotEmpty()) matched.chunked(500).forEach { messageDao.markSeen(it) }
        return ReceiptResult.Done
    }

    private fun signedPayload(reader: String, sender: String, ids: List<String>): ByteArray {
        var payload = lengthPrefixed(DOMAIN) +
            lengthPrefixed(reader.toByteArray(Charsets.UTF_8)) +
            lengthPrefixed(sender.toByteArray(Charsets.UTF_8))
        for (id in ids) payload += lengthPrefixed(id.toByteArray(Charsets.UTF_8))
        return payload
    }

    companion object {
        private const val TAG = "ReceiptManager"
        private const val MAX_IDS_PER_RECEIPT = 100
        private const val SERVER_PAGE_SIZE = 100
        private const val MAX_PAGES = 20
        /** Only messages this recent get a receipt (no burst for old history). */
        private const val RECEIPT_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
        /** Sent messages older than this are not matched against receipts. */
        private const val MATCH_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        private val DOMAIN = "ChatControll-read-receipt-v1".toByteArray(Charsets.UTF_8)

        /** The relay's id for a message we sent with local id [localId] (see messages.py). */
        fun relayMessageId(senderId: String, localId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$senderId:$localId".toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.substring(0, 32)
        }
    }
}
