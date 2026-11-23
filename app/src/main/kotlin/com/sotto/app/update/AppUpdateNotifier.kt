package com.sotto.app.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.sotto.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The "update ready" notification: tapping it opens [SourceDrop] (or its page). */
@Singleton
class AppUpdateNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The version last announced, so each one is announced once. */
    fun lastAnnouncedVersion(): String? = prefs.getString(KEY_ANNOUNCED, null)

    /** Posts the notification; true if it could (notifications allowed). */
    fun announce(update: AppUpdate.Ready): Boolean {
        if (!canNotify()) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a new version of Sotto is out"
            }
        )
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            SourceDrop.intent(context, update.version),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sotto ${update.version} is ready")
            .setContentText("Your relay already runs it. Tap to update with SourceDrop.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            return false // the permission went away just now
        }
        prefs.edit { putString(KEY_ANNOUNCED, update.version) }
        return true
    }

    /** Takes the notification away (the app is up to date now). */
    fun withdraw() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val PREFS = "app_update"
        const val KEY_ANNOUNCED = "announced_version"
        const val CHANNEL = "app_updates"
        const val NOTIFICATION_ID = 0x0BC03 // next to the background connection (0x0BC01) and push sync (0x0BC02)
    }
}
