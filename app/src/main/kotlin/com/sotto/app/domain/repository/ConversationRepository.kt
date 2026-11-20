package com.sotto.app.domain.repository

import com.sotto.app.domain.model.Conversation
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun getConversations(): Flow<List<Conversation>>
    fun getMessageRequests(): Flow<List<Conversation>>
    fun getConversation(conversationId: String): Flow<Conversation?>
    suspend fun getOrCreateConversation(contactId: String): String
    suspend fun approveConversation(conversationId: String)
    suspend fun clearUnread(conversationId: String)
    suspend fun deleteConversation(conversationId: String)
    /**
     * Reject a message request: delete the conversation, and forget the sender if
     * they only became a contact by messaging us (unverified, trust-on-first-use).
     */
    suspend fun rejectMessageRequest(conversationId: String)

    /**
     * Block [userId]: their messages and calls are dropped from now on. A pending
     * message request from them is rejected; an existing chat keeps its history.
     */
    suspend fun blockUser(userId: String, displayName: String)
    suspend fun unblockUser(userId: String)
    fun observeBlocked(userId: String): Flow<Boolean>
    fun getBlockedUsers(): Flow<List<com.sotto.app.domain.model.BlockedUser>>
}
