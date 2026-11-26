package com.sotto.app.ui.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sotto.app.R
import com.sotto.app.domain.model.Group
import com.sotto.app.domain.model.GroupCopy
import com.sotto.app.domain.model.GroupMember
import com.sotto.app.domain.model.GroupReaction
import com.sotto.app.domain.model.GroupStatus
import com.sotto.app.domain.model.MediaKind
import com.sotto.app.domain.model.MediaPayload
import com.sotto.app.domain.model.Message
import com.sotto.app.domain.model.MessageMedia
import com.sotto.app.domain.repository.GroupRepository
import com.sotto.app.domain.repository.MessageRepository
import com.sotto.app.domain.usecase.SendMessageUseCase
import com.sotto.app.ui.text.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A group chat (ADR-32): its messages, members and reactions, and sending in it. */
@HiltViewModel
class GroupChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val groups: GroupRepository,
    private val messageRepository: MessageRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val webSocketClient: com.sotto.app.data.remote.WebSocketClient,
) : ViewModel() {

    val groupId: String = savedStateHandle["groupId"] ?: ""

    val group: StateFlow<Group?> = groups.observeGroup(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Null until the database has answered. */
    val messages: StateFlow<List<Message>?> = groups.observeMessages(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val members: StateFlow<List<GroupMember>> = groups.observeMembers(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reactions: StateFlow<Map<String, List<GroupReaction>>> = groups.observeReactions(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** How far each of our messages got (the ticks' "1/2"), by message id. */
    val progress: StateFlow<Map<String, com.sotto.app.domain.model.GroupProgress>> = groups.observeProgress(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val composerText: StateFlow<String> = savedStateHandle.getStateFlow(KEY_DRAFT, "")
    private var draft: String
        get() = composerText.value
        set(value) { savedStateHandle[KEY_DRAFT] = value }

    private val replyingToId: StateFlow<String?> = savedStateHandle.getStateFlow(KEY_REPLY_TO, null)
    val replyingTo: StateFlow<Message?> =
        combine(messages, replyingToId) { list, id -> id?.let { wanted -> list?.firstOrNull { it.id == wanted } } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _error = MutableStateFlow<UiText?>(null)
    val error: StateFlow<UiText?> = _error.asStateFlow()

    /** The message whose delivery sheet is open, and its copies. */
    private val _infoFor = MutableStateFlow<String?>(null)
    val infoFor: StateFlow<String?> = _infoFor.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val infoCopies: StateFlow<List<GroupCopy>> = _infoFor
        .flatMapLatest { id -> if (id == null) emptyFlow() else groups.observeCopies(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val fileProgress: StateFlow<Map<String, Float>> = messageRepository.fileProgress

    private val _openFile = MutableSharedFlow<ChatViewModel.OpenedFile>(extraBufferCapacity = 1)
    val openFile: SharedFlow<ChatViewModel.OpenedFile> = _openFile

    fun updateComposer(text: String) {
        draft = text
    }

    fun startReply(message: Message) {
        savedStateHandle[KEY_REPLY_TO] = message.id
    }

    fun cancelReply() {
        savedStateHandle[KEY_REPLY_TO] = null
    }

    fun clearError() {
        _error.value = null
    }

    fun reportError(message: UiText) {
        _error.value = message
    }

    fun showInfo(message: Message) {
        _infoFor.value = message.id
    }

    fun hideInfo() {
        _infoFor.value = null
    }

    private fun canSend(): Boolean {
        if (group.value?.status?.canSend == true) return true
        _error.value = UiText.res(R.string.group_composer_cant_send)
        return false
    }

    fun send() {
        val text = draft.trim()
        if (text.isBlank() || !canSend()) return
        if (text.length > SendMessageUseCase.MAX_MESSAGE_LENGTH) {
            _error.value = UiText.plural(R.plurals.chat_error_message_too_long, SendMessageUseCase.MAX_MESSAGE_LENGTH)
            return
        }
        val replyTo = replyingTo.value
        draft = ""
        if (replyTo != null) cancelReply()
        viewModelScope.launch {
            try {
                groups.sendText(groupId, text, replyTo)
            } catch (e: Exception) {
                Log.w(TAG, "Sending failed: ${e.message}")
                _error.value = UiText.res(R.string.chat_error_send_message)
            }
        }
    }

    fun react(message: Message, emoji: String) {
        if (!message.canReact || !canSend()) return
        val mine = reactions.value[message.id]?.firstOrNull { it.isMine }?.emoji
        val next = emoji.takeUnless { it == mine }
        viewModelScope.launch {
            try {
                groups.react(groupId, message, next)
            } catch (e: Exception) {
                _error.value = UiText.res(R.string.chat_error_send_reaction)
            }
        }
    }

    fun retry(messageId: String) {
        viewModelScope.launch {
            try {
                groups.retry(messageId)
            } catch (e: Exception) {
                _error.value = UiText.res(R.string.chat_error_retry)
            }
        }
    }

    fun sendVoice(recording: com.sotto.app.media.VoiceRecording) {
        if (!canSend()) {
            recording.file.delete()
            return
        }
        val replyTo = replyingTo.value
        viewModelScope.launch {
            val bytes = try {
                withContext(Dispatchers.IO) { recording.file.readBytes() }
            } finally {
                recording.file.delete()
            }
            sendMediaNow(MessageMedia(MediaKind.VOICE, recording.mime, durationMs = recording.durationMs), bytes, "", replyTo)
        }
    }

    fun sendPhoto(uri: android.net.Uri, hd: Boolean = false, caption: String = "") {
        if (!canSend()) return
        val replyTo = replyingTo.value
        val composer = draft.trim()
        if (caption.length > SendMessageUseCase.MAX_MESSAGE_LENGTH) {
            _error.value = UiText.plural(R.plurals.chat_error_caption_too_long, SendMessageUseCase.MAX_MESSAGE_LENGTH)
            return
        }
        viewModelScope.launch {
            val photo = withContext(Dispatchers.Default) {
                runCatching { com.sotto.app.media.PhotoShrinker.shrink(appContext, uri) }.getOrNull()
            }
            if (photo == null) {
                _error.value = UiText.res(R.string.chat_error_photo_unreadable)
                return@launch
            }
            val made = if (hd) {
                withContext(Dispatchers.Default) {
                    runCatching {
                        com.sotto.app.media.PhotoShrinker.shrink(
                            appContext, uri,
                            maxBytes = MediaPayload.HD_MAX_BYTES.toInt(),
                            maxSide = com.sotto.app.media.PhotoShrinker.HD_SIDE,
                            qualities = com.sotto.app.media.PhotoShrinker.HD_QUALITIES,
                        )
                    }.getOrNull()
                }
            } else {
                null
            }
            if (hd && made == null) _error.value = UiText.res(R.string.chat_error_hd_failed)
            val full = made?.takeIf { maxOf(it.width, it.height) > maxOf(photo.width, photo.height) }
            val sent = sendMediaNow(
                MessageMedia(MediaKind.PHOTO, "image/jpeg", width = photo.width, height = photo.height),
                photo.bytes, caption, replyTo, full?.bytes,
            )
            if (sent && composer.isNotEmpty() && draft.trim() == composer && caption.contains(composer)) draft = ""
        }
    }

    private suspend fun sendMediaNow(media: MessageMedia, bytes: ByteArray, caption: String, replyTo: Message?, hdBytes: ByteArray? = null): Boolean {
        if (replyTo != null && replyingTo.value?.id == replyTo.id) cancelReply()
        return try {
            groups.sendMedia(groupId, media, bytes, caption, replyTo, hdBytes)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Sending media failed: ${e.message}")
            _error.value = UiText.res(if (media.kind == MediaKind.VOICE) R.string.chat_error_send_voice else R.string.chat_error_send_photo)
            false
        }
    }

    fun sendFile(uri: android.net.Uri) {
        if (!canSend()) return
        val replyTo = replyingTo.value
        val caption = draft.trim()
        if (caption.length > SendMessageUseCase.MAX_MESSAGE_LENGTH) {
            _error.value = UiText.plural(R.plurals.chat_error_caption_too_long, SendMessageUseCase.MAX_MESSAGE_LENGTH)
            return
        }
        if (caption.isNotEmpty()) draft = ""
        if (replyTo != null) cancelReply()
        viewModelScope.launch {
            try {
                groups.sendFile(groupId, uri, caption, replyTo)
            } catch (e: com.sotto.app.data.media.FileTooLargeException) {
                _error.value = UiText.res(R.string.chat_error_file_max_size, (e.maxBytes / (1024 * 1024)).toInt())
            } catch (e: Exception) {
                Log.w(TAG, "Sending file failed: ${e.message}")
                _error.value = UiText.res(R.string.chat_error_send_file)
            }
        }
    }

    /** A file's bubble was tapped: download it, or open it once it's on this phone. */
    fun onFileTap(message: Message) {
        val media = message.media ?: return
        viewModelScope.launch {
            try {
                if (media.fileState == com.sotto.app.domain.model.FileState.REMOTE) {
                    messageRepository.requestDownload(message.id)
                    return@launch
                }
                if (media.kind == MediaKind.PHOTO) return@launch
                val file = messageRepository.decryptFileForOpening(message.id)
                if (file == null) {
                    _error.value = UiText.res(R.string.chat_error_file_not_here)
                    return@launch
                }
                _openFile.tryEmit(ChatViewModel.OpenedFile(file, media.mime.ifBlank { "application/octet-stream" }))
            } catch (e: Exception) {
                Log.w(TAG, "File failed: ${e.message}")
                _error.value = UiText.res(R.string.chat_error_download_failed)
            }
        }
    }

    fun saveCopy(file: ChatViewModel.OpenedFile, target: android.net.Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openOutputStream(target)?.use { out -> file.file.inputStream().use { it.copyTo(out) } }
                        ?: error("No output")
                }.isSuccess
            }
            _error.value = UiText.res(if (ok) R.string.chat_file_saved else R.string.chat_error_file_not_saved)
        }
    }

    suspend fun loadMedia(messageId: String): ByteArray? = messageRepository.loadMedia(messageId)

    suspend fun loadHd(messageId: String): ByteArray? = messageRepository.loadHd(messageId)

    fun accept() {
        viewModelScope.launch { runCatching { groups.acceptInvitation(groupId) } }
    }

    fun decline(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { groups.rejectInvitation(groupId) }
            onDone()
        }
    }

    @Volatile
    private var resumed = false

    fun onScreenResumed() {
        resumed = true
        groups.setActiveGroup(groupId)
        markRead()
    }

    fun onScreenPaused() {
        resumed = false
        groups.setActiveGroup(null)
    }

    init {
        // Messages arriving while the chat is open are read at once.
        viewModelScope.launch {
            messages.map { list -> list.orEmpty().count { !it.isOutgoing && it.state == com.sotto.app.domain.model.MessageState.DELIVERED } }
                .collect { if (resumed && it > 0) markRead() }
        }
    }

    private fun markRead() {
        viewModelScope.launch {
            runCatching { groups.markRead(groupId) }.onFailure { Log.w(TAG, "Marking read failed: ${it.message}") }
        }
    }

    suspend fun pollWhileVisible() {
        while (true) {
            try {
                if (!webSocketClient.isLive()) messageRepository.fetchPendingFromServer()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Periodic message fetch failed: ${e.message}")
            }
            kotlinx.coroutines.delay(15_000)
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (resumed) groups.setActiveGroup(null)
    }

    /** Whether the user can write here, and if not, why (for the composer). */
    val status: StateFlow<GroupStatus?> = group.map { it?.status }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private companion object {
        const val TAG = "GroupChatVM"
        const val KEY_DRAFT = "group_composer_draft"
        const val KEY_REPLY_TO = "group_composer_reply_to"
    }
}
