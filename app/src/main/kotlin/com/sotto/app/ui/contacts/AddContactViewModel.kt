package com.sotto.app.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sotto.app.domain.model.Contact
import com.sotto.app.domain.repository.ConversationRepository
import com.sotto.app.domain.repository.IdentityRepository
import com.sotto.app.domain.usecase.AddContactUseCase
import com.sotto.app.domain.usecase.SendMyNameUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddContactViewModel @Inject constructor(
    private val addContact: AddContactUseCase,
    private val conversationRepository: ConversationRepository,
    private val identityRepository: IdentityRepository,
    private val sendMyName: SendMyNameUseCase,
    private val inPersonVerifier: com.sotto.app.crypto.InPersonVerifier,
) : ViewModel() {

    private val _myCode = MutableStateFlow<com.sotto.app.crypto.InPersonCode?>(null)

    /**
     * This phone's code: "Show my QR code" shows it, and an NFC tap sends it. Someone who reads
     * it in person adds this phone and verifies it in one step ([addInPerson]).
     */
    val myCode: StateFlow<com.sotto.app.crypto.InPersonCode?> = _myCode.asStateFlow()

    private val _shareCodeInput = MutableStateFlow("")
    val shareCodeInput: StateFlow<String> = _shareCodeInput.asStateFlow()

    private val _state = MutableStateFlow<AddContactState>(AddContactState.Idle)
    val state: StateFlow<AddContactState> = _state.asStateFlow()

    private val _myShareCode = MutableStateFlow<String?>(null)
    val myShareCode: StateFlow<String?> = _myShareCode.asStateFlow()

    /** Whether the user has set a name to send to the people they add (shows a hint when not). */
    private val _hasMyName = MutableStateFlow(true)
    val hasMyName: StateFlow<Boolean> = _hasMyName.asStateFlow()

    val contacts = identityRepository.getContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val identity = identityRepository.getIdentity()
            _myShareCode.value = identity?.shareCode
            _hasMyName.value = identityRepository.getMyName() != null
            _myCode.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { inPersonVerifier.myCode() }
        }
        // Two phones held together on this screen: each adds the other, verified in person.
        viewModelScope.launch {
            com.sotto.app.nfc.InPersonNfc.received.collect { addInPerson(it) }
        }
    }

    /**
     * Adds the person whose code was read straight off their phone (NFC tap or their QR code)
     * and marks them verified in person: the share code inside the fingerprint finds them on
     * the relay, and their whole fingerprint must match the keys it hands out.
     */
    fun addInPerson(code: com.sotto.app.crypto.InPersonCode) {
        if (_state.value is AddContactState.Loading) return
        _state.value = AddContactState.Loading
        viewModelScope.launch {
            if (code.userId == identityRepository.getIdentity()?.userId) {
                _state.value = AddContactState.Error(OWN_SHARE_CODE_MESSAGE)
                return@launch
            }
            val shareCode = com.sotto.app.crypto.KeyFingerprint.shareCode(code.fingerprint)
            try {
                val existed = identityRepository.getContact(code.userId) != null
                val contact = addContact(shareCode).getOrThrow()
                val result = inPersonVerifier.verify(contact.userId, code)
                if (result != com.sotto.app.crypto.InPersonResult.Verified) {
                    // The relay answered with keys other than the ones on their phone: don't keep them.
                    if (!existed) conversationRepository.removeContact(contact.userId)
                    _state.value = AddContactState.Error(
                        "The keys the relay gave for this person don't match their phone. Don't add them this way.",
                    )
                    return@launch
                }
                val conversationId = conversationRepository.getOrCreateConversation(contact.userId)
                if (!existed) sendMyName(contact.userId)
                _state.value = AddContactState.Success(contact.copy(verifiedInPerson = true), conversationId)
            } catch (e: Exception) {
                android.util.Log.e("AddContactVM", "Failed to add contact in person", e)
                _state.value = AddContactState.Error("Couldn't add them. Check your connection and try again.")
            }
        }
    }

    /** Opens the chat with someone already added (started now if there's none yet). */
    fun openChat(contact: Contact) {
        if (_state.value is AddContactState.Loading) return
        viewModelScope.launch {
            val conversationId = conversationRepository.getOrCreateConversation(contact.userId)
            _state.value = AddContactState.Success(contact, conversationId)
        }
    }

    /** Remove [userId] from the contacts, and the chat with them, from this phone. */
    fun removeContact(userId: String) {
        viewModelScope.launch { conversationRepository.removeContact(userId) }
    }

    fun updateShareCode(code: String) {
        _shareCodeInput.value = code
    }

    /** Adds the contact behind a code read by the QR scanner, without another tap. */
    fun submitScannedShareCode(code: String) {
        if (_state.value is AddContactState.Loading) return
        _shareCodeInput.value = code
        submitShareCode()
    }

    fun submitShareCode() {
        // Codes pasted from a message often carry spaces or line breaks.
        val code = _shareCodeInput.value.filterNot { it.isWhitespace() }
        if (code.isBlank()) return
        // Share codes are exactly 16 base64url characters; anything else can't be one
        // (and must not be put into the request path as-is).
        if (!SHARE_CODE_PATTERN.matches(code)) {
            _state.value = AddContactState.Error("That doesn't look like a share code. Check it and try again.")
            return
        }

        _state.value = AddContactState.Loading
        viewModelScope.launch {
            // The user's own code (pasted, or from their own invite link): say so, rather than
            // asking the relay and failing with a generic error. Read here as well, in case
            // the screen's copy hasn't loaded yet.
            val mine = _myShareCode.value ?: identityRepository.getIdentity()?.shareCode
            if (code == mine) {
                _state.value = AddContactState.Error(OWN_SHARE_CODE_MESSAGE)
                return@launch
            }
            try {
                val contact = addContact(code).getOrThrow()
                val conversationId = conversationRepository.getOrCreateConversation(contact.userId)
                // A new connection: send the user's name so they see it instead of an id.
                sendMyName(contact.userId)
                _state.value = AddContactState.Success(contact, conversationId)
                _shareCodeInput.value = ""
            } catch (e: Exception) {
                android.util.Log.e("AddContactVM", "Failed to add contact", e)
                _state.value = AddContactState.Error("Failed to add contact. Check the share code and try again.")
            }
        }
    }

    fun resetState() {
        _state.value = AddContactState.Idle
    }
}

/** Shown when the user tries to add their own share code. */
const val OWN_SHARE_CODE_MESSAGE = "That's your own share code. Ask the other person for theirs."

sealed interface AddContactState {
    data object Idle : AddContactState
    data object Loading : AddContactState
    data class Success(val contact: Contact, val conversationId: String) : AddContactState
    data class Error(val message: String) : AddContactState
}
