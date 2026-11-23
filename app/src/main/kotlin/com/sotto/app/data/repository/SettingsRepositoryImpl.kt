package com.sotto.app.data.repository

import android.annotation.SuppressLint
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sotto.app.crypto.KeyManager
import com.sotto.app.crypto.SessionResetSender
import com.sotto.app.crypto.SessionResetSender.Companion.CTRL_ACCOUNT_DELETED
import com.sotto.app.data.local.AppDatabase
import com.sotto.app.data.local.dao.ContactDao
import com.sotto.app.data.local.dao.ConversationDao
import com.sotto.app.data.local.dao.MessageDao
import com.sotto.app.data.remote.ApiService
import com.sotto.app.data.remote.WebSocketClient
import com.sotto.app.domain.model.DisappearingDuration
import com.sotto.app.domain.model.LockScreenPreviewMode
import com.sotto.app.domain.model.PrivacySettings
import com.sotto.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "privacy_settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val keyManager: KeyManager,
    private val apiService: ApiService,
    private val webSocketClient: WebSocketClient,
    private val sessionResetSender: SessionResetSender,
    private val pushTokenRepository: com.sotto.app.domain.repository.PushTokenRepository,
    private val relayConfig: com.sotto.app.connection.RelayConfig,
) : SettingsRepository {

    override fun getPrivacySettings(): Flow<PrivacySettings> {
        return context.settingsDataStore.data.map { prefs ->
            PrivacySettings(
                lockScreenPreview = prefs[LOCK_SCREEN_PREVIEW]
                    ?.let { LockScreenPreviewMode.valueOf(it) }
                    ?: LockScreenPreviewMode.SHOW_ALL,
                disappearingMessagesDefault = prefs[DISAPPEARING_DEFAULT]
                    ?.let { DisappearingDuration.valueOf(it) }
                    ?: DisappearingDuration.OFF,
                readReceipts = prefs[READ_RECEIPTS] ?: true,
                screenSecurity = prefs[SCREEN_SECURITY] ?: false,
                localDataRetentionDays = prefs[DATA_RETENTION_DAYS] ?: 0,
                backgroundConnection = prefs[BACKGROUND_CONNECTION] ?: false,
                directCalls = prefs[DIRECT_CALLS] ?: true,
                highQualityCalls = prefs[HIGH_QUALITY_CALLS] ?: false,
                levelVoices = prefs[LEVEL_VOICES] ?: false,
                webRtcVoiceProcessing = prefs[WEBRTC_VOICE_PROCESSING] ?: false,
                reduceAnimations = prefs[REDUCE_ANIMATIONS] ?: false,
            )
        }
    }

    override suspend fun updatePrivacySettings(settings: PrivacySettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[LOCK_SCREEN_PREVIEW] = settings.lockScreenPreview.name
            prefs[DISAPPEARING_DEFAULT] = settings.disappearingMessagesDefault.name
            prefs[READ_RECEIPTS] = settings.readReceipts
            prefs[SCREEN_SECURITY] = settings.screenSecurity
            prefs[DATA_RETENTION_DAYS] = settings.localDataRetentionDays
            prefs[BACKGROUND_CONNECTION] = settings.backgroundConnection
            prefs[DIRECT_CALLS] = settings.directCalls
            prefs[HIGH_QUALITY_CALLS] = settings.highQualityCalls
            prefs[LEVEL_VOICES] = settings.levelVoices
            prefs[WEBRTC_VOICE_PROCESSING] = settings.webRtcVoiceProcessing
            prefs[REDUCE_ANIMATIONS] = settings.reduceAnimations
        }
    }

    /**
     * Not cancellable: leaving the settings screen mid-way must not stop between
     * telling contacts we're gone and deleting the account, or between deleting
     * the server identity and wiping the local keys.
     */
    override suspend fun wipeLocalData() = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        val senderId = keyManager.getUserId()
        if (senderId != null) {
            // Notify contacts — best-effort
            try {
                val contacts = contactDao.getAll().first()
                for (contact in contacts) {
                    try {
                        sessionResetSender.send(contact.userId, SessionResetSender.CTRL_ACCOUNT_DELETED, waitForRateLimit = true)
                    } catch (_: Exception) { /* best effort */ }
                }
            } catch (_: Exception) { /* best effort */ }

            // Delete server identity BEFORE wiping local keys.
            // If this fails, keys are preserved so the user can retry.
            apiService.deleteIdentity()
        }

        // Server identity deleted (or no identity) — wipe local state
        wipeLocal()
    }

    override suspend fun retryServerDeletion() = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        apiService.deleteIdentity()
        wipeLocal()
    }

    override suspend fun wipeLocalOnly() = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        wipeLocal()
    }

    // stopService below finds the service by its component, so a new Intent stops it
    // (lint's ImplicitSamInstance takes stopService for a listener removal).
    @SuppressLint("ImplicitSamInstance")
    private suspend fun wipeLocal() {
        webSocketClient.disconnect()
        // The setting is wiped below; stop its service so it doesn't keep the wiped app connected.
        context.stopService(
            android.content.Intent(context, com.sotto.app.connection.BackgroundConnectionService::class.java),
        )
        // Posted notifications can show sender names and message bodies and survive
        // the process exit below; background work would run against the wiped state.
        androidx.core.app.NotificationManagerCompat.from(context).cancelAll()
        androidx.work.WorkManager.getInstance(context).cancelAllWork()
        try { pushTokenRepository.clearLocal() } catch (_: Exception) {}
        messageDao.deleteAll()
        // Voice messages and photos are files beside the database.
        java.io.File(context.filesDir, "media").deleteRecursively()
        // Decrypted copies made for opening files, and records of unfinished uploads.
        java.io.File(context.cacheDir, com.sotto.app.data.media.FileDrop.OPEN_DIR).deleteRecursively()
        java.io.File(context.filesDir, "transfers").deleteRecursively()
        conversationDao.deleteAll()
        contactDao.deleteAll()
        database.close()
        keyManager.wipeAll()
        context.deleteDatabase("sotto.db")
        context.settingsDataStore.edit { it.clear() }
        // The account was on this relay; starting over may pick another.
        relayConfig.clear()
        // Kill the process so the Hilt singleton graph (including the now-closed
        // AppDatabase) is fully recreated on next launch.
        kotlin.system.exitProcess(0)
    }

    companion object {
        // New _v2 keys (0.5.6): lock screen notifications now show sender and message, read
        // receipts default on and screen security off. The old keys were written along with
        // any other privacy setting, so a stored old default can't be told from a choice;
        // every install moves to the new defaults once.
        private val LOCK_SCREEN_PREVIEW = stringPreferencesKey("lock_screen_preview_v2")
        private val DISAPPEARING_DEFAULT = stringPreferencesKey("disappearing_default")
        private val READ_RECEIPTS = booleanPreferencesKey("read_receipts_v2")
        private val SCREEN_SECURITY = booleanPreferencesKey("screen_security_v2")
        // New key: the old one defaulted to 90 and was written along with any other
        // privacy setting, so it can't tell a user's choice from that default.
        // Enforcing it would silently delete everyone's messages older than 90 days.
        private val DATA_RETENTION_DAYS = intPreferencesKey("data_retention_days_v2")
        private val BACKGROUND_CONNECTION = booleanPreferencesKey("background_connection")
        private val DIRECT_CALLS = booleanPreferencesKey("direct_calls")
        private val HIGH_QUALITY_CALLS = booleanPreferencesKey("high_quality_calls")
        private val LEVEL_VOICES = booleanPreferencesKey("level_voices")
        private val WEBRTC_VOICE_PROCESSING = booleanPreferencesKey("webrtc_voice_processing")
        private val REDUCE_ANIMATIONS = booleanPreferencesKey("reduce_animations")
    }
}
