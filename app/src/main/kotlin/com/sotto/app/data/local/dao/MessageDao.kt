package com.sotto.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sotto.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/** A received message's text and time, for its chat's notification. */
data class UnseenText(val text: String, val timestamp: Long, val mediaKind: String? = null, val mediaName: String? = null)

/** A received group message not seen yet: whose it is, and the relay's id for our copy (for a read receipt). */
data class UnseenGroupMessage(val id: String, val senderId: String, val relayId: String?)

/** A received group message for a notification: who wrote it, and what. */
data class UnseenGroupText(val senderId: String, val text: String, val timestamp: Long, val mediaKind: String? = null, val mediaName: String? = null)

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    // A receipt can arrive before the send result (or for a send whose response was
    // lost): SEEN is final, and a sent message that was delivered never goes back to
    // SENDING / SENT / FAILED.
    @Query(
        "UPDATE messages SET state = :state WHERE id = :messageId AND state != 'SEEN' " +
            "AND NOT (isOutgoing = 1 AND state = 'DELIVERED' AND :state IN ('SENDING', 'SENT', 'FAILED'))"
    )
    suspend fun updateState(messageId: String, state: String)

    /** Received messages of a conversation not seen yet and newer than [since], newest first. */
    @Query(
        "SELECT id FROM messages WHERE conversationId = :conversationId AND isOutgoing = 0 " +
            "AND state = 'DELIVERED' AND timestamp >= :since ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun getRecentUnseenIncomingIds(conversationId: String, since: Long, limit: Int): List<String>

    /** Mark received messages of a conversation older than [before] as seen. */
    @Query(
        "UPDATE messages SET state = 'SEEN' WHERE conversationId = :conversationId AND isOutgoing = 0 " +
            "AND state = 'DELIVERED' AND timestamp < :before"
    )
    suspend fun markIncomingSeen(conversationId: String, before: Long)

    /**
     * The newest [limit] received messages of a conversation not seen yet, oldest first:
     * what its notification lists.
     */
    @Query(
        "SELECT text, timestamp, mediaKind, mediaName FROM (SELECT plaintext AS text, timestamp, mediaKind, mediaName FROM messages " +
            "WHERE conversationId = :conversationId AND isOutgoing = 0 AND state = 'DELIVERED' " +
            "ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp ASC"
    )
    suspend fun getUnseenIncomingTexts(conversationId: String, limit: Int): List<UnseenText>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND isOutgoing = 0 AND state = 'DELIVERED'")
    suspend fun countUnseenIncoming(conversationId: String): Int

    /** A group's received messages not seen yet since [since], oldest first. */
    @Query(
        "SELECT id, senderId, relayId FROM messages WHERE conversationId = :groupId AND groupId IS NOT NULL " +
            "AND isOutgoing = 0 AND state = 'DELIVERED' AND timestamp >= :since ORDER BY timestamp ASC LIMIT :limit"
    )
    suspend fun getRecentUnseenGroupMessages(groupId: String, since: Long, limit: Int): List<UnseenGroupMessage>

    /** The newest [limit] received group messages not seen yet, oldest first, with their senders. */
    @Query(
        "SELECT senderId, plaintext AS text, timestamp, mediaKind, mediaName FROM (SELECT * FROM messages " +
            "WHERE conversationId = :groupId AND isOutgoing = 0 AND state = 'DELIVERED' ORDER BY timestamp DESC LIMIT :limit) " +
            "ORDER BY timestamp ASC"
    )
    suspend fun getUnseenGroupTexts(groupId: String, limit: Int): List<UnseenGroupText>

    /** Cheap trigger for marking a visible chat read. */
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND isOutgoing = 0 AND state = 'DELIVERED'")
    fun observeUnseenIncomingCount(conversationId: String): Flow<Int>

    /** Our recent sent messages to [recipientId] not yet shown as read. */
    @Query(
        "SELECT id FROM messages WHERE recipientId = :recipientId AND isOutgoing = 1 " +
            "AND state IN ('SENDING', 'SENT', 'DELIVERED', 'FAILED') AND timestamp >= :since"
    )
    suspend fun getUnreadOutgoingIds(recipientId: String, since: Long): List<String>

    @Query("UPDATE messages SET state = 'SEEN' WHERE id IN (:ids)")
    suspend fun markSeen(ids: List<String>)

    /** Our recent sent messages to [recipientId] that no delivery (or read) receipt has covered. */
    @Query(
        "SELECT id FROM messages WHERE recipientId = :recipientId AND isOutgoing = 1 " +
            "AND state IN ('SENDING', 'SENT', 'FAILED') AND timestamp >= :since"
    )
    suspend fun getUndeliveredOutgoingIds(recipientId: String, since: Long): List<String>

    /** The relay says these sent messages reached the recipient's phone (never undoes SEEN). */
    @Query("UPDATE messages SET state = 'DELIVERED' WHERE id IN (:ids) AND isOutgoing = 1 AND state IN ('SENDING', 'SENT', 'FAILED')")
    suspend fun markDelivered(ids: List<String>)

    @Query(
        "UPDATE messages SET state = CASE WHEN state = 'SEEN' OR (isOutgoing = 1 AND state = 'DELIVERED') " +
            "THEN state ELSE :state END, " +
            "timestamp = :timestamp WHERE id = :messageId"
    )
    suspend fun updateStateAndTimestamp(messageId: String, state: String, timestamp: Long)

    @Query("UPDATE messages SET encryptedBody = :encryptedBody, nonce = :nonce WHERE id = :messageId")
    suspend fun updateEncryptedBody(messageId: String, encryptedBody: ByteArray, nonce: ByteArray)

    /** The user's own messages in a chat, newest first: to find the one a reply names by its relay id. */
    @Query("SELECT id FROM messages WHERE conversationId = :conversationId AND isOutgoing = 1 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getOutgoingIds(conversationId: String, limit: Int): List<String>

    @Query("UPDATE messages SET myReaction = :emoji WHERE id = :messageId")
    suspend fun setMyReaction(messageId: String, emoji: String?)

    @Query("UPDATE messages SET theirReaction = :emoji WHERE id = :messageId")
    suspend fun setTheirReaction(messageId: String, emoji: String?)

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getById(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE state = 'FAILED'")
    suspend fun getFailedMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE state = 'SENDING'")
    suspend fun getSendingMessages(): List<MessageEntity>

    /** Our messages written since [since] still being sent: Settings > Diagnostics. */
    @Query("SELECT COUNT(*) FROM messages WHERE isOutgoing = 1 AND state = 'SENDING' AND timestamp >= :since")
    suspend fun countSendingSince(since: Long): Int

    /**
     * Our group messages (not still sending) with a copy the outbox will try again by itself
     * (AutoResend.copyDue): Settings > Diagnostics.
     */
    @Query(
        "SELECT COUNT(DISTINCT o.messageId) FROM group_outbox o JOIN messages m ON m.id = o.messageId " +
            "WHERE o.state = 'failed' AND o.attempts < :maxAttempts AND (o.lastError IS NULL OR o.lastError != 'keys') " +
            "AND o.createdAt >= :since AND m.state != 'SENDING'"
    )
    suspend fun countGroupMessagesRetrying(maxAttempts: Int, since: Long): Int

    /** Our failed one-to-one messages written since [since], oldest first, so they go in order (AutoResend; files and HD photos aren't resent by themselves). */
    @Query("SELECT * FROM messages WHERE isOutgoing = 1 AND state = 'FAILED' AND groupId IS NULL AND timestamp >= :since ORDER BY timestamp")
    suspend fun getFailedOutgoingSince(since: Long): List<MessageEntity>

    /** Moves [messageId] from [from] to [to] only if it's still in [from]; 1 when it did. */
    @Query("UPDATE messages SET state = :to WHERE id = :messageId AND state = :from")
    suspend fun claimState(messageId: String, from: String, to: String): Int

    /** Our group messages still sending (a process death may have stopped them). */
    @Query("SELECT * FROM messages WHERE state = 'SENDING' AND groupId IS NOT NULL AND isOutgoing = 1")
    suspend fun getSendingGroupMessages(): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun delete(messageId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(conversationId: String, beforeTimestamp: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteAllForConversation(conversationId: String)

    /** Message-history retention: delete every message older than [beforeTimestamp]. */
    @Query("DELETE FROM messages WHERE timestamp < :beforeTimestamp")
    suspend fun deleteAllOlderThan(beforeTimestamp: Long): Int

    @Query("DELETE FROM messages WHERE expiresAt IS NOT NULL AND expiresAt < :now")
    suspend fun deleteExpired(now: Long): Int

    /** Every message that has media on disk, for sweeping the files of deleted ones. */
    @Query("SELECT id FROM messages WHERE mediaKind IS NOT NULL")
    suspend fun getMediaMessageIds(): List<String>

    @Query("UPDATE messages SET mediaKind = :kind, mediaMime = :mime, mediaKey = :key, mediaDurationMs = :durationMs, mediaWidth = :width, mediaHeight = :height WHERE id = :messageId")
    suspend fun setMedia(messageId: String, kind: String, mime: String, key: ByteArray, durationMs: Long, width: Int, height: Int)

    @Query("UPDATE messages SET hdKey = NULL, attachmentId = NULL, mediaState = NULL, mediaSize = 0 WHERE id = :messageId")
    suspend fun clearHd(messageId: String)

    /** Our files to [recipientId] that they don't have yet, as far as we know. */
    @Query(
        "SELECT id FROM messages WHERE isOutgoing = 1 AND recipientId = :recipientId AND mediaKind = 'file' " +
            "AND (mediaState IS NULL OR mediaState != 'received')"
    )
    suspend fun getOutgoingFilesNotReceived(recipientId: String): List<String>

    @Query("UPDATE messages SET mediaState = :state WHERE id = :messageId")
    suspend fun setMediaState(messageId: String, state: String)

    @Query("UPDATE messages SET attachmentId = :attachmentId WHERE id = :messageId")
    suspend fun setAttachmentId(messageId: String, attachmentId: String?)

    /** Our own sends whose file transfer a background job owns. */
    @Query("SELECT * FROM messages WHERE state = 'SENDING' AND isOutgoing = 1 AND mediaState = 'uploading'")
    suspend fun getUploadingMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE mediaState = 'downloading'")
    suspend fun getDownloadingMessages(): List<MessageEntity>

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
