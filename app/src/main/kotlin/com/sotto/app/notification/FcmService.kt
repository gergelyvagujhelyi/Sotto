package com.sotto.app.notification

import com.sotto.app.data.remote.WebSocketClient
import com.sotto.app.domain.repository.MessageRepository
import com.sotto.app.domain.repository.PushTokenRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.annotation.SuppressLint
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import com.sotto.app.worker.PushSyncWorker
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

// Pushes address this installation's Firebase installation ID (onRegistered), not an FCM
// token, so there's no onNewToken to implement (MissingFirebaseInstanceTokenRefresh).
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {

    @Inject lateinit var pushTokenRepository: PushTokenRepository
    @Inject lateinit var webSocketClient: WebSocketClient
    @Inject lateinit var messageRepository: MessageRepository

    // Firebase calls these on a background thread and stops the service as soon as
    // they return; work launched into a scope would be cancelled in onDestroy before
    // its first network call. Short work (registration, a push's own fetch) runs inside
    // the callback, within a budget; anything longer goes to an expedited job, which
    // Android lets finish.

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
        // Connect the WebSocket so buffered call signals are delivered, unless it's being
        // connected right now (a push that started the app) or is open and alive.
        webSocketClient.connectUnlessWorking()

        // Fetch the messages now, within the few seconds Android gives a push; notifications
        // are shown by MessageRepositoryImpl after decryption. Only if that fails or runs out
        // of time does the expedited job take over. Since 0.6.1 the job did every fetch, but
        // Android decides when a job runs: on a strict phone, or once the app had used up its
        // allowance for urgent work on a busy day, messages waited 20-70 s (the owner's
        // wife's phone, 2026-11-20). The job is still there for a slow start, such as a fresh
        // install on a phone's background cores, which ran past those seconds in 0.6.1.
        val fetched = runBlocking {
            fetchWithin(DIRECT_FETCH_BUDGET_MS) { messageRepository.fetchPendingAndWait() }
        }
        if (!fetched) {
            Log.w("FcmService", "Fetch on push didn't finish in ${DIRECT_FETCH_BUDGET_MS} ms; the job takes over")
            PushSyncWorker.enqueue(applicationContext)
        }
    }

    /**
     * Firebase dropped pushes it held for this phone: more than 100 waited while it was offline
     * (message pushes don't collapse, see the relay's push.py). Fetch everything now rather than
     * at the next 15-minute check, as one push would have.
     */
    override fun onDeletedMessages() {
        super.onDeletedMessages()
        webSocketClient.connectUnlessWorking()
        PushSyncWorker.enqueue(applicationContext)
    }

    private companion object {
        const val WORK_BUDGET_MS = 9_000L
    }
}
