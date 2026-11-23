package com.sotto.app.ui.conversations

import com.sotto.app.update.AppUpdate
import com.sotto.app.update.AppUpdates
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sotto.app.domain.model.Conversation
import com.sotto.app.domain.repository.ConversationRepository
import com.sotto.app.domain.repository.IdentityRepository
import com.sotto.app.domain.repository.MessageRepository
import com.sotto.app.domain.usecase.SendMyNameUseCase
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import com.sotto.app.domain.model.trust
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val identityRepository: IdentityRepository,
    private val messageRepository: MessageRepository,
    private val sendMyName: SendMyNameUseCase,
    appUpdates: AppUpdates,
) : ViewModel() {

    /** Whether the relay runs a newer Sotto, and whether that one is out yet. */
    val appUpdate: StateFlow<AppUpdate> = appUpdates.state

    val conversations: StateFlow<List<Conversation>> =
        conversationRepository.getConversations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Each contact's verification, by user id, for the shield on their chat. */
    val trust: StateFlow<Map<String, com.sotto.app.domain.model.ContactTrust>> =
        identityRepository.getContacts()
            .map { contacts -> contacts.associate { it.userId to it.trust } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val messageRequests: StateFlow<List<Conversation>> =
        conversationRepository.getMessageRequests()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _shareCode = MutableStateFlow<String?>(null)
    val shareCode: StateFlow<String?> = _shareCode.asStateFlow()

    init {
        viewModelScope.launch {
            val identity = identityRepository.getIdentity()
            _shareCode.value = identity?.shareCode
        }
    }

    /**
     * Poll for new messages; the screen runs this only while it is resumed.
     * WebSocket and FCM provide real-time delivery when available; this poll
     * covers emulators and unreliable push environments.
     */
    suspend fun pollWhileVisible() {
        while (true) {
            try {
                messageRepository.fetchPendingFromServer()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ConversationsVM", "Periodic message fetch failed: ${e.message}")
            }
            kotlinx.coroutines.delay(10_000)
        }
    }

    fun acceptMessageRequest(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.approveConversation(conversationId)
            // Accepting connects them: send the user's name so they see it instead of an id.
            conversationRepository.getConversation(conversationId).first()?.let { sendMyName(it.contactId) }
        }
    }

    fun blockRequest(contactId: String, displayName: String) {
        viewModelScope.launch {
            conversationRepository.blockUser(contactId, displayName)
        }
    }

    fun rejectMessageRequest(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.rejectMessageRequest(conversationId)
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.deleteConversation(conversationId)
        }
    }
}
