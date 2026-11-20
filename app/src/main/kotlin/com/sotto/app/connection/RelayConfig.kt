package com.sotto.app.connection

import android.content.Context
import androidx.core.content.edit
import com.sotto.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The relay this app talks to. Release builds come with none: setup gets it from an invite
 * or from an address the user types, so the public app never points anyone at the owner's
 * server by itself. An account lives on one relay, so the address doesn't change while the
 * account exists; wiping the app clears it.
 *
 * Read synchronously on every request and socket connect (plain SharedPreferences), so the
 * HTTP clients never need rebuilding.
 */
@Singleton
class RelayConfig @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Like "https://relay.example.com"; null until setup chose one. */
    val baseUrl: String?
        get() = prefs.getString(KEY_URL, null) ?: BuildConfig.DEFAULT_RELAY_URL.ifEmpty { null }

    /** The relay's host, for showing where the account lives. */
    val host: String?
        get() = baseUrl?.substringAfter("://")

    /** Only the owner's relay holds the Firebase key that can wake this app. */
    val canPush: Boolean
        get() = pushAllowed(baseUrl, BuildConfig.OWNER_RELAY_URL)

    fun set(url: String) {
        // Committed at once: setup uses the relay right after choosing it.
        prefs.edit(commit = true) { putString(KEY_URL, url) }
    }

    fun clear() {
        prefs.edit(commit = true) { clear() }
    }

    /** Apps set up before the relay was a setting keep the relay they were built for. */
    fun migrate(hasIdentity: Boolean) {
        relayForExistingIdentity(hasIdentity, prefs.getString(KEY_URL, null), BuildConfig.LEGACY_RELAY_URL)
            ?.let(::set)
    }

    companion object {
        private const val PREFS = "relay"
        private const val KEY_URL = "base_url"

        /**
         * Plain-HTTP relays allowed in debug builds only: the emulator's host, a LAN name, and a
         * relay on the computer reached from a phone through `adb reverse tcp:8000 tcp:8000`.
         */
        val cleartextHosts: Set<String>
            get() = if (BuildConfig.DEBUG) setOf("10.0.2.2", "sotto.local", "localhost") else emptySet()
    }
}

/** What to store for an account made before the relay was a setting: the build's old relay. */
internal fun relayForExistingIdentity(hasIdentity: Boolean, stored: String?, legacy: String): String? =
    if (hasIdentity && stored == null && legacy.isNotEmpty()) legacy else null

/** Push only works through the owner's relay: no other relay has the app's Firebase key. */
internal fun pushAllowed(relay: String?, ownerRelay: String): Boolean =
    relay != null && ownerRelay.isNotEmpty() && relay == ownerRelay

/**
 * A typed or pasted relay address as "https://host[:port]", or null when it can't be one.
 * A bare host gets https; plain http only for [cleartextHosts]. The relay must sit at the
 * host's root (requests go to "/v1/…"), and an address with a user, query or fragment is
 * refused.
 */
internal fun normalizeRelayUrl(input: String, cleartextHosts: Set<String> = emptySet()): String? {
    val text = input.trim().trimEnd('/')
    if (text.isEmpty() || text.any { it.isWhitespace() }) return null
    val withScheme = if ("://" in text) text else "https://$text"
    val uri = try {
        java.net.URI(withScheme)
    } catch (e: Exception) {
        return null
    }
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
    when (scheme) {
        "https" -> Unit
        "http" -> if (host !in cleartextHosts) return null
        else -> return null
    }
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return "$scheme://$host$port"
}
