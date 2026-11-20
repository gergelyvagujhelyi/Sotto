package com.sotto.app.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.sotto.app.R
import com.sotto.app.domain.model.CallState
import com.sotto.app.domain.model.CallStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * System UI for calls: the ringing notification for an incoming call (works
 * while the app is in the background or the screen is locked) and the ongoing
 * call notification shown by [CallService].
 */
@Singleton
class CallNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    init {
        createChannels()
        // A new process has no call: a ringing notification left by a process that
        // died mid-call would offer to accept a call that no longer exists.
        NotificationManagerCompat.from(context).cancel(INCOMING_ID)
        NotificationManagerCompat.from(context).cancel(ONGOING_ID)
    }

    private fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        // High importance (heads-up) but silent: the app rings and vibrates itself
        // (IncomingRinger), which doesn't depend on how Android treats the notification. A
        // channel's sound can't be changed once it exists, hence a new id.
        val incoming = NotificationChannel(
            CHANNEL_INCOMING,
            "Incoming calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Shows incoming voice calls; the app rings and vibrates for them"
            setSound(null, null)
            enableVibration(false)
        }
        val ongoing = NotificationChannel(
            CHANNEL_ONGOING,
            "Ongoing calls",
            // DEFAULT (silent): LOW tucks it into the collapsed silent section,
            // hiding the only way back to the call / hang up from outside the app.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Shown while a call is in progress"
            setSound(null, null)
        }
        manager.deleteNotificationChannel("ongoing_calls") // pre-release LOW-importance channel
        manager.deleteNotificationChannel("incoming_calls") // up to 0.5.11: the notification rang itself
        manager.createNotificationChannel(incoming)
        manager.createNotificationChannel(ongoing)
    }

    /** Show [state] (an incoming call that is ringing); the ringing itself is IncomingRinger's. */
    fun showIncoming(state: CallState) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        try {
            NotificationManagerCompat.from(context).notify(INCOMING_ID, buildIncoming(state))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked: the in-app call screen still works.
        }
    }

    /**
     * The incoming-call notification: a heads-up notification with Decline and Answer, never a
     * full-screen alert. Answer and a tap open [CallActivity], which may show over the lock
     * screen; Decline needs no screen. Not a CallStyle notification: Android accepts those only
     * with a full-screen alert or from a foreground service.
     */
    fun buildIncoming(state: CallState): Notification {
        val open = activityIntent(ACTION_SHOW_CALL, state.callId, REQUEST_SHOW)
        val answer = activityIntent(ACTION_ACCEPT_CALL, state.callId, REQUEST_ACCEPT)
        val decline = receiverIntent(CallActionReceiver.ACTION_DECLINE, state.callId, REQUEST_DECLINE)

        // Caller name stays off the lock screen (only "Incoming call" is public).
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Incoming call")
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.peerDisplayName)
            .setContentText("Incoming encrypted call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(open)
            .addPerson(person(state))
            .addAction(0, "Decline", decline)
            .addAction(0, "Answer", answer)
            .build()
        return notification
    }

    fun cancelIncoming() {
        NotificationManagerCompat.from(context).cancel(INCOMING_ID)
    }

    /** Notification for the call foreground service. */
    fun buildOngoing(state: CallState?): Notification {
        val callId = state?.callId ?: ""
        val open = activityIntent(ACTION_SHOW_CALL, callId, REQUEST_SHOW)
        val hangUp = receiverIntent(CallActionReceiver.ACTION_HANG_UP, callId, REQUEST_HANG_UP)
        val text = when (state?.status) {
            CallStatus.CONNECTED -> if (state.reconnecting) "Reconnecting…" else "Encrypted call"
            CallStatus.RINGING -> "Ringing…"
            else -> "Connecting…"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state?.peerDisplayName ?: "Call")
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(state), hangUp))
        state?.connectedAt?.let { builder.setWhen(it).setUsesChronometer(true).setShowWhen(true) }
        return builder.build()
    }

    /** Refresh the ongoing-call notification. Only [CallService] calls this, while in the foreground. */
    fun updateOngoing(state: CallState) {
        try {
            NotificationManagerCompat.from(context).notify(ONGOING_ID, buildOngoing(state))
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
            // CallStyle without a foreground service is rejected: never crash for it.
        }
    }

    private fun person(state: CallState?): Person =
        Person.Builder()
            .setName(state?.peerDisplayName ?: "Call")
            .setImportant(true)
            .build()

    private fun activityIntent(action: String, callId: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, CallActivity::class.java)
            .setAction(action)
            .putExtra(EXTRA_CALL_ID, callId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun receiverIntent(action: String, callId: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_CALL_ID, callId)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_INCOMING = "incoming_calls_v2"
        const val CHANNEL_ONGOING = "call_in_progress"
        const val INCOMING_ID = 0x0CA11
        const val ONGOING_ID = 0x0CA12

        const val ACTION_SHOW_CALL = "com.sotto.app.action.SHOW_CALL"
        const val ACTION_ACCEPT_CALL = "com.sotto.app.action.ACCEPT_CALL"
        const val EXTRA_CALL_ID = "call_id"

        private const val REQUEST_SHOW = 7001
        private const val REQUEST_ACCEPT = 7002
        private const val REQUEST_DECLINE = 7003
        private const val REQUEST_HANG_UP = 7004
    }
}
