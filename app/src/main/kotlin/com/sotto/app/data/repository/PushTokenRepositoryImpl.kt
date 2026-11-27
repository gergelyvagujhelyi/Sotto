package com.sotto.app.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sotto.app.crypto.KeyManager
import com.sotto.app.data.remote.ApiService
import com.sotto.app.data.remote.dto.PushTokenRequest
import com.sotto.app.domain.repository.PushTokenRepository
import com.google.android.gms.tasks.Task
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private val Context.pushDataStore by preferencesDataStore(name = "push_token")

@Singleton
class PushTokenRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService,
    private val keyManager: KeyManager,
    private val relayConfig: com.sotto.app.connection.RelayConfig,
) : PushTokenRepository {

    private val installationIdKey = stringPreferencesKey("fcm_fid")

    override suspend fun syncPushRegistration() {
        // Without an identity the server can't authenticate the registration;
        // onboarding calls this again once the identity exists.
        if (!hasIdentity()) return
        // Only the owner's relay can push to this app: any other never gets our Firebase ID.
        if (!relayConfig.canPush) return
        // Registers this installation with FCM. That needs Google Play services: without
        // them it fails (or never finishes) and nothing is sent, so the relay never pushes
        // to a phone that can't receive. FCM then also calls FcmService.onRegistered.
        val registered = withTimeoutOrNull(FIREBASE_TIMEOUT_MS) {
            awaitTask { FirebaseMessaging.getInstance().register() }
        }
        if (registered?.isSuccess != true) {
            Log.i(TAG, "No FCM registration: ${registered?.exceptionOrNull()?.message ?: "timed out"}")
            return
        }
        val installationId = withTimeoutOrNull(FIREBASE_TIMEOUT_MS) {
            awaitTask { FirebaseInstallations.getInstance().id }
        }?.getOrNull() ?: return
        registerInstallationId(installationId)
    }

    /**
     * What this process has registered with the relay: the account and installation ID.
     * Every app start asks FCM to register (syncPushRegistration), and FCM then calls
     * FcmService.onRegistered with the same ID, so each start sent the relay the same
     * registration twice. Each request costs a post-quantum signature, and a start woken by a
     * push has only seconds.
     */
    private val registered = PushRegistrationMemo()
    private val registerLock = Mutex()

    override suspend fun registerInstallationId(installationId: String) {
        if (!hasIdentity() || !relayConfig.canPush) return
        val userId = keyManager.getUserId() ?: return
        registerLock.withLock {
            if (registered.isRegistered(userId, installationId)) return
            try {
                apiService.registerPushToken(PushTokenRequest(fid = installationId))
                context.pushDataStore.edit { it[installationIdKey] = installationId }
                registered.remember(userId, installationId)
            } catch (e: Exception) {
                Log.w(TAG, "Push registration failed, will retry on next app start: ${e.message}")
            }
        }
    }

    override suspend fun registeredWithRelay(): Boolean =
        context.pushDataStore.data.first()[installationIdKey] != null

    override suspend fun clearLocal() {
        registered.forget()
        context.pushDataStore.edit { it.clear() }
    }

    private fun hasIdentity() = keyManager.hasIdentity() && keyManager.getUserId() != null

    /** The task's outcome; a failure also when Firebase isn't set up at all. */
    private suspend fun <T> awaitTask(start: () -> Task<T>): Result<T> = try {
        suspendCancellableCoroutine { cont ->
            start().addOnCompleteListener { task ->
                cont.resume(
                    if (task.isSuccessful) Result.success(task.result)
                    else Result.failure(task.exception ?: IllegalStateException("Firebase task failed")),
                )
            }
        }
    } catch (e: IllegalStateException) {
        // No default FirebaseApp (no google-services configuration in this build).
        Result.failure(e)
    }

    private companion object {
        const val TAG = "PushToken"
        const val FIREBASE_TIMEOUT_MS = 30_000L
    }
}

/**
 * Which account and installation ID this process has registered with the relay, so the same
 * registration isn't sent twice. A new account (after a wipe) or a new installation ID
 * registers again.
 */
internal class PushRegistrationMemo {
    @Volatile
    private var registered: Pair<String, String>? = null

    fun isRegistered(userId: String, installationId: String) = registered == (userId to installationId)

    fun remember(userId: String, installationId: String) {
        registered = userId to installationId
    }

    fun forget() {
        registered = null
    }
}
