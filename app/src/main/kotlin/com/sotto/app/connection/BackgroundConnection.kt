package com.sotto.app.connection

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.sotto.app.BuildConfig
import com.sotto.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Runs [BackgroundConnectionService] while the "Background connection" setting is on. */
@Singleton
class BackgroundConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var following = false

    @Volatile
    private var enabled = false

    /** Follow the setting for the life of the process: start the service when on, stop it when off. */
    @Synchronized
    fun start() {
        if (following) return
        following = true
        scope.launch {
            settingsRepository.getPrivacySettings()
                .map { it.backgroundConnection }
                .distinctUntilChanged()
                .collect { on ->
                    enabled = on
                    if (on) startService() else stopService()
                }
        }
    }

    /** Start the service if the setting is on, e.g. when the app comes to the foreground. */
    fun ensureRunning() {
        if (enabled) startService()
    }

    /** Read the setting once and start the service if it's on (after a reboot or an app update). */
    suspend fun startIfEnabled() {
        if (settingsRepository.getPrivacySettings().first().backgroundConnection) startService()
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, BackgroundConnectionService::class.java))
        } catch (e: Exception) {
            // Android 12+ refuses foreground services started from the background unless the app
            // may ignore battery optimisation; the service starts the next time the app is opened.
            if (BuildConfig.DEBUG) android.util.Log.w(TAG, "Couldn't start the background connection", e)
        }
    }

    private fun stopService() {
        context.stopService(Intent(context, BackgroundConnectionService::class.java))
    }

    private companion object {
        const val TAG = "BackgroundConnection"
    }
}
