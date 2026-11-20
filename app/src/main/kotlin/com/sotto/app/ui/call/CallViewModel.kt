package com.sotto.app.ui.call

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.sotto.app.call.CallManager
import com.sotto.app.domain.model.CallState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val callManager: CallManager,
) : ViewModel() {

    val callState: StateFlow<CallState?> = callManager.callState
    val callError: StateFlow<String?> = callManager.callError

    private val pendingContactId: String = savedStateHandle["contactId"] ?: ""
    private val pendingDisplayName: String = savedStateHandle.get<String>("displayName")
        ?.let { Uri.decode(it) } ?: pendingContactId.take(8)

    /** Encryption label derived from the call's actual PQC key agreement result. */
    val encryptionInfo: StateFlow<String> =
        callManager.callState
            .map { state ->
                if (state?.pqcEstablished == true) "ML-KEM-768 + X25519 + AES-256-GCM"
                else "X25519 + AES-256-GCM"
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                "X25519 + AES-256-GCM",
            )

    /**
     * Whether this screen already did its start-up (dialing, or showing an incoming
     * call). Kept in SavedStateHandle: after process death Navigation restores the
     * call route with a new ViewModel and no call state, and a plain field would
     * make it dial the contact again — even the peer of an incoming call.
     */
    private var outgoingStarted: Boolean
        get() = savedStateHandle.get<Boolean>(KEY_STARTED) == true
        set(value) { savedStateHandle[KEY_STARTED] = value }

    init {
        // Opened for an incoming call: this screen must never dial.
        if (callManager.callState.value?.direction == com.sotto.app.domain.model.CallDirection.INCOMING) {
            outgoingStarted = true
        }
    }

    /**
     * Called by the UI once microphone permission is confirmed.
     * Initiates an outgoing call if no call is active, or accepts an incoming one.
     */
    fun onMicPermissionGranted() {
        val current = callManager.callState.value
        // A call that just ended may still be on its end screen: calling back works anyway.
        if ((current == null || current.status.isOver) && !outgoingStarted && pendingContactId.isNotEmpty()) {
            outgoingStarted = true
            callManager.initiateCall(pendingContactId, pendingDisplayName)
        }
    }

    private companion object {
        const val KEY_STARTED = "call_screen_started"
    }

    val autoAcceptRequest: StateFlow<String?> = callManager.autoAcceptRequest
    fun consumeAutoAccept(): Boolean = callManager.consumeAutoAccept()

    fun acceptCall() {
        callManager.acceptCall()
    }
    fun rejectCall() = callManager.rejectCall()
    fun hangup() = callManager.hangup()
    fun toggleMute() = callManager.toggleMute()
    fun toggleSpeaker() = callManager.toggleSpeaker()
    fun toggleLouder() = callManager.toggleLouder()
    fun selectAudioRoute(route: com.sotto.app.domain.model.AudioRoute) = callManager.selectAudioRoute(route)
    fun clearCallError() = callManager.clearCallError()
}
