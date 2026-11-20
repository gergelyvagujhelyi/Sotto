package com.sotto.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.sotto.app.data.remote.WebSocketClient
import com.sotto.app.data.repository.IdentityRepositoryImpl
import com.sotto.app.worker.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class SottoApp : Application(), Configuration.Provider {

    companion object {
        init {
            // Must load before Hilt injects the database (DI triggers before onCreate)
            System.loadLibrary("sqlcipher")

            // Android ships a stripped-down BC provider that lacks Ed25519/X25519/Kyber.
            // Replace it with the full Bouncy Castle 1.79 provider.
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var webSocketClient: WebSocketClient
    @Inject lateinit var identityRepository: IdentityRepositoryImpl
    @Inject lateinit var pushTokenRepository: com.sotto.app.domain.repository.PushTokenRepository
    @Inject lateinit var messageRepository: com.sotto.app.domain.repository.MessageRepository
    /**
     * Created eagerly: it is the only collector of call signals from the WebSocket.
     * When an FCM push starts the process for an incoming call, the server flushes
     * the buffered offer as soon as the socket connects; without a collector it
     * would be dropped (the SharedFlow has no replay).
     */
    @Inject lateinit var callManager: com.sotto.app.call.CallManager
    @Inject lateinit var backgroundConnection: com.sotto.app.connection.BackgroundConnection
    @Inject lateinit var relayConnectionPolicy: com.sotto.app.connection.RelayConnectionPolicy
    @Inject lateinit var relayConfig: com.sotto.app.connection.RelayConfig
    @Inject lateinit var keyManager: com.sotto.app.crypto.KeyManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Before anything talks to a relay: an account made before the relay was a
        // setting keeps the relay this build always used.
        relayConfig.migrate(keyManager.hasIdentity())

        // Recovery must complete before any identity access — block startup.
        // This is fast (only promotes staged keys if a rotation was interrupted).
        val promotedRotation = kotlinx.coroutines.runBlocking {
            identityRepository.recoverFromInterruptedKeyRotation()
        }
        appScope.launch {
            try {
                // Contacts still use sessions bound to our old keys: tell them.
                if (promotedRotation) identityRepository.notifyContactsOfRotation()
            } catch (_: Exception) {
            }
            // A rotation whose server answer we never saw: ask the server, and keep
            // asking until it answers (until then requests may fail authentication).
            var backoff = 5_000L
            while (true) {
                val settled = try {
                    identityRepository.resolvePendingRotation()
                } catch (_: Exception) {
                    false
                }
                if (settled) break
                kotlinx.coroutines.delay(backoff)
                backoff = (backoff * 2).coerceAtMost(5 * 60_000L)
            }
        }

        scheduleSyncWorker()

        // Connect WebSocket for real-time delivery (no-op if using mock backend)
        webSocketClient.connect()
        // Keep it connected while the app is closed, if the user turned that on.
        backgroundConnection.start()
        // Otherwise close it soon after the app leaves the screen, so the relay pushes.
        relayConnectionPolicy.start(this)

        appScope.launch {
            // Sends interrupted by a process death can't complete; make them retryable.
            try { messageRepository.failInterruptedSends() } catch (_: Exception) {}
            pushTokenRepository.syncPushRegistration()
        }

        // Disappearing messages: delete expired rows while the app is alive
        // (SyncWorker also does it, but only every 15+ minutes).
        appScope.launch {
            while (true) {
                try { messageRepository.deleteExpiredMessages() } catch (_: Exception) {}
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

    private fun scheduleSyncWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            15, TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "message_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest,
        )
    }
}
