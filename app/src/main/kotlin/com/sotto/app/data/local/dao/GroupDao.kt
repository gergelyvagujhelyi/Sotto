package com.sotto.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.sotto.app.data.local.entity.GroupEntity
import com.sotto.app.data.local.entity.GroupHeldEntity
import com.sotto.app.data.local.entity.GroupMemberEntity
import com.sotto.app.data.local.entity.GroupOutboxEntity
import com.sotto.app.data.local.entity.GroupReactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {

    // ── Groups ────────────────────────────────────────────────────────────

    /** Groups for the chat list: every one but open invitations and declined ones. */
    @Query("SELECT * FROM groups WHERE status NOT IN ('invited', 'rejected') ORDER BY COALESCE(lastMessageTimestamp, createdAt) DESC")
    fun observeChats(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE status = 'invited' ORDER BY createdAt DESC")
    fun observeInvitations(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE id = :id")
    fun observe(id: String): Flow<GroupEntity?>

    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun get(id: String): GroupEntity?

    @Query("SELECT * FROM groups")
    suspend fun getAll(): List<GroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(group: GroupEntity)

    @Query("UPDATE groups SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("UPDATE groups SET frozen = 1 WHERE id = :id")
    suspend fun freeze(id: String)

    @Query(
        "UPDATE groups SET lastMessagePreview = :preview, lastMessageTimestamp = :timestamp, " +
            "unreadCount = unreadCount + :unreadDelta WHERE id = :id",
    )
    suspend fun updatePreview(id: String, preview: String, timestamp: Long, unreadDelta: Int)

    @Query("UPDATE groups SET unreadCount = 0 WHERE id = :id")
    suspend fun clearUnread(id: String)

    /** Drop previews whose message is gone (it disappeared): as for 1:1 chats, no plaintext stays in the list. */
    @Query(
        "UPDATE groups SET lastMessagePreview = NULL " +
            "WHERE lastMessagePreview IS NOT NULL AND NOT EXISTS (" +
            "SELECT 1 FROM messages WHERE messages.conversationId = groups.id " +
            "AND messages.timestamp = groups.lastMessageTimestamp)",
    )
    suspend fun clearPreviewsWithoutMessage()

    @Query("DELETE FROM groups WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM groups")
    suspend fun deleteAll()

    // ── Members ───────────────────────────────────────────────────────────

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY role ASC, name COLLATE NOCASE ASC, userId ASC")
    fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun getMembers(groupId: String): List<GroupMemberEntity>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND userId = :userId")
    suspend fun getMember(groupId: String, userId: String): GroupMemberEntity?

    @Query("SELECT * FROM group_members WHERE userId = :userId")
    suspend fun getMemberships(userId: String): List<GroupMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(members: List<GroupMemberEntity>)

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun deleteMembers(groupId: String)

    @Transaction
    suspend fun replaceMembers(groupId: String, members: List<GroupMemberEntity>) {
        deleteMembers(groupId)
        upsertMembers(members)
    }

    @Query("UPDATE group_members SET keyState = :keyState WHERE groupId = :groupId AND userId = :userId")
    suspend fun setKeyState(groupId: String, userId: String, keyState: String)

    @Query("UPDATE group_members SET status = 'gone' WHERE userId = :userId")
    suspend fun markGoneEverywhere(userId: String)

    @Query("UPDATE group_members SET status = 'gone' WHERE groupId = :groupId AND userId = :userId")
    suspend fun markGone(groupId: String, userId: String)

    @Query("SELECT COUNT(*) FROM group_members WHERE groupId = :groupId AND status = 'active'")
    fun observeActiveCount(groupId: String): Flow<Int>

    /** Emits whenever any member row changes (for lists that show member counts). */
    @Query("SELECT COUNT(*) FROM group_members WHERE status = 'active'")
    fun observeMemberChanges(): Flow<Int>

    // ── Outbox ────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCopies(copies: List<GroupOutboxEntity>)

    @Query("SELECT * FROM group_outbox WHERE id = :id")
    suspend fun getCopy(id: String): GroupOutboxEntity?

    @Query("SELECT * FROM group_outbox WHERE messageId = :messageId")
    suspend fun getCopiesForMessage(messageId: String): List<GroupOutboxEntity>

    @Query("SELECT * FROM group_outbox WHERE messageId = :messageId")
    fun observeCopiesForMessage(messageId: String): Flow<List<GroupOutboxEntity>>

    /** For each of our messages in [groupId]: how many members it goes to, how many have it, how many read it. */
    @Query(
        "SELECT messageId, COUNT(*) AS total, " +
            "SUM(CASE WHEN state IN ('sent', 'delivered', 'read') THEN 1 ELSE 0 END) AS sent, " +
            "SUM(CASE WHEN state IN ('delivered', 'read') THEN 1 ELSE 0 END) AS delivered, " +
            "SUM(CASE WHEN state = 'read' THEN 1 ELSE 0 END) AS seen " +
            "FROM group_outbox WHERE groupId = :groupId AND messageId IS NOT NULL AND state != 'skipped' GROUP BY messageId"
    )
    fun observeProgress(groupId: String): Flow<List<GroupCopyProgressRow>>

    /** Copies of control packets (rosters, invitations, answers) for [groupId], newest first. */
    @Query("SELECT * FROM group_outbox WHERE groupId = :groupId AND messageId IS NULL ORDER BY createdAt DESC")
    fun observeControlCopies(groupId: String): Flow<List<GroupOutboxEntity>>

    @Query("SELECT * FROM group_outbox WHERE state IN (:states) ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getByStates(states: List<String>, limit: Int): List<GroupOutboxEntity>

    @Query("SELECT * FROM group_outbox WHERE recipientId = :recipientId AND relayId IN (:relayIds)")
    suspend fun findByRelayIds(recipientId: String, relayIds: List<String>): List<GroupOutboxEntity>

    @Query(
        "UPDATE group_outbox SET ciphertext = :ciphertext, nonce = :nonce, signature = :signature, " +
            "pqcSignature = :pqcSignature, packet = NULL, state = 'sealed', updatedAt = :now WHERE id = :id",
    )
    suspend fun setSealed(id: String, ciphertext: ByteArray, nonce: ByteArray, signature: String, pqcSignature: String, now: Long)

    /** The relay has it: the sealed bytes aren't needed any more. */
    @Query(
        "UPDATE group_outbox SET state = 'sent', ciphertext = NULL, nonce = NULL, signature = NULL, " +
            "pqcSignature = NULL, packet = NULL, lastError = NULL, updatedAt = :now WHERE id = :id",
    )
    suspend fun setSent(id: String, now: Long)

    @Query("UPDATE group_outbox SET state = :state, attempts = attempts + 1, lastError = :error, updatedAt = :now WHERE id = :id")
    suspend fun setFailed(id: String, state: String, error: String?, now: Long)

    @Query("UPDATE group_outbox SET state = :state, updatedAt = :now WHERE id = :id")
    suspend fun setState(id: String, state: String, now: Long)

    @Query("DELETE FROM group_outbox WHERE groupId = :groupId")
    suspend fun deleteCopiesForGroup(groupId: String)

    @Query("DELETE FROM group_outbox WHERE messageId = :messageId")
    suspend fun deleteCopiesForMessage(messageId: String)

    /** Finished control copies older than [before] (sent ones; failed ones stay to be seen). */
    @Query("DELETE FROM group_outbox WHERE messageId IS NULL AND state IN ('sent', 'delivered', 'read', 'skipped') AND updatedAt < :before")
    suspend fun pruneControlCopies(before: Long)

    /** Copies of messages that are gone (deleted, disappeared). */
    @Query("DELETE FROM group_outbox WHERE messageId IS NOT NULL AND messageId NOT IN (SELECT id FROM messages)")
    suspend fun pruneOrphanCopies()

    // ── Reactions ─────────────────────────────────────────────────────────

    @Query(
        "SELECT r.* FROM group_reactions r INNER JOIN messages m ON m.id = r.messageId " +
            "WHERE m.conversationId = :groupId",
    )
    fun observeReactions(groupId: String): Flow<List<GroupReactionEntity>>

    @Query("SELECT * FROM group_reactions WHERE messageId = :messageId")
    suspend fun getReactions(messageId: String): List<GroupReactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReaction(reaction: GroupReactionEntity)

    @Query("DELETE FROM group_reactions WHERE messageId = :messageId AND reactorId = :reactorId")
    suspend fun deleteReaction(messageId: String, reactorId: String)

    @Query("DELETE FROM group_reactions WHERE messageId NOT IN (SELECT id FROM messages)")
    suspend fun pruneOrphanReactions()

    // ── Held packets ──────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun hold(held: GroupHeldEntity)

    @Query("SELECT * FROM group_held WHERE groupId = :groupId ORDER BY receivedAt ASC")
    suspend fun getHeld(groupId: String): List<GroupHeldEntity>

    @Query("DELETE FROM group_held WHERE relayId = :relayId")
    suspend fun deleteHeld(relayId: String)

    @Query("DELETE FROM group_held WHERE groupId = :groupId")
    suspend fun deleteHeldForGroup(groupId: String)

    @Query("DELETE FROM group_held WHERE receivedAt < :before")
    suspend fun pruneHeld(before: Long)
}

/** One of our group messages: [total] members it goes to, [sent] whose copy the relay took, [delivered] whose phones have it, [seen] who read it. */
data class GroupCopyProgressRow(val messageId: String, val total: Int, val sent: Int, val delivered: Int, val seen: Int)
