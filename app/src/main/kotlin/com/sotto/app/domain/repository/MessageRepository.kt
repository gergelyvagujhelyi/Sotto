package com.sotto.app.domain.repository

import com.sotto.app.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun getMessages(conversationId: String): Flow<List<Message>>
    /** Send [plaintext]; with [replyTo], as a reply to that message of this chat. */
    suspend fun sendMessage(conversationId: String, recipientId: String, plaintext: String, replyTo: Message? = null): Message

    /** Sends a voice message or photo inline (ADR-29), with an optional [caption]. */
    suspend fun sendMedia(
        conversationId: String,
        recipientId: String,
        media: com.sotto.app.domain.model.MessageMedia,
        bytes: ByteArray,
        caption: String = "",
        replyTo: Message? = null,
    ): Message
    /**
     * React to [message] with [emoji] (null takes the reaction back), and tell its chat's
     * contact. Shown at once; undone if it can't be sent.
     */
    suspend fun sendReaction(message: Message, recipientId: String, emoji: String?)
    /**
     * Send the user's own [name] to [recipientId], end-to-end encrypted like a message.
     * Nothing is stored or shown on this side.
     */
    suspend fun sendProfile(recipientId: String, name: String)
    suspend fun markDelivered(messageId: String)
    suspend fun markSeen(messageId: String)
    suspend fun retryFailed(messageId: String)
    suspend fun deleteMessage(messageId: String)

    /** The decrypted media of [messageId] (voice or photo), or null when it has none or it's gone. */
    suspend fun loadMedia(messageId: String): ByteArray?
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
