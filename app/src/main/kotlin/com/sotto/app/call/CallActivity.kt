package com.sotto.app.call

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.sotto.app.MainActivity
import com.sotto.app.domain.repository.SettingsRepository
import com.sotto.app.ui.call.CallScreen
import com.sotto.app.ui.theme.SottoTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The call screen on its own, for the incoming-call notification (its full-screen alert,
 * Answer and tap) and the ongoing-call notification.
 *
 * The manifest marks it to show over the lock screen and turn the screen on. Android only
 * lets a notification open an activity over the lock screen without unlocking first when
 * the manifest says so, and MainActivity can't: that would put the chats over the lock
 * screen too. Leaving this screen while the phone is unlocked hands over to the app (with
 * its ongoing-call bar); while it's locked, the screen just goes away.
 */
@AndroidEntryPoint
class CallActivity : ComponentActivity() {

    @Inject lateinit var callManager: CallManager
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            // The manifest attributes exist from Android 8.1.
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch {
            settingsRepository.getPrivacySettings().collect { settings ->
                if (settings.screenSecurity) {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
        if (!handle(intent)) return
        val reduceAnimations = settingsRepository.getPrivacySettings().map { it.reduceAnimations }
        setContent {
            val reduceMotion by reduceAnimations.collectAsState(initial = false)
            SottoTheme(reduceMotion = reduceMotion) {
                CallScreen(
                    onCallEnded = { finishAndRemoveTask() },
                    onMinimize = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        )
                        finishAndRemoveTask()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // On screen now: the call's service may take the microphone (Android only allows
        // that while the app is in the foreground).
        callManager.refreshCallService()
    }

    /** False (and finishing) when the notification belonged to a call that's already over. */
    private fun handle(intent: Intent?): Boolean {
        val callId = intent?.getStringExtra(CallNotifier.EXTRA_CALL_ID)
        val call = callManager.callState.value
        if (call == null || (callId != null && call.callId != callId)) {
            finishAndRemoveTask()
            return false
        }
        if (intent?.action == CallNotifier.ACTION_ACCEPT_CALL) callManager.requestAutoAccept(call.callId)
        return true
    }
}
