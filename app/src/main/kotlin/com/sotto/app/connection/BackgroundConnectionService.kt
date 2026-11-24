package com.sotto.app.connection

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sotto.app.MainActivity
import com.sotto.app.R
import com.sotto.app.data.remote.WebSocketClient
import com.sotto.app.domain.repository.SettingsRepository
import com.sotto.app.notification.ChatNotificationManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the app's process, and with it the relay WebSocket, alive while the app is closed,
 * so messages and calls arrive on phones without Google push. The WebSocket client already
 * reconnects for as long as the process lives; this service only stops Android from ending
 * the process. Runs while the "Background connection" setting is on (see [BackgroundConnection]).
 */
@AndroidEntryPoint
class BackgroundConnectionService : Service() {

    @Inject lateinit var webSocketClient: WebSocketClient
    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Not allowed into the foreground right now (started from the background without
            // an exemption); BackgroundConnection tries again when the app is opened.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        webSocketClient.connect()
        // Android restarts a sticky service on its own, also after the setting was turned off
        // or the account wiped: stop again in that case.
        scope.launch {
            if (!settingsRepository.getPrivacySettings().first().backgroundConnection) {
                ServiceCompat.stopForeground(this@BackgroundConnectionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, ChatNotificationManager.CHANNEL_BACKGROUND_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sotto")
            .setContentText(getString(R.string.notification_background_connected))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            // Its own group: never bundled with the chat notifications.
            .setGroup("com.sotto.app.CONNECTION")
            .setContentIntent(openApp)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 0x0BC01
    }
}
