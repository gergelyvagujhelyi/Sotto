package com.sotto.app.domain.repository

import com.sotto.app.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun getMessages(conversationId: String): Flow<List<Message>>
    /** Send [plaintext]; with [replyTo], as a reply to that message of this chat. */
    suspend fun sendMessage(conversationId: String, recipientId: String, plaintext: String, replyTo: Message? = null): Message
    /**
     * Send the user's own [name] to [recipientId], end-to-end encrypted like a message.
     * Nothing is stored or shown on this side.
     */
    suspend fun sendProfile(recipientId: String, name: String)
    suspend fun markDelivered(messageId: String)
    suspend fun markSeen(messageId: String)
    suspend fun retryFailed(messageId: String)
    suspend fun deleteMessage(messageId: String)
    suspend fun deleteMessagesOlderThan(conversationId: String, timestampMillis: Long)
    /** Fetch pending messages; when a fetch is running, make it fetch once more and return at once. */
    suspend fun fetchPendingFromServer()
    /**
     * Fetch pending messages, waiting for a running fetch first rather than returning early.
     * For background work that must not end before the messages are in (a push's job).
     * Throws when the fetch fails.
     */
    suspend fun fetchPendingAndWait()
    suspend fun reEstablishSession(contactId: String)
    /** Set the conversation the user is currently viewing. Messages arriving for
     *  this conversation will not increment the unread counter. Pass null on exit. */
    fun setActiveConversation(conversationId: String?)
    suspend fun failInterruptedSends()
    /** Clear the active conversation only if it is still [conversationId]. */
    fun clearActiveConversation(conversationId: String)
    /** Delete messages whose disappearing-message timer ran out. */
    suspend fun deleteExpiredMessages()
}
