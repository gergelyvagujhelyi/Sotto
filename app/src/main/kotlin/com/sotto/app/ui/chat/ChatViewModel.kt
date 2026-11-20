package com.sotto.app.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sotto.app.domain.model.Contact
import com.sotto.app.domain.model.Conversation
import com.sotto.app.domain.model.Message
import com.sotto.app.domain.repository.ConversationRepository
import com.sotto.app.domain.repository.IdentityRepository
import com.sotto.app.domain.usecase.SendMessageUseCase
import com.sotto.app.domain.usecase.SendMyNameUseCase
import com.sotto.app.domain.repository.MessageRepository
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val identityRepository: IdentityRepository,
    private val sendMessage: SendMessageUseCase,
    private val receiptManager: com.sotto.app.data.repository.ReceiptManager,
    private val sendMyName: SendMyNameUseCase,
) : ViewModel() {

    val conversationId: String = savedStateHandle["conversationId"] ?: ""
    val contactId: String = savedStateHandle["contactId"] ?: ""

    /** Null until the database has answered, so an empty list really means no messages. */
    val messages: StateFlow<List<Message>?> =
        messageRepository.getMessages(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val conversation: StateFlow<Conversation?> =
        conversationRepository.getConversation(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val encryptionInfo: StateFlow<String> =
        identityRepository.observePqcSession(contactId)
            .map { isPqc ->
                if (isPqc) "ML-KEM-768 + X25519 + AES-256-GCM"
                else "X25519 + AES-256-GCM"
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                if (identityRepository.isPqcSession(contactId)) "ML-KEM-768 + X25519 + AES-256-GCM"
                else "X25519 + AES-256-GCM",
            )

    // In SavedStateHandle so an unsent draft survives process death.
    val composerText: StateFlow<String> = savedStateHandle.getStateFlow(KEY_DRAFT, "")
    private var draft: String
        get() = composerText.value
        set(value) { savedStateHandle[KEY_DRAFT] = value }

    val isBlocked: StateFlow<Boolean> =
        conversationRepository.observeBlocked(contactId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** The contact behind this chat, for the names the rename dialog shows. */
    val contact: StateFlow<Contact?> =
        identityRepository.observeContact(contactId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Give the contact a name on this phone; blank clears it. */
    fun rename(nickname: String) {
        viewModelScope.launch { identityRepository.renameContact(contactId, nickname) }
    }

    /** Block this contact; [onRemoved] runs if that removed a message request. */
    fun block(onRemoved: () -> Unit) {
        viewModelScope.launch {
            val wasRequest = conversationRepository.getConversation(conversationId).first()?.isApproved == false
            conversationRepository.blockUser(
                contactId,
                conversation.value?.contactDisplayName ?: contactId.take(8),
            )
            if (wasRequest) onRemoved()
        }
    }

    fun unblock() {
        viewModelScope.launch { conversationRepository.unblockUser(contactId) }
    }

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    private val _isReEstablishing = MutableStateFlow(false)
    val isReEstablishing: StateFlow<Boolean> = _isReEstablishing.asStateFlow()

    /**
     * Poll for new messages; the screen runs this only while it is resumed
     * (supplements WebSocket/FCM; a loop in viewModelScope kept polling in the
     * background and under other screens).
     */
    suspend fun pollWhileVisible() {
        while (true) {
            try {
                messageRepository.fetchPendingFromServer()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Periodic message fetch failed: ${e.message}")
            }
            kotlinx.coroutines.delay(15_000)
        }
    }

    /**
     * Called when the chat screen is resumed / paused. Only a visible chat counts as
     * active: otherwise its incoming messages would get no notification or unread
     * badge while the app is in the background.
     */
    fun onScreenResumed() {
        resumed = true
        messageRepository.setActiveConversation(conversationId)
        viewModelScope.launch {
            conversationRepository.clearUnread(conversationId)
        }
        markRead()
    }

    fun onScreenPaused() {
        resumed = false
        messageRepository.clearActiveConversation(conversationId)
    }

    /** Messages count as read only while the chat is actually on screen. */
    @Volatile
    private var resumed = false

    init {
        // New messages arriving while the chat is open are read right away.
        // (A count query: cheap, and only re-emits when it changes.)
        viewModelScope.launch {
            receiptManager.observeUnseen(conversationId).collect { if (resumed && it > 0) markRead() }
        }
    }

    private fun markRead() {
        viewModelScope.launch {
            try {
                receiptManager.markConversationRead(conversationId)
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Marking messages read failed: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        messageRepository.clearActiveConversation(conversationId)
    }

    fun updateComposer(text: String) {
        draft = text
    }

    fun send() {
        val text = draft.trim()
        if (text.isBlank()) return
        if (text.length > SendMessageUseCase.MAX_MESSAGE_LENGTH) {
            // Checked before clearing the composer: the use case rejects it before
            // anything is stored, so the text would otherwise just vanish.
            _sendError.value = "Message is too long (max ${SendMessageUseCase.MAX_MESSAGE_LENGTH} characters)."
            return
        }

        viewModelScope.launch {
            // Read the conversation now rather than the screen's cached copy: it may
            // still be loading (null passed every check), or be gone (a rejected
            // request opened from a stale notification) — sending would then
            // re-add the stranger and message them.
            val conv = conversationRepository.getConversation(conversationId).first()
            val blocked = when {
                conv == null -> "This conversation no longer exists."
                conversationRepository.observeBlocked(contactId).first() ->
                    "You blocked this contact. Unblock them to send messages."
                conv.peerDeleted -> "This user has deleted their account."
                conv.needsSessionReset -> "Peer rotated keys. Tap the banner above to resume sending."
                !conv.isApproved -> "Accept this message request before replying."
                else -> null
            }
            if (blocked != null) {
                _sendError.value = blocked
                return@launch
            }
            if (draft.trim() == text) draft = ""
            _sendError.value = null
            sendMessage(conversationId, contactId, text)
                .onFailure { e ->
                    _sendError.value = "Failed to send message"
                }
        }
    }

    fun reEstablishSession() {
        _isReEstablishing.value = true
        _sendError.value = null
        viewModelScope.launch {
            try {
                messageRepository.reEstablishSession(contactId)
            } catch (e: Exception) {
                _sendError.value = "Failed to re-establish session. Try again."
            } finally {
                _isReEstablishing.value = false
            }
        }
    }

    fun retrySend(messageId: String) {
        viewModelScope.launch {
            try {
                messageRepository.retryFailed(messageId)
            } catch (e: Exception) {
                _sendError.value = "Retry failed"
            }
        }
    }

    fun acceptMessageRequest() {
        viewModelScope.launch {
            conversationRepository.approveConversation(conversationId)
            // Accepting connects them: send the user's name so they see it instead of an id.
            sendMyName(contactId)
        }
    }

    fun rejectMessageRequest(onRejected: () -> Unit) {
        viewModelScope.launch {
            conversationRepository.rejectMessageRequest(conversationId)
            onRejected()
        }
    }

    fun clearError() {
        _sendError.value = null
    }

    private companion object {
        const val KEY_DRAFT = "composer_draft"
    }
}

