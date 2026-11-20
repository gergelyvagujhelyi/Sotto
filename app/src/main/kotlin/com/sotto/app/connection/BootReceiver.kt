package com.sotto.app.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sotto.app.worker.PushSyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * After a reboot or an app update: fetches the messages waiting on the relay, and brings the
 * background connection back if it's turned on.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var backgroundConnection: BackgroundConnection

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Starting the app in the background like this also starts a catch-up fetch (the socket
        // connects), with nothing keeping the app awake for it: on a slow start Android froze it
        // halfway. Messages that came while the phone was off have no push left to wake it (a
        // push lasts a minute), so they waited until the app was opened. The fetch job keeps the
        // app running until they're in.
        PushSyncWorker.enqueue(context)
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
