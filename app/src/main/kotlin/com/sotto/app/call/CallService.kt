package com.sotto.app.call

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sotto.app.domain.model.CallDirection
import com.sotto.app.domain.model.CallState
import com.sotto.app.domain.model.CallStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service for the whole call, from the moment an incoming call rings.
 *
 * While an incoming call rings it shows the ringing notification, with type phoneCall:
 * without a foreground service Android may pause the app in the background, and then it
 * can neither notice the caller hanging up nor keep ringing reliably. Once the call is
 * live it shows the ongoing-call notification, with phoneCall and microphone (Android
 * silences the microphone of a background app otherwise; it only grants that type while
 * the app is on screen, so CallManager.refreshCallService asks again from the call screen).
 * [CallManager] only starts it; the service follows the call state itself and stops when
 * the call ends. Stopping it from outside before it had called startForeground(), or
 * posting its CallStyle notification while it isn't in the foreground, crashes the app,
 * so it owns both.
 */
@AndroidEntryPoint
class CallService : Service() {

    @Inject lateinit var callNotifier: CallNotifier
    @Inject lateinit var callManager: CallManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var follower: Job? = null
    private var lastStartId = 0
    /** The notification id the service is in the foreground with, null before the first time. */
    private var foregroundId: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Every startForegroundService() must be answered with startForeground().
        if (!enterForeground(callManager.callState.value, force = true)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (follower == null) {
            follower = scope.launch {
                callManager.callState.collect { state ->
                    if (state != null && state.status in LIVE) {
                        enterForeground(state, force = false)
                    } else {
                        ServiceCompat.stopForeground(this@CallService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        // Only the latest start: a new call may have started it again.
                        stopSelf(lastStartId)
                    }
                }
            }
        }
        // A restarted process has no call to keep alive.
        return START_NOT_STICKY
    }

    /**
     * In the foreground with what the call needs now. [force]: apply the types again (a new
     * start, e.g. from the call screen, where the microphone becomes allowed); otherwise only
     * a change of phase (ringing to live) does. False if Android refused even phoneCall.
     */
    private fun enterForeground(state: CallState?, force: Boolean): Boolean {
        val ringingIn = state?.direction == CallDirection.INCOMING && state.status == CallStatus.RINGING
        val id = if (ringingIn) CallNotifier.INCOMING_ID else CallNotifier.ONGOING_ID
        if (!force && foregroundId == id) {
            if (!ringingIn && state != null) callNotifier.updateOngoing(state)
            return true
        }
        val notification = if (ringingIn && state != null) {
            callNotifier.buildIncoming(state)
        } else {
            callNotifier.buildOngoing(state)
        }
        val withMicrophone = !ringingIn && ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        for (types in listOf(serviceTypes(withMicrophone), serviceTypes(withMicrophone = false)).distinct()) {
            try {
                ServiceCompat.startForeground(this, id, notification, types)
                foregroundId = id
                if (id == CallNotifier.ONGOING_ID) {
                    NotificationManagerCompat.from(this).cancel(CallNotifier.INCOMING_ID)
                }
                return true
            } catch (e: Exception) {
                // Not allowed right now (the microphone from the background, say): try less.
                android.util.Log.w("CallService", "Foreground with types $types refused: ${e.message}")
            }
        }
        return foregroundId != null
    }

    private fun serviceTypes(withMicrophone: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        if (withMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        NotificationManagerCompat.from(this).cancel(CallNotifier.ONGOING_ID)
        NotificationManagerCompat.from(this).cancel(CallNotifier.INCOMING_ID)
        super.onDestroy()
    }

    private companion object {
        val LIVE = setOf(CallStatus.RINGING, CallStatus.CONNECTING, CallStatus.CONNECTED)
    }
}
