package com.sotto.app.data.repository

import com.sotto.app.data.local.dao.ConversationDao
import com.sotto.app.data.local.dao.MessageDao
import com.sotto.app.data.local.entity.ConversationEntity
import com.sotto.app.domain.model.Conversation
import com.sotto.app.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val contactDao: com.sotto.app.data.local.dao.ContactDao,
    private val notificationManager: dagger.Lazy<com.sotto.app.notification.ChatNotificationManager>,
    private val blockedUserDao: com.sotto.app.data.local.dao.BlockedUserDao,
) : ConversationRepository {

    override fun getConversations(): Flow<List<Conversation>> {
        return conversationDao.getAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getMessageRequests(): Flow<List<Conversation>> {
        return conversationDao.getMessageRequests().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getConversation(conversationId: String): Flow<Conversation?> {
        return conversationDao.getById(conversationId).map { it?.toDomain() }
    }

    override suspend fun getOrCreateConversation(contactId: String): String {
        // The user starts a chat with them: a group member known only through a group becomes a contact.
        contactDao.clearGroupOnly(contactId)
        val existing = conversationDao.getByContactId(contactId)
        if (existing != null) {
            // Auto-approve if user explicitly adds this contact
            if (!existing.isApproved) {
                conversationDao.approve(existing.id)
            }
            return existing.id
        }

        val id = UUID.randomUUID().toString()
        val actualId = conversationDao.getOrInsert(
            ConversationEntity(
                id = id,
                contactId = contactId,
                // The contact may already have a name (a nickname, or the one they sent).
                contactDisplayName = contactDao.getByUserId(contactId)?.displayName ?: contactId.take(8),
                lastMessagePreview = null,
                lastMessageTimestamp = null,
                unreadCount = 0,
                isEncrypted = true,
                isApproved = true,
            )
        )
        // Another path (an incoming message request) created it first: the user
        // added this contact, so approve it as above.
        if (actualId != id) conversationDao.approve(actualId)
        return actualId
    }

    override suspend fun approveConversation(conversationId: String) {
        conversationDao.approve(conversationId)
        // Accepting their request makes a group member known only through a group a contact.
        conversationDao.getByIdOnce(conversationId)?.contactId?.let { contactDao.clearGroupOnly(it) }
    }

    override suspend fun clearUnread(conversationId: String) {
        conversationDao.clearUnread(conversationId)
        // The user is looking at the chat: its notifications are seen, the missed-call one too
        // (the chat shows those calls), so the next missed call counts from one.
        val contactId = conversationDao.getByIdOnce(conversationId)?.contactId
        notificationManager.get().cancelNotification(conversationId, contactId)
    }

    override suspend fun deleteConversation(conversationId: String) {
        val contactId = conversationDao.getByIdOnce(conversationId)?.contactId
        messageDao.deleteAllForConversation(conversationId)
        conversationDao.delete(conversationId)
        // A leftover notification would reopen the deleted chat (and let the user
        // write to a rejected stranger).
        notificationManager.get().cancelNotification(conversationId, contactId)
    }

    override suspend fun rejectMessageRequest(conversationId: String) {
        val conversation = conversationDao.getByIdOnce(conversationId)
        deleteConversation(conversationId)
        val contactId = conversation?.contactId ?: return
        val contact = contactDao.getByUserId(contactId) ?: return
        if (!contact.verified) {
            // Otherwise the stranger stays in the contacts list and keeps receiving
            // our key-rotation / account-deleted notices.
            contactDao.delete(contactId)
            // Keep the ratchet session. Deleting it kept its session tag as "seen"
            // (replay protection) while the sender, who never got a reply, keeps
            // sending on that same session: every later message was then dropped
            // as a replay, so rejecting silently blocked them for good (and
            // unblocking couldn't undo it). A key change is still caught: the
            // session record is bound to their identity key.
        }
    }

    override suspend fun removeContact(userId: String) {
        conversationDao.getByContactId(userId)?.let { deleteConversation(it.id) }
        // As in rejectMessageRequest: the contact goes, the ratchet session stays.
        contactDao.delete(userId)
    }

    override suspend fun blockUser(userId: String, displayName: String) {
        blockedUserDao.block(
            com.sotto.app.data.local.entity.BlockedUserEntity(
                userId = userId,
                displayName = displayName,
                blockedAt = System.currentTimeMillis(),
            )
        )
        val conversation = conversationDao.getByContactId(userId) ?: return
        if (!conversation.isApproved) rejectMessageRequest(conversation.id)
    }

    override suspend fun unblockUser(userId: String) {
        blockedUserDao.unblock(userId)
    }

    override fun observeBlocked(userId: String): Flow<Boolean> = blockedUserDao.observeIsBlocked(userId)

    override fun getBlockedUsers(): Flow<List<com.sotto.app.domain.model.BlockedUser>> =
        blockedUserDao.getAll().map { list ->
            list.map { com.sotto.app.domain.model.BlockedUser(it.userId, it.displayName) }
        }
}

private fun ConversationEntity.toDomain(): Conversation {
    return Conversation(
        id = id,
        contactId = contactId,
        contactDisplayName = contactDisplayName,
        lastMessagePreview = lastMessagePreview,
        lastMessageTimestamp = lastMessageTimestamp?.let { Instant.fromEpochMilliseconds(it) },
        unreadCount = unreadCount,
        isEncrypted = isEncrypted,
        isApproved = isApproved,
        needsSessionReset = needsSessionReset,
        peerDeleted = peerDeleted,
    )
}
