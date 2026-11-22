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
import kotlinx.coroutines.flow.combine
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
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
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

    /**
     * The message the composer replies to, by id (saved like the draft); null when none.
     * It goes away by itself if that message does (deleted, or disappeared).
     */
    private val replyingToId: StateFlow<String?> = savedStateHandle.getStateFlow(KEY_REPLY_TO, null)
    val replyingTo: StateFlow<Message?> =
        combine(messages, replyingToId) { list, id -> id?.let { wanted -> list?.firstOrNull { it.id == wanted } } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Reply to [message] with the next one sent. */
    fun startReply(message: Message) {
        savedStateHandle[KEY_REPLY_TO] = message.id
    }

    fun cancelReply() {
        savedStateHandle[KEY_REPLY_TO] = null
    }

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

    /** Remove this contact, and the chat with them, from this phone; [onRemoved] runs after. */
    fun removeContact(onRemoved: () -> Unit) {
        viewModelScope.launch {
            conversationRepository.removeContact(contactId)
            onRemoved()
        }
    }

    /**
     * Delete this chat, its messages and call history, from this phone; [onDeleted] runs after.
     * The contact stays, and a new message from them starts a new chat. A message request
     * is rejected instead, which also forgets the stranger.
     */
    fun deleteChat(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val isRequest = conversationRepository.getConversation(conversationId).first()?.isApproved == false
            if (isRequest) {
                conversationRepository.rejectMessageRequest(conversationId)
            } else {
                conversationRepository.deleteConversation(conversationId)
            }
            onDeleted()
        }
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

    /**
     * Why nothing can be sent in this chat right now, or null when it can. Reads the
     * conversation now rather than the screen's cached copy: it may still be loading
     * (null passed every check), or be gone (a rejected request opened from a stale
     * notification) — sending would then re-add the stranger and message them.
     */
    private suspend fun whyNotSendable(): String? {
        val conv = conversationRepository.getConversation(conversationId).first()
        return when {
            conv == null -> "This conversation no longer exists."
            conversationRepository.observeBlocked(contactId).first() ->
                "You blocked this contact. Unblock them to send messages."
            conv.peerDeleted -> "This user has deleted their account."
            conv.needsSessionReset -> "Peer rotated keys. Tap the banner above to resume sending."
            !conv.isApproved -> "Accept this message request before replying."
            else -> null
        }
    }

    /** File transfers under way, by message id (0..1), for the file bubbles. */
    val fileProgress: StateFlow<Map<String, Float>> = messageRepository.fileProgress

    /** A file decrypted for opening, and its type. */
    class OpenedFile(val file: java.io.File, val mime: String)

    private val _openFile = kotlinx.coroutines.flow.MutableSharedFlow<OpenedFile>(extraBufferCapacity = 1)
    val openFile: kotlinx.coroutines.flow.SharedFlow<OpenedFile> = _openFile

    /** No app opens [file]: the user picked where to save a copy of it. */
    fun saveCopy(file: OpenedFile, target: android.net.Uri) {
        viewModelScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openOutputStream(target)?.use { out -> file.file.inputStream().use { it.copyTo(out) } }
                        ?: error("No output")
                }.isSuccess
            }
            _sendError.value = if (ok) "Saved." else "The file couldn't be saved."
        }
    }

    fun reportError(message: String) {
        _sendError.value = message
    }

    /** Sends any file through the relay's store, with the composer's text as its caption. */
    fun sendFile(uri: android.net.Uri) {
        val replyTo = replyingTo.value
        val caption = draft.trim()
        if (caption.length > SendMessageUseCase.MAX_MESSAGE_LENGTH) {
            _sendError.value = "Caption is too long (max ${SendMessageUseCase.MAX_MESSAGE_LENGTH} characters)."
            return
        }
        viewModelScope.launch {
            val blocked = whyNotSendable()
            if (blocked != null) {
                _sendError.value = blocked
                return@launch
            }
            if (caption.isNotEmpty() && draft.trim() == caption) draft = ""
            if (replyTo != null && replyingTo.value?.id == replyTo.id) cancelReply()
            _sendError.value = null
            try {
                messageRepository.sendFile(conversationId, contactId, uri, caption, replyTo)
            } catch (e: com.sotto.app.data.media.FileTooLargeException) {
                _sendError.value = "Files can be up to ${e.maxBytes / (1024 * 1024)} MB."
            } catch (e: com.sotto.app.data.remote.ApiHttpException) {
                _sendError.value = when {
                    // An older relay has no such endpoint (404); one without storage says so (503).
                    e.status == 404 || e.status == 503 -> "This relay doesn't take files."
                    else -> when (e.message) {
                    "attachment_monthly_limit" -> "You've sent as much as this relay allows this month."
                    "attachment_store_full", "attachment_relay_monthly_limit" -> "The relay's file storage is full for now."
                    "attachment_too_large" -> "That file is too large for this relay."
                    else -> "Failed to send file"
                    }
                }
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Sending file failed: ${e.message}")
                _sendError.value = "Failed to send file"
            }
        }
    }

    /** A file's bubble was tapped: download it, or open it once it's on this phone. */
    fun onFileTap(message: Message) {
        val media = message.media ?: return
        viewModelScope.launch {
            try {
                if (media.kind == com.sotto.app.domain.model.MediaKind.PHOTO) {
                    // An HD photo's full-size copy: fetch it; the viewer shows it once it's here.
                    if (media.fileState == com.sotto.app.domain.model.FileState.REMOTE) messageRepository.downloadFile(message.id)
                    return@launch
                }
                if (media.fileState == com.sotto.app.domain.model.FileState.REMOTE) {
                    messageRepository.downloadFile(message.id)
                    return@launch
                }
                val file = messageRepository.decryptFileForOpening(message.id)
                if (file == null) {
                    _sendError.value = "This file isn't on this phone."
                    return@launch
                }
                _openFile.tryEmit(OpenedFile(file, media.mime.ifBlank { "application/octet-stream" }))
            } catch (e: Exception) {
                Log.w("ChatViewModel", "File failed: ${e.message}")
                _sendError.value = "The file couldn't be downloaded. Try again."
            }
        }
    }

    /** The decrypted voice message or photo of [messageId], for playing or showing it. */
    suspend fun loadMedia(messageId: String): ByteArray? = messageRepository.loadMedia(messageId)

    /** An HD photo's full-size copy, once it's on this phone. */
    suspend fun loadHd(messageId: String): ByteArray? = messageRepository.loadHd(messageId)

    /** Sends a recorded voice message, replying to the message being replied to, if any. */
    fun sendVoice(recording: com.sotto.app.media.VoiceRecording) {
        val replyTo = replyingTo.value
        viewModelScope.launch {
            val bytes = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { recording.file.readBytes() }
            } finally {
                recording.file.delete()
            }
            sendMediaNow(
                com.sotto.app.domain.model.MessageMedia(
                    com.sotto.app.domain.model.MediaKind.VOICE, recording.mime, durationMs = recording.durationMs,
                ),
                bytes, caption = "", replyTo = replyTo,
            )
        }
    }

    /** Shrinks the photo at [uri] and sends it, with the composer's text as its caption. */
    fun sendPhoto(uri: android.net.Uri, hd: Boolean = false) {
        val replyTo = replyingTo.value
        val caption = draft.trim()
        if (caption.length > SendMessageUseCase.MAX_MESSAGE_LENGTH) {
            _sendError.value = "Caption is too long (max ${SendMessageUseCase.MAX_MESSAGE_LENGTH} characters)."
            return
        }
        viewModelScope.launch {
            val photo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { com.sotto.app.media.PhotoShrinker.shrink(appContext, uri) }.getOrNull()
            }
            if (photo == null) {
                _sendError.value = "That picture couldn't be opened."
                return@launch
            }
            // HD: also a full-size copy, through the relay's store. Not for a picture that's
            // no bigger than the normal one anyway.
            val full = if (hd) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching {
                        com.sotto.app.media.PhotoShrinker.shrink(
                            appContext, uri,
                            maxBytes = com.sotto.app.domain.model.MediaPayload.HD_MAX_BYTES.toInt(),
                            maxSide = com.sotto.app.media.PhotoShrinker.HD_SIDE,
                            qualities = com.sotto.app.media.PhotoShrinker.HD_QUALITIES,
                        )
                    }.getOrNull()
                }?.takeIf { maxOf(it.width, it.height) > maxOf(photo.width, photo.height) }
            } else {
                null
            }
            val sent = sendMediaNow(
                com.sotto.app.domain.model.MessageMedia(
                    com.sotto.app.domain.model.MediaKind.PHOTO, "image/jpeg", width = photo.width, height = photo.height,
                ),
                photo.bytes, caption = caption, replyTo = replyTo, hdBytes = full?.bytes,
            )
            // The caption went with it.
            if (sent && caption.isNotEmpty() && draft.trim() == caption) draft = ""
        }
    }

    private suspend fun sendMediaNow(
        media: com.sotto.app.domain.model.MessageMedia,
        bytes: ByteArray,
        caption: String,
        replyTo: Message?,
        hdBytes: ByteArray? = null,
    ): Boolean {
        val blocked = whyNotSendable()
        if (blocked != null) {
            _sendError.value = blocked
            return false
        }
        if (replyTo != null && replyingTo.value?.id == replyTo.id) cancelReply()
        _sendError.value = null
        return try {
            messageRepository.sendMedia(conversationId, contactId, media, bytes, caption, replyTo, hdBytes)
            true
        } catch (e: com.sotto.app.data.remote.ApiHttpException) {
            Log.w("ChatViewModel", "HD copy refused: ${e.message}")
            _sendError.value = if (e.status == 404 || e.status == 503) {
                "This relay doesn't take HD photos. Send it as a normal photo."
            } else {
                "The HD copy couldn't be sent. Tap the photo to try again."
            }
            false
        } catch (e: Exception) {
            Log.w("ChatViewModel", "Sending media failed: ${e.message}")
            // The message stays in the chat as "Not delivered · Tap to retry" if it got that far.
            _sendError.value = "Failed to send ${if (media.kind == com.sotto.app.domain.model.MediaKind.VOICE) "voice message" else "photo"}"
            false
        }
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

        // The reply shown when Send was tapped, not one picked while the checks below run.
        val replyTo = replyingTo.value
        viewModelScope.launch {
            val blocked = whyNotSendable()
            if (blocked != null) {
                _sendError.value = blocked
                return@launch
            }
            if (draft.trim() == text) {
                draft = ""
                if (replyingTo.value?.id == replyTo?.id) cancelReply()
            }
            _sendError.value = null
            sendMessage(conversationId, contactId, text, replyTo)
                .onFailure { e ->
                    _sendError.value = "Failed to send message"
                }
        }
    }

    /**
     * React to [message] with [emoji]; picking the reaction it already has takes it back.
     * Checked like sending: not to a blocked contact, an unanswered request, and so on.
     */
    fun react(message: Message, emoji: String) {
        if (!message.canReact) return
        val next = emoji.takeUnless { it == message.myReaction }
        viewModelScope.launch {
            val conv = conversationRepository.getConversation(conversationId).first()
            val blocked = when {
                conv == null -> "This conversation no longer exists."
                conversationRepository.observeBlocked(contactId).first() ->
                    "You blocked this contact. Unblock them to react."
                conv.peerDeleted -> "This user has deleted their account."
                conv.needsSessionReset -> "Peer rotated keys. Tap the banner above to resume sending."
                !conv.isApproved -> "Accept this message request before reacting."
                else -> null
            }
            if (blocked != null) {
                _sendError.value = blocked
                return@launch
            }
            try {
                messageRepository.sendReaction(message, contactId, next)
            } catch (e: Exception) {
                _sendError.value = "Couldn't send the reaction. Check your connection and try again."
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
        const val KEY_REPLY_TO = "composer_reply_to"
    }
}

