package com.sotto.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sotto.app.R
import com.sotto.app.domain.model.DisappearingDuration
import com.sotto.app.domain.model.LockScreenPreviewMode
import com.sotto.app.domain.model.PrivacySettings
import com.sotto.app.domain.repository.IdentityRepository
import com.sotto.app.domain.repository.SettingsRepository
import com.sotto.app.notification.CallRingtone
import com.sotto.app.notification.SoundKind
import com.sotto.app.notification.soundTitle
import com.sotto.app.ui.text.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val identityRepository: IdentityRepository,
    private val conversationRepository: com.sotto.app.domain.repository.ConversationRepository,
    private val messageRepository: com.sotto.app.domain.repository.MessageRepository,
    private val apiService: com.sotto.app.data.remote.ApiService,
    private val relayConfig: com.sotto.app.connection.RelayConfig,
    private val inPersonVerifier: com.sotto.app.crypto.InPersonVerifier,
    private val groupRepository: com.sotto.app.domain.repository.GroupRepository,
    private val chatNotifications: com.sotto.app.notification.ChatNotificationManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    private val _sounds = MutableStateFlow<Sounds?>(null)
    /** The sounds in Settings > Sounds; null until read. */
    val sounds: StateFlow<Sounds?> = _sounds.asStateFlow()

    /**
     * Reads and changes of the sounds, one after another: a read started on coming back from
     * the picker must not finish after the change and show the old sound.
     */
    private var soundWork: kotlinx.coroutines.Job? = null

    private fun soundWork(block: suspend () -> Unit) {
        val previous = soundWork
        soundWork = viewModelScope.launch {
            previous?.join()
            block()
        }
    }

    /** Read the sounds again: also on coming back to Settings, as one may have changed in Android's settings. */
    fun refreshSounds() = soundWork { _sounds.value = readSounds() }

    private suspend fun readSounds(): Sounds {
        val ringtone = settingsRepository.getPrivacySettings().first().callRingtone
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            fun channel(kind: SoundKind): Sound {
                val uri = chatNotifications.messageSound(kind)
                return Sound(uri, soundName(uri))
            }
            val callUri = CallRingtone.uri(ringtone)
            Sounds(
                messages = channel(SoundKind.MESSAGES),
                groups = channel(SoundKind.GROUPS),
                calls = Sound(callUri, soundName(callUri)),
            )
        }
    }

    private fun soundName(uri: android.net.Uri?): UiText = when (uri) {
        null -> UiText.res(R.string.settings_sound_none)
        else -> soundTitle(appContext, uri)?.let { UiText.Plain(it) } ?: UiText.res(R.string.settings_sound_unknown)
    }

    /** The user picked [uri] (null: none) for [kind] in Android's sound list. */
    fun setSound(kind: SoundKind, uri: android.net.Uri?) {
        soundWork {
            when (kind) {
                SoundKind.CALLS -> settingsMutex.withLock {
                    val current = settingsRepository.getPrivacySettings().first()
                    settingsRepository.updatePrivacySettings(current.copy(callRingtone = CallRingtone.fromPicked(uri)))
                }
                else -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    chatNotifications.setMessageSound(kind, uri)
                }
            }
            _sounds.value = readSounds()
        }
    }

    /** Groups the user runs (with other members), offered a handover before the account goes. */
    private val _groupsToHandOver = MutableStateFlow(0)
    val groupsToHandOver: StateFlow<Int> = _groupsToHandOver.asStateFlow()

    /**
     * A fresh live QR code (it changes every second, as BankID's): scanned in person it adds
     * and verifies; a picture of it only adds (InPersonVerifier).
     */
    suspend fun liveQr(): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { inPersonVerifier.myLiveCode() }?.encode()
            ?.also { com.sotto.app.ui.contacts.LiveQrWatch.shown() }

    /** The relay the account lives on. */
    val relayHost: String? = relayConfig.host

    /** Push reaches the app only through the owner's relay. */
    val relayCanPush: Boolean = relayConfig.canPush

    private val _invite = kotlinx.coroutines.flow.MutableStateFlow<InviteShare?>(null)
    /** A fresh invite to show and share; null when none is open. */
    val invite: StateFlow<InviteShare?> = _invite

    private val _inviteError = kotlinx.coroutines.flow.MutableStateFlow<UiText?>(null)
    val inviteError: StateFlow<UiText?> = _inviteError

    private var creatingInvite = false

    /** A one-time invite to this relay; it also tells the invited person who to add. */
    fun createInvite() {
        if (creatingInvite) return
        creatingInvite = true
        viewModelScope.launch {
            try {
                val created = apiService.createInvite()
                val relay = relayConfig.baseUrl ?: throw IllegalStateException("No relay")
                val code = com.sotto.app.domain.model.normalizeInviteCode(created.code)
                    ?: throw IllegalStateException("Unexpected invite code")
                val shareCode = identityRepository.getIdentity()?.shareCode
                _invite.value = InviteShare(
                    link = com.sotto.app.domain.model.InviteLink(relay, code, shareCode).toLink(),
                    expiresAt = created.expiresAt,
                )
            } catch (e: com.sotto.app.data.remote.ApiHttpException) {
                _inviteError.value = com.sotto.app.domain.model.inviteLimitMessage(e.message)
            } catch (e: Exception) {
                _inviteError.value = UiText.res(R.string.settings_invite_error)
            } finally {
                creatingInvite = false
            }
        }
    }

    fun dismissInvite() {
        _invite.value = null
    }

    fun inviteErrorShown() {
        _inviteError.value = null
    }

    val blockedUsers: StateFlow<List<com.sotto.app.domain.model.BlockedUser>> =
        conversationRepository.getBlockedUsers()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun unblock(userId: String) {
        viewModelScope.launch { conversationRepository.unblockUser(userId) }
    }

    val privacySettings: StateFlow<PrivacySettings> =
        settingsRepository.getPrivacySettings()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PrivacySettings())

    private val _shareCode = MutableStateFlow<String?>(null)
    val shareCode: StateFlow<String?> = _shareCode.asStateFlow()

    /** The user's own name, sent to new contacts; null when not set. */
    private val _myName = MutableStateFlow<String?>(null)
    val myName: StateFlow<String?> = _myName.asStateFlow()

    private val settingsMutex = Mutex()

    private val _showWipeConfirmation = MutableStateFlow(false)
    val showWipeConfirmation: StateFlow<Boolean> = _showWipeConfirmation.asStateFlow()

    private val _wipeCompleted = MutableStateFlow(false)
    val wipeCompleted: StateFlow<Boolean> = _wipeCompleted.asStateFlow()

    private val _wipeError = MutableStateFlow<UiText?>(null)
    val wipeError: StateFlow<UiText?> = _wipeError.asStateFlow()

    private val _showRotateConfirmation = MutableStateFlow(false)
    val showRotateConfirmation: StateFlow<Boolean> = _showRotateConfirmation.asStateFlow()

    private val _isRotatingKeys = MutableStateFlow(false)
    val isRotatingKeys: StateFlow<Boolean> = _isRotatingKeys.asStateFlow()

    private val _rotationError = MutableStateFlow<UiText?>(null)
    val rotationError: StateFlow<UiText?> = _rotationError.asStateFlow()

    init {
        viewModelScope.launch {
            val identity = identityRepository.getIdentity()
            _shareCode.value = identity?.shareCode
            _myName.value = identityRepository.getMyName()
        }
    }

    /** Set the user's own name; blank clears it. Only connections made afterwards get it. */
    fun setMyName(name: String) {
        viewModelScope.launch {
            identityRepository.setMyName(name)
            _myName.value = identityRepository.getMyName()
        }
    }

    fun updateLockScreenPreview(mode: LockScreenPreviewMode) {
        viewModelScope.launch {
            settingsMutex.withLock {
                val current = settingsRepository.getPrivacySettings().first()
                settingsRepository.updatePrivacySettings(
                    current.copy(lockScreenPreview = mode),
                )
            }
        }
    }

    fun updateReadReceipts(enabled: Boolean) {
        viewModelScope.launch {
            settingsMutex.withLock {
                val current = settingsRepository.getPrivacySettings().first()
                settingsRepository.updatePrivacySettings(
                    current.copy(readReceipts = enabled),
                )
            }
        }
    }

    fun updateReduceAnimations(enabled: Boolean) {
        viewModelScope.launch {
            settingsMutex.withLock {
                val current = settingsRepository.getPrivacySettings().first()
                settingsRepository.updatePrivacySettings(current.copy(reduceAnimations = enabled))
            }
        }
    }

    fun updateHighQualityCalls(enabled: Boolean) {
        viewModelScope.launch {
            settingsMutex.withLock {
                val current = settingsRepository.getPrivacySettings().first()
                settingsRepository.updatePrivacySettings(current.copy(highQualityCalls = enabled))
            }
        }
    }

    fun updateLevelVoices(enabled: Boolean) {
        viewModelScope.launch {
            settingsMutex.withLock {
                val current = settingsRepository.getPrivacySettings().first()
                settingsRepository.updatePrivacySettings(current.copy(levelVoices = enabled))
            }
        }
    }

    fun updateWebRtcVoiceProcessing(enabled: Boolean) {
        viewModelScope.launch {
            settingsMutex.withLock {
                val current = settingsRepository.getPrivacySettings().first()
                settingsRepository.updatePrivacySettings(current.copy(webRtcVoiceProcessing = enabled))
            }
        }
    }

    fun updateDirectCalls(enabled: Boolean) {
        viewModelScope.launch {
            settingsMutex.withLock {
                val current = settingsRepository.getPrivacySettings().first()
                settingsRepository.updatePrivacySettings(
                    current.copy(directCalls = enabled),
                )
            }
        }
    }

    fun updateBackgroundConnection(enabled: Boolean) {
        viewModelScope.launch {
            settingsMutex.withLock {
                val current = settingsRepository.getPrivacySettings().first()
                settingsRepository.updatePrivacySettings(
                    current.copy(backgroundConnection = enabled),
                )
            }
        }
    }

    fun updateScreenSecurity(enabled: Boolean) {
        viewModelScope.launch {
            settingsMutex.withLock {
                val current = settingsRepository.getPrivacySettings().first()
                settingsRepository.updatePrivacySettings(
                    current.copy(screenSecurity = enabled),
                )
            }
        }
    }

    fun updateDisappearingMessages(duration: DisappearingDuration) {
        viewModelScope.launch {
            settingsMutex.withLock {
                val current = settingsRepository.getPrivacySettings().first()
                settingsRepository.updatePrivacySettings(
                    current.copy(disappearingMessagesDefault = duration),
                )
            }
        }
    }

    /** Retention (days) waiting for the user to confirm; null when no dialog. */
    private val _pendingRetentionDays = MutableStateFlow<Int?>(null)
    val pendingRetentionDays: StateFlow<Int?> = _pendingRetentionDays.asStateFlow()

    /** Choosing a period that deletes history asks first; "keep forever" doesn't. */
    fun requestRetention(days: Int) {
        if (days == 0) applyRetention(0) else _pendingRetentionDays.value = days
    }

    fun confirmRetention() {
        val days = _pendingRetentionDays.value ?: return
        _pendingRetentionDays.value = null
        applyRetention(days)
    }

    fun cancelRetention() {
        _pendingRetentionDays.value = null
    }

    private fun applyRetention(days: Int) {
        viewModelScope.launch {
            settingsMutex.withLock {
                val current = settingsRepository.getPrivacySettings().first()
                settingsRepository.updatePrivacySettings(current.copy(localDataRetentionDays = days))
            }
            // Apply now rather than at the next periodic cleanup.
            try { messageRepository.deleteExpiredMessages() } catch (_: Exception) {}
        }
    }

    fun requestWipe() {
        viewModelScope.launch {
            _groupsToHandOver.value = runCatching { groupRepository.groupsToHandOver().size }.getOrDefault(0)
            _showWipeConfirmation.value = true
        }
    }

    fun cancelWipe() {
        _showWipeConfirmation.value = false
    }

    /** [handOver]: first give every group the user runs to another member (ADR-32); else they freeze. */
    fun confirmWipe(handOver: Boolean = true) {
        _showWipeConfirmation.value = false
        performWipe {
            if (handOver) runCatching { groupRepository.handOverAll() }
            settingsRepository.wipeLocalData()
        }
    }

    fun retryWipe() {
        _wipeError.value = null
        performWipe { settingsRepository.retryServerDeletion() }
    }

    fun forceWipeLocal() {
        _wipeError.value = null
        viewModelScope.launch {
            settingsRepository.wipeLocalOnly()
            _wipeCompleted.value = true
        }
    }

    fun dismissWipeError() {
        _wipeError.value = null
        _wipeCompleted.value = true
    }

    private fun performWipe(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
                _wipeCompleted.value = true
            } catch (_: Exception) {
                _wipeError.value = UiText.res(R.string.settings_wipe_unreachable_text)
            }
        }
    }

    fun requestRotateKeys() {
        _rotationError.value = null
        _showRotateConfirmation.value = true
    }

    fun cancelRotateKeys() {
        _showRotateConfirmation.value = false
    }

    fun confirmRotateKeys() {
        _showRotateConfirmation.value = false
        _isRotatingKeys.value = true
        _rotationError.value = null
        viewModelScope.launch {
            try {
                identityRepository.rotateIdentityKeys()
                // Refresh the displayed share code
                val identity = identityRepository.getIdentity()
                _shareCode.value = identity?.shareCode
            } catch (e: Exception) {
                android.util.Log.e("SettingsVM", "Key rotation failed", e)
                _rotationError.value = when (e) {
                    is java.io.IOException -> UiText.res(R.string.settings_rotate_failed_network)
                    is IllegalStateException -> UiText.res(R.string.settings_rotate_failed_local)
                    else -> UiText.res(R.string.settings_rotate_failed)
                }
            } finally {
                _isRotatingKeys.value = false
            }
        }
    }

    fun dismissRotationError() {
        _rotationError.value = null
    }
}

/** A sound in Settings > Sounds: [uri] (null for none) to open the picker on, [name] to show. */
data class Sound(val uri: android.net.Uri?, val name: UiText)

data class Sounds(val messages: Sound, val groups: Sound, val calls: Sound) {
    operator fun get(kind: SoundKind) = when (kind) {
        SoundKind.MESSAGES -> messages
        SoundKind.GROUPS -> groups
        SoundKind.CALLS -> calls
    }
}
