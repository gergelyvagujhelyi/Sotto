package com.sotto.app.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Brings the background connection back after a reboot or an app update, if it's turned on. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var backgroundConnection: BackgroundConnection

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Both broadcasts allow starting a foreground service; reading the setting is a suspend
        // call, so keep the broadcast alive until the start is done.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                backgroundConnection.startIfEnabled()
            } finally {
                pending.finish()
            }
        }
    }
}
