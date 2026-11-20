package com.sotto.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sotto.app.BuildConfig
import com.sotto.app.connection.PendingInvite
import com.sotto.app.connection.RelayConfig
import com.sotto.app.connection.normalizeRelayUrl
import com.sotto.app.data.remote.ApiHttpException
import com.sotto.app.data.remote.ApiService
import com.sotto.app.data.remote.WebSocketClient
import com.sotto.app.domain.model.DisplayNames
import com.sotto.app.domain.model.Identity
import com.sotto.app.domain.model.InviteLink
import com.sotto.app.domain.model.KeyType
import com.sotto.app.domain.model.displayInviteCode
import com.sotto.app.domain.model.inviteErrorMessage
import com.sotto.app.domain.model.normalizeInviteCode
import com.sotto.app.domain.repository.ConversationRepository
import com.sotto.app.domain.repository.IdentityRepository
import com.sotto.app.domain.repository.PushTokenRepository
import com.sotto.app.domain.repository.SettingsRepository
import com.sotto.app.domain.usecase.AddContactUseCase
import com.sotto.app.domain.usecase.CreateIdentityUseCase
import com.sotto.app.domain.usecase.SendMyNameUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val createIdentity: CreateIdentityUseCase,
    private val webSocketClient: WebSocketClient,
    private val pushTokenRepository: PushTokenRepository,
    private val identityRepository: IdentityRepository,
    private val apiService: ApiService,
    private val relayConfig: RelayConfig,
    private val pendingInvite: PendingInvite,
    private val addContact: AddContactUseCase,
    private val conversationRepository: ConversationRepository,
    private val sendMyName: SendMyNameUseCase,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.Welcome)
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    private var selectedKeyType: KeyType = KeyType.CLASSICAL

    /** The optional name from the name step; stored once the identity exists. */
    private var pendingName: String? = null

    /** The chosen relay's invite code ("" when it takes new people without one). */
    private var inviteCode: String = ""

    /** Whose invite it was: added as a contact once the account exists. */
    private var inviterShareCode: String? = null

    /** The steps after the relay are done: fixing the relay or invite goes straight to sign-up. */
    private var detailsChosen = false

    private var relayCheck: Job? = null

    init {
        // An invite link that opened the app during setup.
        viewModelScope.launch {
            pendingInvite.invite.filterNotNull().collect {
                val current = _state.value
                if (current == OnboardingState.Welcome || current is OnboardingState.RelayChoice) {
                    pendingInvite.take()?.let(::useInvite)
                }
            }
        }
    }

    fun advanceToRelay() {
        if (_state.value != OnboardingState.Welcome) return
        _state.value = freshRelayChoice()
    }

    /** The relay step, empty; debug builds suggest the development relay. */
    private fun freshRelayChoice() = OnboardingState.RelayChoice(input = BuildConfig.DEFAULT_RELAY_URL)

    /**
     * What was pasted, scanned or typed: an invite link (also inside a whole message), which holds
     * the relay and the code together. A bare relay address only gets someone in where no invite
     * is needed (a new relay's first account); there's no separate code to type.
     */
    fun submitRelayInput(text: String) {
        InviteLink.find(text, RelayConfig.cleartextHosts)?.let {
            useInvite(it)
            return
        }
        val relay = normalizeRelayUrl(text, RelayConfig.cleartextHosts)
        if (relay == null) {
            _state.value = OnboardingState.RelayChoice(
                input = text,
                error = "That isn't an invite link. Paste the whole link you were sent.",
            )
            return
        }
        checkRelay(relay, code = "", inviter = null, input = text)
    }

    private fun useInvite(invite: InviteLink) {
        checkRelay(invite.relay, displayInviteCode(invite.code), invite.inviterShareCode, input = "")
    }

    private fun checkRelay(relay: String, code: String, inviter: String?, input: String) {
        relayCheck?.cancel()
        _state.value = OnboardingState.RelayChoice(input = input, checking = true)
        relayCheck = viewModelScope.launch {
            // Unreachable (no network, no such host) vs. something answered that isn't a relay.
            var unreachable = false
            val health = try {
                apiService.relayHealth(relay)
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                unreachable = true
                null
            } catch (e: Exception) {
                null
            }
            val host = relay.substringAfter("://")
            _state.value = when {
                unreachable -> OnboardingState.RelayChoice(
                    input = input.ifEmpty { relay },
                    error = "Couldn't reach $host. Check the address and your connection.",
                )
                health == null || !health.isSottoRelay -> OnboardingState.RelayChoice(
                    input = input.ifEmpty { relay },
                    error = "$host isn't a Sotto relay.",
                )
                // Only an invite link gets someone in here: it carries the code with the relay.
                health.inviteRequired && normalizeInviteCode(code) == null -> OnboardingState.RelayChoice(
                    input = input.ifEmpty { relay },
                    error = "$host takes new people only with an invite link. Ask someone on it to send you one.",
                )
                else -> OnboardingState.RelayChoice(
                    relay = relay,
                    inviteRequired = health.inviteRequired,
                    code = code,
                    inviterShareCode = inviter,
                )
            }
        }
    }

    /** Join the relay that answered, with its invite when it wants one. */
    fun confirmRelay() {
        val current = _state.value as? OnboardingState.RelayChoice ?: return
        val relay = current.relay ?: return
        val code = normalizeInviteCode(current.code)
        if (current.inviteRequired && code == null) {
            _state.value = OnboardingState.RelayChoice(
                error = "This relay takes new people only with an invite link. Ask someone on it to send you one.",
            )
            return
        }
        relayConfig.set(relay)
        inviteCode = code.orEmpty()
        inviterShareCode = current.inviterShareCode
        if (detailsChosen) signUp() else _state.value = OnboardingState.PrivacyExplainer
    }

    fun chooseAnotherRelay() {
        relayCheck?.cancel()
        relayConfig.clear()
        inviteCode = ""
        inviterShareCode = null
        _state.value = freshRelayChoice()
    }

    fun advanceToKeySelection() {
        _state.value = OnboardingState.KeySelection
    }

    fun selectKeyType(keyType: KeyType) {
        selectedKeyType = keyType
        _state.value = OnboardingState.NameEntry
    }

    /** The name the user entered; blank or null (Skip) means no name. */
    fun submitName(name: String?) {
        if (_state.value != OnboardingState.NameEntry) return
        pendingName = DisplayNames.sanitize(name)
        _state.value = OnboardingState.NotificationPermission
    }

    fun createGuestIdentity() {
        // Only from the notification step, or as a retry after an error. A second
        // call while one runs (a double tap, a late permission result) would store
        // a second key pair over the first one's, and a permission result replayed
        // after process death would skip the user's choice of key type.
        val current = _state.value
        if (current != OnboardingState.NotificationPermission && current !is OnboardingState.Error) return
        signUp()
    }

    private fun signUp() {
        detailsChosen = true
        _state.value = OnboardingState.CreatingIdentity
        viewModelScope.launch {
            try {
                val identity = createIdentity(selectedKeyType, inviteCode)
                // Sent to contacts later, when they're added; a failure here mustn't fail setup.
                pendingName?.let { name -> runCatching { identityRepository.setMyName(name) } }
                // Only the owner's relay can push to this app. On any other, stay connected in
                // the background, or calls and messages wouldn't arrive while the app is closed.
                if (!relayConfig.canPush) {
                    runCatching {
                        val settings = settingsRepository.getPrivacySettings().first()
                        if (!settings.backgroundConnection) {
                            settingsRepository.updatePrivacySettings(settings.copy(backgroundConnection = true))
                        }
                    }
                }
                // Identity is now available — force WebSocket reconnect so
                // real-time delivery (messages, call signals) starts immediately
                // instead of waiting for the backoff timer to expire.
                webSocketClient.ensureConnected()
                // FCM may have registered before the identity existed, when the relay
                // couldn't take it; register now or the server can never wake us up.
                pushTokenRepository.syncPushRegistration()
                // Joined with someone's invite: they become a contact, as if their share
                // code had been added (it's checked against their keys the same way).
                inviterShareCode?.let { code ->
                    runCatching {
                        val contact = addContact(code).getOrThrow()
                        conversationRepository.getOrCreateConversation(contact.userId)
                        sendMyName(contact.userId)
                    }.onFailure { android.util.Log.w("Onboarding", "Couldn't add the inviter as a contact: ${it.message}") }
                }
                _state.value = OnboardingState.Complete(identity)
            } catch (e: ApiHttpException) {
                val message = inviteErrorMessage(e.message)
                _state.value = if (message != null) {
                    // Back to the first step for another invite link; the keys made are kept and
                    // reused, and the steps after the relay aren't asked again.
                    relayConfig.clear()
                    inviteCode = ""
                    inviterShareCode = null
                    OnboardingState.RelayChoice(error = message)
                } else {
                    OnboardingState.Error("Failed to create identity. Please try again.")
                }
            } catch (e: Exception) {
                _state.value = OnboardingState.Error("Failed to create identity. Please try again.")
            }
        }
    }
}

sealed interface OnboardingState {
    data object Welcome : OnboardingState

    /**
     * Choosing the relay. While [relay] is null the user opens, pastes or scans an invite link, or
     * types a relay's address ([input]) for a relay that needs no invite; once a relay answered,
     * they confirm it. The invite's [code] comes only from the link.
     */
    data class RelayChoice(
        val input: String = "",
        val checking: Boolean = false,
        val relay: String? = null,
        val inviteRequired: Boolean = false,
        val code: String = "",
        val inviterShareCode: String? = null,
        val error: String? = null,
    ) : OnboardingState

    data object PrivacyExplainer : OnboardingState
    data object KeySelection : OnboardingState
    data object NameEntry : OnboardingState
    data object NotificationPermission : OnboardingState
    data object CreatingIdentity : OnboardingState
    data class Complete(val identity: Identity) : OnboardingState
    data class Error(val message: String) : OnboardingState
}
