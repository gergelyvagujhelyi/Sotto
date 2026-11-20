package com.sotto.app.notification

import com.sotto.app.data.remote.WebSocketClient
import com.sotto.app.domain.repository.MessageRepository
import com.sotto.app.domain.repository.PushTokenRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.sotto.app.worker.SyncWorker
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {

    @Inject lateinit var pushTokenRepository: PushTokenRepository
    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var webSocketClient: WebSocketClient

    // Firebase calls these on a background thread and stops the service as soon as
    // they return; work launched into a scope would be cancelled in onDestroy before
    // its first network call. So do the work inside the callback (Firebase allows
    // several seconds for a high-priority message) and hand anything unfinished
    // to WorkManager, which survives the service.

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

        // Fetch pending messages — notifications are shown by
        // MessageRepositoryImpl after decryption with actual content.
        try {
            runBlocking { withTimeout(WORK_BUDGET_MS) { messageRepository.fetchPendingFromServer() } }
        } catch (e: Exception) {
            Log.w("FcmService", "FCM-triggered message fetch failed, deferring: ${e.message}")
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                "fcm-sync",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .build(),
            )
        }
    }

    private companion object {
        const val WORK_BUDGET_MS = 9_000L
    }
}
