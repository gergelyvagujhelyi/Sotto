package com.sotto.app.ui.contacts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sotto.app.crypto.InPersonCode
import com.sotto.app.crypto.InPersonResult
import com.sotto.app.crypto.InPersonVerifier
import com.sotto.app.domain.model.Contact
import com.sotto.app.domain.repository.IdentityRepository
import com.sotto.app.nfc.InPersonNfc
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** How a check went, and whether it was a tap (which checks both phones) or a scan (this one only). */
data class VerifyOutcome(val result: InPersonResult, val viaNfc: Boolean)

@HiltViewModel
class VerifyInPersonViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    identityRepository: IdentityRepository,
    private val verifier: InPersonVerifier,
) : ViewModel() {

    val contactId: String = savedStateHandle["contactId"] ?: ""

    val contact: StateFlow<Contact?> =
        identityRepository.observeContact(contactId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _myCode = MutableStateFlow<InPersonCode?>(null)

    /** This phone's code: shown as the QR code, and sent on an NFC tap ([com.sotto.app.nfc.InPersonNfcEffect]). */
    val myCode: StateFlow<InPersonCode?> = _myCode.asStateFlow()

    /** This phone's code as the QR code shows it; null until loaded. */
    private val _myQr = MutableStateFlow<String?>(null)
    val myQr: StateFlow<String?> = _myQr.asStateFlow()

    private val _outcome = MutableStateFlow<VerifyOutcome?>(null)
    val outcome: StateFlow<VerifyOutcome?> = _outcome.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    init {
        viewModelScope.launch {
            val code = withContext(Dispatchers.IO) { verifier.myCode() }
            _myCode.value = code
            _myQr.value = code?.encode()
        }
        viewModelScope.launch {
            InPersonNfc.received.collect { code -> check(code, viaNfc = true) }
        }
    }

    /** A QR code the scanner accepted ([InPersonCode.parse] already passed). */
    fun onScanned(text: String) {
        InPersonCode.parse(text)?.let { check(it, viaNfc = false) }
    }

    fun dismissOutcome() {
        _outcome.value = null
    }

    private fun check(code: InPersonCode, viaNfc: Boolean) {
        viewModelScope.launch {
            _checking.value = true
            try {
                _outcome.value = VerifyOutcome(verifier.verify(contactId, code), viaNfc)
            } finally {
                _checking.value = false
            }
        }
    }

    override fun onCleared() {
        InPersonNfc.stop()
    }
}
