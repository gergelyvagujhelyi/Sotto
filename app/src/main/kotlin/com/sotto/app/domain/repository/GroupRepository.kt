package com.sotto.app.domain.repository

import com.sotto.app.domain.model.Group
import com.sotto.app.domain.model.GroupCopy
import com.sotto.app.domain.model.GroupMember
import com.sotto.app.domain.model.GroupReaction
import com.sotto.app.domain.model.Message
import com.sotto.app.domain.model.MessageMedia
import kotlinx.coroutines.flow.Flow

/**
 * Group chats (ADR-32): every message goes to each member as its own pairwise end-to-end
 * encrypted envelope; the admin's signed roster says who's in. The relay sees ordinary
 * one-to-one envelopes only.
 */
interface GroupRepository {
    fun observeGroups(): Flow<List<Group>>
    fun observeInvitations(): Flow<List<Group>>
    fun observeGroup(groupId: String): Flow<Group?>
    fun observeMembers(groupId: String): Flow<List<GroupMember>>
    fun observeMessages(groupId: String): Flow<List<Message>>
    /** Reactions in [groupId], by message id. */
    fun observeReactions(groupId: String): Flow<Map<String, List<GroupReaction>>>
    /** Where each member's copy of our message [messageId] is. */
    fun observeCopies(messageId: String): Flow<List<GroupCopy>>

    /** How far each of our messages in [groupId] got, by message id (the ticks' "1/2"). */
    fun observeProgress(groupId: String): Flow<Map<String, com.sotto.app.domain.model.GroupProgress>> =
        kotlinx.coroutines.flow.flowOf(emptyMap())

    /**
     * Who has read how far in [groupId]: by message id, the members for whom it's the latest of
     * our messages they read (where their little read circle sits, as in Messenger).
     */
    fun observeReadUpTo(groupId: String): Flow<Map<String, List<String>>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    /** Where each member's copy of the latest roster or invitation is (for the admin). */
    fun observeRosterDelivery(groupId: String): Flow<List<GroupCopy>>

    /** A new group named [title] with the user as its admin; [memberIds] are invited. Returns its id. */
    suspend fun createGroup(title: String, memberIds: List<String>): String

    suspend fun sendText(groupId: String, text: String, replyTo: Message? = null)
    suspend fun sendMedia(groupId: String, media: MessageMedia, bytes: ByteArray, caption: String, replyTo: Message?, hdBytes: ByteArray? = null)
    suspend fun sendFile(groupId: String, uri: android.net.Uri, caption: String, replyTo: Message?)
    suspend fun react(groupId: String, message: Message, emoji: String?)
    /** Unsends our group message [messageId] for every member (ADR-36); see MessageRepository.unsend. */
    suspend fun unsend(messageId: String)
    /** Sends the copies of our message [messageId] that failed again (never the ones that went). */
    suspend fun retry(messageId: String)

    suspend fun acceptInvitation(groupId: String)
    suspend fun rejectInvitation(groupId: String)
    suspend fun leave(groupId: String)
    /** Removes a group the user is no longer in (or an ended one) and its messages from this phone. */
    suspend fun deleteLocally(groupId: String)

    // Admin only.
    suspend fun rename(groupId: String, title: String)
    suspend fun setTimer(groupId: String, timerMs: Long)
    suspend fun invite(groupId: String, memberIds: List<String>)
    suspend fun remove(groupId: String, memberId: String)
    suspend fun makeAdmin(groupId: String, memberId: String)
    suspend fun deleteGroup(groupId: String)
    /** Before the account is deleted: every group the user runs goes to another member. Returns how many. */
    suspend fun handOverAll(): Int
    /** Groups the user is the admin of and that have other members. */
    suspend fun groupsToHandOver(): List<Group>

    /** The group chat on screen (no notification or unread for it), or null. */
    fun setActiveGroup(groupId: String?)
    suspend fun markRead(groupId: String)

    /** Sends whatever copies are queued or were interrupted, and retries control packets. Returns whether some still wait. */
    suspend fun flushOutbox(): Boolean
}
