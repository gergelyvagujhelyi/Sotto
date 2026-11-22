package com.sotto.app.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.sotto.app.R
import com.sotto.app.MainActivity
import com.sotto.app.data.local.dao.MessageDao
import com.sotto.app.domain.model.LockScreenPreviewMode
import com.sotto.app.domain.model.PrivacySettings
import com.sotto.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val messageDao: MessageDao,
) {
    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)

        // Delete legacy channel whose VISIBILITY_SECRET default caps per-notification visibility
        manager.deleteNotificationChannel(CHANNEL_MESSAGES_LEGACY)

        val messageChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "New message notifications"
            // Leave lockscreenVisibility at default (VISIBILITY_PRIVATE) so
            // per-notification visibility set dynamically from privacy settings
            // is not capped by the channel.
        }

        val missedCallChannel = NotificationChannel(
            CHANNEL_MISSED_CALLS,
            "Missed Calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Missed call notifications"
        }

        // Never used for anything; replaced by the background connection's channel.
        manager.deleteNotificationChannel(CHANNEL_SERVICE_LEGACY)
        // The background connection's permanent notification: lowest importance, so it stays
        // silent and out of the status bar.
        val backgroundConnectionChannel = NotificationChannel(
            CHANNEL_BACKGROUND_CONNECTION,
            "Background connection",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Shown while Sotto stays connected for messages and calls"
            setSound(null, null)
            setShowBadge(false)
        }

        manager.createNotificationChannel(messageChannel)
        manager.createNotificationChannel(missedCallChannel)
        manager.createNotificationChannel(backgroundConnectionChannel)
    }

    suspend fun showMessageNotification(
        senderId: String,
        senderName: String,
        messageBody: String,
        conversationId: String? = null,
        /** [messageBody] is a reaction: it isn't stored, so it's listed after the unread messages. */
        isReaction: Boolean = false,
    ) {
        if (!hasNotificationPermission()) return

        val settings = settingsRepository.getPrivacySettings().firstOrNull() ?: PrivacySettings()

        // Every message since the user last opened the chat, not only this one.
        val unread = conversationId?.let { id ->
            messageDao.getUnseenIncomingTexts(id, NotificationCompat.MessagingStyle.MAXIMUM_RETAINED_MESSAGES)
                .map {
                    val kind = com.sotto.app.domain.model.MediaKind.fromWire(it.mediaKind)
                    NotifiedMessage(com.sotto.app.domain.model.previewText(kind, it.text), it.timestamp)
                }
        }.orEmpty()
        val content = messageNotificationContent(
            mode = settings.lockScreenPreview,
            senderName = senderName,
            unread = unread,
            unreadCount = conversationId?.let { messageDao.countUnseenIncoming(it) } ?: 0,
            latest = NotifiedMessage(messageBody, System.currentTimeMillis()),
            latestIsReaction = isReaction,
        )

        val visibility = when (settings.lockScreenPreview) {
            LockScreenPreviewMode.SHOW_ALL -> NotificationCompat.VISIBILITY_PUBLIC
            LockScreenPreviewMode.SENDER_ONLY -> NotificationCompat.VISIBILITY_PUBLIC
            LockScreenPreviewMode.HIDE_BODY -> NotificationCompat.VISIBILITY_PRIVATE
            LockScreenPreviewMode.HIDE_ALL -> NotificationCompat.VISIBILITY_SECRET
        }

        val deepLinkIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (conversationId != null) {
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_CONTACT_ID, senderId)
            }
        }

        val notificationId = (conversationId?.hashCode() ?: senderId.hashCode()) and Int.MAX_VALUE

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            deepLinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(visibility)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_CHATS)
        if (content.messages.isNotEmpty()) {
            val sender = Person.Builder().setName(senderName).setKey(senderId).build()
            val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
            content.messages.forEach { style.addMessage(it.text, it.timestamp, sender) }
            builder.setStyle(style)
        }
        if (content.count > 1) builder.setNumber(content.count)

        post(notificationId, builder.build())
        postGroupSummary(visibility)
    }

    suspend fun showMissedCallNotification(
        callerId: String,
        callerName: String,
        conversationId: String? = null,
    ) {
        if (!hasNotificationPermission()) return

        val settings = settingsRepository.getPrivacySettings().firstOrNull() ?: PrivacySettings()

        val notificationId = (MISSED_CALL_PREFIX + callerId).hashCode() and Int.MAX_VALUE
        // One notification per caller: a call missed while it still shows counts on from it, so a
        // second missed call no longer replaces the first. Seeing it (opening it or the chat, or
        // swiping it away) starts the count again.
        val missed = (shownMissedCalls(notificationId) ?: 0) + 1
        val (title, body) = when (settings.lockScreenPreview) {
            LockScreenPreviewMode.SHOW_ALL,
            LockScreenPreviewMode.SENDER_ONLY,
            LockScreenPreviewMode.HIDE_BODY -> callerName to missedCallsText(missed)
            LockScreenPreviewMode.HIDE_ALL -> "Sotto" to missedCallsText(missed)
        }

        val visibility = when (settings.lockScreenPreview) {
            LockScreenPreviewMode.SHOW_ALL -> NotificationCompat.VISIBILITY_PUBLIC
            LockScreenPreviewMode.SENDER_ONLY -> NotificationCompat.VISIBILITY_PUBLIC
            LockScreenPreviewMode.HIDE_BODY -> NotificationCompat.VISIBILITY_PRIVATE
            LockScreenPreviewMode.HIDE_ALL -> NotificationCompat.VISIBILITY_SECRET
        }

        val deepLinkIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (conversationId != null) {
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_CONTACT_ID, callerId)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            deepLinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MISSED_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(visibility)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setGroup(GROUP_CHATS)
            .setNumber(missed)
            // Its time is the last call's, and the count rides along for the next one.
            .addExtras(Bundle().apply { putInt(EXTRA_MISSED_CALLS, missed) })
            .build()

        post(notificationId, notification)
        postGroupSummary(visibility)
    }

    /**
     * Our own summary for the chat notifications. Without one, Android bundles several
     * notifications from the app under a summary it makes itself, and tapping that only
     * launches the app: it opened on whatever screen was last shown (Settings, say)
     * instead of the chats. Ours opens the chat list; each notification in the bundle
     * still opens its own chat.
     */
    private fun postGroupSummary(visibility: Int) {
        val openChats = PendingIntent.getActivity(
            context,
            GROUP_SUMMARY_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_CHATS, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val summary = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sotto")
            .setContentText("New messages")
            .setVisibility(visibility)
            .setContentIntent(openChats)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_CHATS)
            .setGroupSummary(true)
            // The notifications in the group already alerted; the summary stays quiet.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .build()
        post(GROUP_SUMMARY_ID, summary)
    }

    /**
     * Post [notification] if notifications are allowed. The callers check first too, but the
     * user can take the permission back at any moment, and then Android throws instead.
     */
    private fun post(id: Int, notification: android.app.Notification) {
        if (!hasNotificationPermission()) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // The permission went away just now: nothing to show.
        }
    }

    /**
     * How many missed calls the notification [id] counts while it shows; null when it doesn't.
     * One posted before the count existed stands for a single call.
     */
    private fun shownMissedCalls(id: Int): Int? =
        context.getSystemService(NotificationManager::class.java)?.activeNotifications
            ?.firstOrNull { it.id == id }
            ?.notification?.extras?.getInt(EXTRA_MISSED_CALLS, 1)

    /** Drop the summary once no chat notification is left under it. */
    private fun cancelEmptyGroupSummary() {
        val active = context.getSystemService(NotificationManager::class.java)?.activeNotifications ?: return
        if (active.none { it.id != GROUP_SUMMARY_ID && it.notification.group == GROUP_CHATS }) {
            NotificationManagerCompat.from(context).cancel(GROUP_SUMMARY_ID)
        }
    }

    fun cancelNotification(conversationId: String, contactId: String? = null) {
        val compat = NotificationManagerCompat.from(context)
        compat.cancel(conversationId.hashCode() and Int.MAX_VALUE)
        if (contactId != null) {
            compat.cancel((MISSED_CALL_PREFIX + contactId).hashCode() and Int.MAX_VALUE)
        }
        cancelEmptyGroupSummary()
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    companion object {
        /** Legacy channel ID — had VISIBILITY_SECRET that capped per-notification visibility. */
        private const val CHANNEL_MESSAGES_LEGACY = "messages"
        const val CHANNEL_MESSAGES = "messages_v2"
        const val CHANNEL_MISSED_CALLS = "missed_calls"
        private const val CHANNEL_SERVICE_LEGACY = "background_service"
        const val CHANNEL_BACKGROUND_CONNECTION = "background_connection"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_CONTACT_ID = "contact_id"
        /** Set on the summary's intent: open the chat list. */
        const val EXTRA_OPEN_CHATS = "open_chats"
        private const val GROUP_CHATS = "com.sotto.app.CHATS"
        private const val GROUP_SUMMARY_ID = 0x5C11
        private const val MISSED_CALL_PREFIX = "missed-call-"
        /** In a missed-call notification's extras: how many calls it counts. */
        private const val EXTRA_MISSED_CALLS = "com.sotto.app.MISSED_CALLS"
    }
}
