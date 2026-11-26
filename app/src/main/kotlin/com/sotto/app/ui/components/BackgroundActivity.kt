package com.sotto.app.ui.components

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.net.toUri

/**
 * Android's battery optimisation for Sotto: while it's on, Android (and phone makers' own
 * battery savers, Samsung's "sleeping apps") may hold the app back in the background, so a
 * message or call can wait until Sotto is opened. Lifting it is Android's "Unrestricted".
 */
object BackgroundActivity {

    /** Android may still hold Sotto back in the background. */
    fun isRestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == false

    /**
     * Asks Android to let Sotto run in the background (its own yes/no dialog), or opens the
     * list of apps where it's set where that dialog doesn't exist. Lint flags the request
     * (BatteryLife); a chat app whose messages and calls wait without it is what the exemption
     * is for. app/lint.xml says so too: lint has ignored a @SuppressLint here before.
     */
    @SuppressLint("BatteryLife")
    fun requestUnrestricted(context: Context) {
        val request = Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:${context.packageName}".toUri(),
        )
        try {
            context.startActivity(request)
        } catch (_: ActivityNotFoundException) {
            runCatching {
                context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}
