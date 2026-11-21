package com.sotto.app.update

import com.sotto.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the relay runs a newer Sotto than this app, and whether that one is out. Every
 * answer from the relay says it: the HTTP headers (KtorApiService), the socket's sign-in and
 * its app_release message (WebSocketClient), and a push wakes the app to fetch (so headers
 * again). The chat list shows [state]; once the app is out, [notifier] announces it, once
 * per version.
 */
@Singleton
class AppUpdates @Inject constructor(
    private val notifier: AppUpdateNotifier,
) {
    internal var appVersion: String = BuildConfig.VERSION_NAME

    private val _state = MutableStateFlow<AppUpdate>(AppUpdate.None)
    val state: StateFlow<AppUpdate> = _state.asStateFlow()
    private var heard = false

    /** The relay said it runs [relayVersion], and whether that version's app is out. */
    @Synchronized
    fun report(relayVersion: String, appReady: Boolean) {
        val update = AppUpdate.of(appVersion, relayVersion, appReady)
        if (heard && update == _state.value) return
        heard = true
        _state.value = update
        when (update) {
            is AppUpdate.Ready -> if (notifier.lastAnnouncedVersion() != update.version) notifier.announce(update)
            AppUpdate.None -> notifier.withdraw()
            is AppUpdate.OnItsWay -> Unit
        }
    }

    companion object {
        /** The relay's version, on every HTTP answer (relay 0.7.3+). */
        const val RELAY_VERSION_HEADER = "Sotto-Relay-Version"
        /** "true" once the app of that version is out. */
        const val APP_READY_HEADER = "Sotto-App-Ready"
    }
}
