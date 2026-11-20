package com.sotto.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Decline / hang-up buttons of the call notifications (no need to open the app). */
@AndroidEntryPoint
class CallActionReceiver : BroadcastReceiver() {

    @Inject lateinit var callManager: CallManager

    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(CallNotifier.EXTRA_CALL_ID)
        // Only act on the call the notification was posted for.
        if (callId == null || callManager.callState.value?.callId != callId) return
        when (intent.action) {
            ACTION_DECLINE -> callManager.rejectCall()
            ACTION_HANG_UP -> callManager.hangup()
        }
    }

    companion object {
        const val ACTION_DECLINE = "com.sotto.app.action.DECLINE_CALL"
        const val ACTION_HANG_UP = "com.sotto.app.action.HANG_UP"
    }
}
