package com.sotto.app.data.repository

import com.sotto.app.data.local.dao.BlockedUserDao
import com.sotto.app.data.local.dao.ContactDao
import com.sotto.app.data.local.dao.ConversationDao
import com.sotto.app.data.local.dao.GroupDao
import com.sotto.app.data.local.dao.MessageDao
import com.sotto.app.data.local.dao.UnseenGroupMessage
import com.sotto.app.data.local.entity.ContactEntity
import com.sotto.app.data.local.entity.ConversationEntity
import com.sotto.app.data.local.entity.GroupEntity
import com.sotto.app.data.local.entity.GroupHeldEntity
import com.sotto.app.data.local.entity.GroupMemberEntity
import com.sotto.app.data.local.entity.GroupOutboxEntity
import com.sotto.app.data.local.entity.GroupReactionEntity
import com.sotto.app.data.local.entity.MessageEntity
import com.sotto.app.data.remote.ApiService
import com.sotto.app.data.remote.dto.KeyBundleDto
import com.sotto.app.data.remote.dto.SendMessageResponse
import com.sotto.app.domain.repository.CryptoEngine
import com.sotto.app.group.GroupIds
import com.sotto.app.group.TestAccount
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Base64

/** The parts of [GroupDao] the group code uses, in memory. */
class FakeGroupDao : GroupDao {
    val groups = LinkedHashMap<String, GroupEntity>()
    val members = LinkedHashMap<Pair<String, String>, GroupMemberEntity>()
    val outbox = LinkedHashMap<String, GroupOutboxEntity>()
    val reactions = LinkedHashMap<Pair<String, String>, GroupReactionEntity>()
    val held = LinkedHashMap<String, GroupHeldEntity>()
    lateinit var messages: FakeMessageDao

    override fun observeChats(): Flow<List<GroupEntity>> = flow { emit(groups.values.filter { it.status !in listOf("invited", "rejected") }) }
    override fun observeInvitations(): Flow<List<GroupEntity>> = flow { emit(groups.values.filter { it.status == "invited" }) }
    override fun observe(id: String): Flow<GroupEntity?> = flow { emit(groups[id]) }
    override suspend fun get(id: String) = groups[id]
    override suspend fun getAll() = groups.values.toList()
    override suspend fun upsert(group: GroupEntity) { groups[group.id] = group }
    override suspend fun setStatus(id: String, status: String) { groups[id]?.let { groups[id] = it.copy(status = status) } }
    override suspend fun freeze(id: String) { groups[id]?.let { groups[id] = it.copy(frozen = true) } }
    override suspend fun updatePreview(id: String, preview: String, timestamp: Long, unreadDelta: Int) {
        groups[id]?.let { groups[id] = it.copy(lastMessagePreview = preview, lastMessageTimestamp = timestamp, unreadCount = it.unreadCount + unreadDelta) }
    }
    override suspend fun clearUnread(id: String) { groups[id]?.let { groups[id] = it.copy(unreadCount = 0) } }
    override suspend fun clearPreviewsWithoutMessage() {
        groups.replaceAll { _, g ->
            if (g.lastMessagePreview != null && messages.rows.values.none { it.conversationId == g.id && it.timestamp == g.lastMessageTimestamp }) {
                g.copy(lastMessagePreview = null)
            } else {
                g
            }
        }
    }
    override suspend fun delete(id: String) { groups.remove(id) }
    override suspend fun deleteAll() = groups.clear()
    override fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>> = flow { emit(getMembers(groupId)) }
    override suspend fun getMembers(groupId: String) = members.values.filter { it.groupId == groupId }
    override suspend fun getMember(groupId: String, userId: String) = members[groupId to userId]
    override suspend fun getMemberships(userId: String) = members.values.filter { it.userId == userId }
    override suspend fun upsertMembers(members: List<GroupMemberEntity>) { members.forEach { this.members[it.groupId to it.userId] = it } }
    override suspend fun deleteMembers(groupId: String) { members.keys.removeAll { it.first == groupId } }
    override suspend fun setKeyState(groupId: String, userId: String, keyState: String) {
        members[groupId to userId]?.let { members[groupId to userId] = it.copy(keyState = keyState) }
    }
    override suspend fun markGoneEverywhere(userId: String) { members.replaceAll { _, m -> if (m.userId == userId) m.copy(status = "gone") else m } }
    override suspend fun markGone(groupId: String, userId: String) {
        members[groupId to userId]?.let { members[groupId to userId] = it.copy(status = "gone") }
    }
    override fun observeMemberChanges(): Flow<Int> = flow { emit(members.size) }
    override fun observeActiveCount(groupId: String): Flow<Int> = flow { emit(getMembers(groupId).count { it.status == "active" }) }
    override suspend fun insertCopies(copies: List<GroupOutboxEntity>) { copies.forEach { outbox.putIfAbsent(it.id, it) } }
    override suspend fun getCopy(id: String) = outbox[id]
    override suspend fun getCopiesForMessage(messageId: String) = outbox.values.filter { it.messageId == messageId }
    override fun observeCopiesForMessage(messageId: String): Flow<List<GroupOutboxEntity>> = flow { emit(getCopiesForMessage(messageId)) }
    /** Message times for the read rows; the fake has no join with messages, so tests set them. */
    val messageTimes = HashMap<String, Long>()
    override fun observeReads(groupId: String): Flow<List<com.sotto.app.data.local.dao.GroupReadRow>> = flow {
        emit(
            outbox.values.filter { it.groupId == groupId && it.messageId != null && it.state == "read" }
                .map { com.sotto.app.data.local.dao.GroupReadRow(it.recipientId, it.messageId!!, messageTimes[it.messageId] ?: it.createdAt) },
        )
    }
    override suspend fun getRetryableFailed(maxAttempts: Int, messagesSince: Long, limit: Int) =
        outbox.values.filter {
            it.state == "failed" && it.attempts < maxAttempts && it.lastError != "keys" &&
                (it.messageId == null || it.createdAt >= messagesSince)
        }.sortedBy { it.createdAt }.take(limit)
    override fun observeProgress(groupId: String, maxAttempts: Int, since: Long): Flow<List<com.sotto.app.data.local.dao.GroupCopyProgressRow>> = flow {
        emit(
            outbox.values.filter { it.groupId == groupId && it.messageId != null && it.state != "skipped" }
                .groupBy { it.messageId!! }
                .map { (id, copies) ->
                    com.sotto.app.data.local.dao.GroupCopyProgressRow(
                        id, copies.size, copies.count { it.state in listOf("sent", "delivered", "read") },
                        copies.count { it.state == "delivered" || it.state == "read" }, copies.count { it.state == "read" },
                        copies.count { it.state == "failed" && it.attempts < maxAttempts && it.lastError != "keys" && it.createdAt >= since },
                    )
                },
        )
    }
    override fun observeControlCopies(groupId: String): Flow<List<GroupOutboxEntity>> =
        flow { emit(outbox.values.filter { it.groupId == groupId && it.messageId == null }.sortedByDescending { it.createdAt }) }
    override suspend fun getByStates(states: List<String>, limit: Int) = outbox.values.filter { it.state in states }.sortedBy { it.createdAt }.take(limit)
    override suspend fun findByRelayIds(recipientId: String, relayIds: List<String>) =
        outbox.values.filter { it.recipientId == recipientId && it.relayId in relayIds }
    override suspend fun setSealed(id: String, ciphertext: ByteArray, nonce: ByteArray, signature: String, pqcSignature: String, now: Long) {
        outbox[id]?.let { outbox[id] = it.copy(ciphertext = ciphertext, nonce = nonce, signature = signature, pqcSignature = pqcSignature, packet = null, state = "sealed", updatedAt = now) }
    }
    override suspend fun setSent(id: String, now: Long) {
        outbox[id]?.let { outbox[id] = it.copy(state = "sent", ciphertext = null, nonce = null, signature = null, pqcSignature = null, packet = null, lastError = null, updatedAt = now) }
    }
    override suspend fun setFailed(id: String, state: String, error: String?, now: Long) {
        outbox[id]?.let { outbox[id] = it.copy(state = state, attempts = it.attempts + 1, lastError = error, updatedAt = now) }
    }
    override suspend fun setState(id: String, state: String, now: Long) { outbox[id]?.let { outbox[id] = it.copy(state = state, updatedAt = now) } }
    override suspend fun deleteCopiesForGroup(groupId: String) { outbox.values.removeAll { it.groupId == groupId } }
    override suspend fun deleteCopiesForMessage(messageId: String) { outbox.values.removeAll { it.messageId == messageId } }
    override suspend fun pruneControlCopies(before: Long) {
        outbox.values.removeAll { it.messageId == null && it.state in listOf("sent", "delivered", "read", "skipped") && it.updatedAt < before }
    }
    override suspend fun pruneOrphanCopies() { outbox.values.removeAll { it.messageId != null && messages.rows[it.messageId] == null } }
    override fun observeReactions(groupId: String): Flow<List<GroupReactionEntity>> =
        flow { emit(reactions.values.filter { messages.rows[it.messageId]?.conversationId == groupId }) }
    override suspend fun getReactions(messageId: String) = reactions.values.filter { it.messageId == messageId }
    override suspend fun upsertReaction(reaction: GroupReactionEntity) { reactions[reaction.messageId to reaction.reactorId] = reaction }
    override suspend fun deleteReaction(messageId: String, reactorId: String) { reactions.remove(messageId to reactorId) }
    override suspend fun pruneOrphanReactions() { reactions.values.removeAll { messages.rows[it.messageId] == null } }
    override suspend fun hold(held: GroupHeldEntity) { this.held.putIfAbsent(held.relayId, held) }
    override suspend fun getHeld(groupId: String) = held.values.filter { it.groupId == groupId }.sortedBy { it.receivedAt }
    override suspend fun deleteHeld(relayId: String) { held.remove(relayId) }
    override suspend fun deleteHeldForGroup(groupId: String) { held.values.removeAll { it.groupId == groupId } }
    override suspend fun pruneHeld(before: Long) { held.values.removeAll { it.receivedAt < before } }
}

/** Messages in memory: what the group code reads and writes; the rest is a relaxed mock. */
class FakeMessageDao(rest: MessageDao = mockk(relaxed = true)) : MessageDao by rest {
    val rows = LinkedHashMap<String, MessageEntity>()

    override fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>> =
        flow { emit(rows.values.filter { it.conversationId == conversationId }.sortedBy { it.timestamp }) }
    override suspend fun insert(message: MessageEntity) { rows[message.id] = message }
    override suspend fun getById(messageId: String) = rows[messageId]
    override suspend fun updateState(messageId: String, state: String) { rows[messageId]?.let { rows[messageId] = it.copy(state = state) } }
    override suspend fun setMediaState(messageId: String, state: String) { rows[messageId]?.let { rows[messageId] = it.copy(mediaState = state) } }
    override suspend fun setAttachmentId(messageId: String, attachmentId: String?) {
        rows[messageId]?.let { rows[messageId] = it.copy(attachmentId = attachmentId) }
    }
    override suspend fun getSendingMessages() = rows.values.filter { it.state == "SENDING" }
    override suspend fun getSendingGroupMessages() = rows.values.filter { it.state == "SENDING" && it.groupId != null && it.isOutgoing }
    override suspend fun deleteAllForConversation(conversationId: String) { rows.values.removeAll { it.conversationId == conversationId } }
    override suspend fun markSeen(ids: List<String>) { ids.forEach { id -> rows[id]?.let { rows[id] = it.copy(state = "SEEN") } } }
    override suspend fun getRecentUnseenGroupMessages(groupId: String, since: Long, limit: Int) =
        rows.values.filter { it.conversationId == groupId && it.groupId != null && !it.isOutgoing && it.state == "DELIVERED" && it.timestamp >= since }
            .sortedBy { it.timestamp }.take(limit).map { UnseenGroupMessage(it.id, it.senderId, it.relayId) }
    override suspend fun countUnseenIncoming(conversationId: String) =
        rows.values.count { it.conversationId == conversationId && !it.isOutgoing && it.state == "DELIVERED" }
    override suspend fun deleteExpired(now: Long): Int {
        val gone = rows.values.filter { it.expiresAt != null && it.expiresAt!! < now }
        gone.forEach { rows.remove(it.id) }
        return gone.size
    }

    fun inGroup(groupId: String) = rows.values.filter { it.conversationId == groupId }.sortedBy { it.timestamp }
    fun messagesIn(groupId: String) = inGroup(groupId).filter { it.state != "GROUP_EVENT" }
}

class FakeContactDao(rest: ContactDao = mockk(relaxed = true)) : ContactDao by rest {
    val rows = LinkedHashMap<String, ContactEntity>()
    override suspend fun getByUserId(userId: String) = rows[userId]
    override suspend fun upsert(contact: ContactEntity) { rows[contact.userId] = contact }
    override suspend fun getAllWithKeys() = rows.values.toList()
    override suspend fun delete(userId: String) { rows.remove(userId) }
}

class FakeConversationDao(rest: ConversationDao = mockk(relaxed = true)) : ConversationDao by rest {
    val rows = LinkedHashMap<String, ConversationEntity>()
    override suspend fun getByContactId(contactId: String) = rows.values.firstOrNull { it.contactId == contactId }
}

class FakeBlockedUserDao(rest: BlockedUserDao = mockk(relaxed = true)) : BlockedUserDao by rest {
    val blocked = mutableSetOf<String>()
    override suspend fun isBlocked(userId: String) = userId in blocked
}

/**
 * The relay, as group copies see it: a queue per recipient, one copy per sender and client id
 * while it's pending (messages.py), and the keys it hands out ([directory], which a test may
 * make lie).
 */
class FakeRelay {
    class Envelope(val relayId: String, val sender: String, val to: String, val plaintext: String, val timestamp: Long)

    val queues = LinkedHashMap<String, MutableList<Envelope>>()
    val posted = mutableListOf<Envelope>()
    val directory = LinkedHashMap<String, TestAccount>()
    var clock = 1_764_000_000_000L
    /** Posts to these recipients fail (a network error, the relay refusing). */
    val failFor = mutableSetOf<String>()
    /** Key lookups for these accounts fail (the relay can't be reached). */
    val failBundles = mutableSetOf<String>()

    fun post(sender: String, to: String, clientId: String, plaintext: String): SendMessageResponse {
        if (to in failFor) throw java.io.IOException("relay unreachable for $to")
        val relayId = ReceiptManager.relayMessageId(sender, clientId)
        val queue = queues.getOrPut(to) { mutableListOf() }
        if (queue.none { it.relayId == relayId }) {
            val e = Envelope(relayId, sender, to, plaintext, ++clock)
            queue += e
            posted += e
        }
        return SendMessageResponse(relayId, clock)
    }

    fun bundle(userId: String): KeyBundleDto? = directory[userId]?.let {
        val b = Base64.getEncoder()
        KeyBundleDto(userId, b.encodeToString(it.edPublic), b.encodeToString(it.identityKey), b.encodeToString(it.kemKey), b.encodeToString(it.mlDsaPublic))
    }

    fun api(): ApiService = mockk(relaxed = true) {
        coEvery { fetchKeyBundle(any(), any()) } answers {
            if (firstArg<String>() in failBundles) throw java.io.IOException("unreachable")
            bundle(firstArg())
        }
    }
}

/** A contact entry pinning [account]'s real keys. */
fun contactOf(account: TestAccount, inPerson: Boolean = true, groupOnly: Boolean = false) = ContactEntity(
    userId = account.userId,
    displayName = account.userId.replaceFirstChar { it.uppercase() },
    publicIdentityKey = account.identityKey,
    publicSigningKey = account.edPublic,
    pqcSigningKey = account.mlDsaPublic,
    verified = !groupOnly,
    keyFingerprint = account.fingerprint,
    verifiedInPerson = inPerson,
    groupOnly = groupOnly,
    profileName = account.userId.replaceFirstChar { it.uppercase() },
)

fun relaxedCrypto(): CryptoEngine = mockk(relaxed = true)

fun newRowIdFor(groupId: String, sender: String, id: String) = GroupIds.rowId(groupId, sender, id)
