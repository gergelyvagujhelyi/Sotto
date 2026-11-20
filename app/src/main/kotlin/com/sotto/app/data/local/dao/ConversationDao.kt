package com.sotto.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sotto.app.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations WHERE isApproved = 1 ORDER BY lastMessageTimestamp DESC")
    fun getAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE isApproved = 0 ORDER BY lastMessageTimestamp DESC")
    fun getMessageRequests(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun getById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getByIdOnce(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE contactId = :contactId LIMIT 1")
    suspend fun getByContactId(contactId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    /**
     * Create [conversation] unless one for its contact exists, and return the id of
     * the contact's conversation either way. REPLACE would delete a conversation
     * another path created a moment earlier (contactId is unique), orphaning the
     * messages already stored under it.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(conversation: ConversationEntity): Long

    @androidx.room.Transaction
    suspend fun getOrInsert(conversation: ConversationEntity): String {
        insertIfAbsent(conversation)
        return getByContactId(conversation.contactId)?.id ?: conversation.id
    }

    /**
     * Update only the preview columns. Rewriting the whole row (read, copy, REPLACE)
     * dropped flags it didn't copy and undid changes made in between (approve, reset).
     */
    @Query(
        "UPDATE conversations SET lastMessagePreview = :preview, lastMessageTimestamp = :timestamp, " +
            "unreadCount = unreadCount + :unreadDelta WHERE id = :conversationId"
    )
    suspend fun updatePreview(conversationId: String, preview: String, timestamp: Long, unreadDelta: Int)

    @Query("UPDATE conversations SET isApproved = 1 WHERE id = :conversationId")
    suspend fun approve(conversationId: String)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :conversationId")
    suspend fun clearUnread(conversationId: String)

    @Query("UPDATE conversations SET needsSessionReset = :needsReset WHERE contactId = :contactId")
    suspend fun setNeedsSessionReset(contactId: String, needsReset: Boolean)

    @Query("UPDATE conversations SET peerDeleted = :deleted WHERE contactId = :contactId")
    suspend fun setPeerDeleted(contactId: String, deleted: Boolean)

    /** Keep the conversation's copy of the contact's name in step with the contact. */
    @Query("UPDATE conversations SET contactDisplayName = :name WHERE contactId = :contactId")
    suspend fun setContactDisplayName(contactId: String, name: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    /** Drop previews whose message no longer exists (e.g. it disappeared). */
    // Exactly the previewed message: every preview update uses that message's own
    // timestamp. ("Any message at least as new" kept the preview of a deleted
    // message when a newer one existed, e.g. after a message delivered late.)
    @Query(
        "UPDATE conversations SET lastMessagePreview = NULL " +
            "WHERE lastMessagePreview IS NOT NULL AND NOT EXISTS (" +
            "SELECT 1 FROM messages WHERE messages.conversationId = conversations.id " +
            "AND messages.timestamp = conversations.lastMessageTimestamp)"
    )
    suspend fun clearPreviewsWithoutMessage()
}
