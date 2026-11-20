package com.sotto.app.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's current default network (Wi-Fi, mobile …) as a handle, null while there is
 * none. Calls watch it to reconnect after a switch. One callback for the app's lifetime:
 * Android limits how many an app may register.
 */
@Singleton
class DefaultNetworkMonitor @Inject constructor(@ApplicationContext context: Context) {
    private val _defaultNetwork = MutableStateFlow<Long?>(null)
    val defaultNetwork: StateFlow<Long?> = _defaultNetwork.asStateFlow()

    init {
        try {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        _defaultNetwork.value = network.networkHandle
                    }

                    override fun onLost(network: Network) {
                        if (_defaultNetwork.value == network.networkHandle) _defaultNetwork.value = null
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (e: Exception) {
            Log.w("DefaultNetworkMonitor", "Can't watch the default network: ${e.message}")
        }
    }
}
