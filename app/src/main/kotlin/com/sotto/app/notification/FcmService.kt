package com.sotto.app.notification

import com.sotto.app.data.remote.WebSocketClient
import com.sotto.app.domain.repository.PushTokenRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import com.sotto.app.worker.PushSyncWorker
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {

    @Inject lateinit var pushTokenRepository: PushTokenRepository
    @Inject lateinit var webSocketClient: WebSocketClient

    // Firebase calls these on a background thread and stops the service as soon as
    // they return; work launched into a scope would be cancelled in onDestroy before
    // its first network call. A short call (registration) runs inside the callback;
    // fetching messages goes to an expedited job, which Android lets finish.

    override fun onRegistered(installationId: String) {
        super.onRegistered(installationId)
        // FCM registered this installation (app start, a register() call, or a new installation
        // ID after a restore or data clear): tell the relay where to push. No-op before
        // onboarding; onboarding and every app start register again.
        try {
            runBlocking { withTimeout(WORK_BUDGET_MS) { pushTokenRepository.registerInstallationId(installationId) } }
        } catch (e: Exception) {
            Log.w("FcmService", "Push registration failed, will retry on next start: ${e.message}")
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // The push notification is a "wake-up" signal only.
        // We never send message content through FCM — the encrypted envelope
        // is fetched directly from the relay server.
        // Ensure WebSocket is connected so buffered call signals are delivered
        webSocketClient.ensureConnected()

        // Fetch pending messages in an expedited job; notifications are shown by
        // MessageRepositoryImpl after decryption with actual content. Not here: Android
        // gives a push only a few seconds, and a slow start (a fresh install on a phone's
        // background cores) ran past them, so the app was frozen halfway and the message
        // waited until it was opened (PushSyncWorker).
        PushSyncWorker.enqueue(applicationContext)
    }

    private companion object {
        const val WORK_BUDGET_MS = 9_000L
    }
}
