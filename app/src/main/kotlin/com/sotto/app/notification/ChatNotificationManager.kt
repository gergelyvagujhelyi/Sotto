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
import com.sotto.app.MainActivity
import com.sotto.app.R
import com.sotto.app.data.local.dao.MessageDao
import com.sotto.app.domain.model.LockScreenPreviewMode
import com.sotto.app.domain.model.PrivacySettings
import com.sotto.app.domain.repository.SettingsRepository
import com.sotto.app.ui.text.resolve
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
    /**
     * The channels in use for 1:1 and group messages; see [SoundChannelIds]. Declared before
     * init, which sets them.
     */
    @Volatile private var messagesChannel = SoundChannelIds.MESSAGES.current(emptyList())
    @Volatile private var groupsChannel = SoundChannelIds.GROUPS.current(emptyList())

    init {
        createNotificationChannels()
    }

    /** Makes the channels, or renames them into the current language (MainActivity, after a language switch). */
    @Synchronized
    fun createNotificationChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)

        // Delete legacy channel whose VISIBILITY_SECRET default caps per-notification visibility
        manager.deleteNotificationChannel(CHANNEL_MESSAGES_LEGACY)

        val existing = manager.notificationChannels.map { it.id }
        messagesChannel = SoundChannelIds.MESSAGES.current(existing)
        groupsChannel = SoundChannelIds.GROUPS.current(existing)
        // Older generations left by a sound change that stopped halfway.
        val stale = SoundChannelIds.MESSAGES.stale(existing, messagesChannel) + SoundChannelIds.GROUPS.stale(existing, groupsChannel)
        stale.forEach { manager.deleteNotificationChannel(it) }
        if (stale.isNotEmpty()) cancelEmptyGroupSummary()

        val missedCallChannel = NotificationChannel(
            CHANNEL_MISSED_CALLS,
            context.getString(R.string.notification_channel_missed_calls),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_missed_calls_description)
        }

        // Never used for anything; replaced by the background connection's channel.
        manager.deleteNotificationChannel(CHANNEL_SERVICE_LEGACY)
        // The background connection's permanent notification: lowest importance, so it stays
        // silent and out of the status bar.
        val backgroundConnectionChannel = NotificationChannel(
            CHANNEL_BACKGROUND_CONNECTION,
            context.getString(R.string.notification_channel_background),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = context.getString(R.string.notification_channel_background_description)
            setSound(null, null)
            setShowBadge(false)
        }

        // For a channel that already exists, Android takes only the new name and description.
        manager.createNotificationChannel(messageChannel(SoundKind.MESSAGES, messagesChannel))
        // Up to 0.7.26 group messages came on the messages channel: their own starts as the user
        // had set that one (silenced or off, say), not with Sotto's defaults.
        val groupsFrom = if (groupsChannel in existing) null else manager.getNotificationChannel(messagesChannel)
        manager.createNotificationChannel(
            messageChannel(SoundKind.GROUPS, groupsChannel).apply { groupsFrom?.let { carryOver(it, it.sound) } },
        )
        manager.createNotificationChannel(missedCallChannel)
        manager.createNotificationChannel(backgroundConnectionChannel)
    }

    /** A new channel for 1:1 or group messages, as Sotto makes it (the phone's notification sound). */
    private fun messageChannel(kind: SoundKind, id: String) = NotificationChannel(
        id,
        context.getString(if (kind == SoundKind.GROUPS) R.string.notification_channel_groups else R.string.notification_channel_messages),
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        description = context.getString(
            if (kind == SoundKind.GROUPS) R.string.notification_channel_groups_description
            else R.string.notification_channel_messages_description,
        )
        // Leave lockscreenVisibility at default (VISIBILITY_PRIVATE) so
        // per-notification visibility set dynamically from privacy settings
        // is not capped by the channel.
    }

    private fun channelIds(kind: SoundKind) = when (kind) {
        SoundKind.GROUPS -> SoundChannelIds.GROUPS
        else -> SoundChannelIds.MESSAGES
    }

    private fun channelId(kind: SoundKind) = if (kind == SoundKind.GROUPS) groupsChannel else messagesChannel

    /**
     * The sound 1:1 or group messages make now, null for none. Read from the channel, so a
     * sound the user chose in Android's settings shows too.
     */
    fun messageSound(kind: SoundKind): android.net.Uri? =
        context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(channelId(kind))
            ?.sound

    /**
     * Give 1:1 or group messages the sound [uri] (null: none). Android doesn't let an app change
     * a channel's sound, so this makes the channel anew (see [SoundChannelIds]) with whatever
     * else the user set on the old one, and deletes the old one. Its notifications go with it.
     */
    @Synchronized
    fun setMessageSound(kind: SoundKind, uri: android.net.Uri?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val ids = channelIds(kind)
        val oldId = channelId(kind)
        val old = manager.getNotificationChannel(oldId)
        // The sound it has already: a new channel would only drop the notifications showing.
        if (old != null && old.sound == uri) return
        val newId = ids.next(oldId)
        val replacement = messageChannel(kind, newId).apply {
            if (old != null) carryOver(old, uri) else setSound(uri, android.app.Notification.AUDIO_ATTRIBUTES_DEFAULT)
        }
        manager.createNotificationChannel(replacement)
        if (kind == SoundKind.GROUPS) groupsChannel = newId else messagesChannel = newId
        ids.stale(manager.notificationChannels.map { it.id }, keep = newId)
            .forEach { manager.deleteNotificationChannel(it) }
        // The deleted channel's notifications went with it; the summary over them may be all that's left.
        cancelEmptyGroupSummary()
    }

    /**
     * What the user set on [from], on this channel not yet made, with the sound [sound].
     * "Override Do Not Disturb" stays the user's to turn on, never Sotto's; Android may also
     * drop a lock screen choice made in its settings.
     */
    private fun NotificationChannel.carryOver(from: NotificationChannel, sound: android.net.Uri?) {
        setSound(sound, from.audioAttributes ?: android.app.Notification.AUDIO_ATTRIBUTES_DEFAULT)
        importance = from.importance
        lockscreenVisibility = from.lockscreenVisibility
        setShowBadge(from.canShowBadge())
        enableVibration(from.shouldVibrate())
        vibrationPattern = from.vibrationPattern
        enableLights(from.shouldShowLights())
        lightColor = from.lightColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setAllowBubbles(from.canBubble())
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
                    NotifiedMessage(com.sotto.app.domain.model.previewUiText(kind, it.text, it.mediaName.orEmpty()).resolve(context), it.timestamp)
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

        val builder = NotificationCompat.Builder(context, messagesChannel) // the channel: postOn
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text.resolve(context))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(visibility)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_CHATS)
        if (content.messages.isNotEmpty()) {
            val sender = Person.Builder().setName(senderName).setKey(senderId).build()
            val style = NotificationCompat.MessagingStyle(Person.Builder().setName(context.getString(R.string.notification_you)).build())
            content.messages.forEach { style.addMessage(it.text, it.timestamp, sender) }
            builder.setStyle(style)
        }
        if (content.count > 1) builder.setNumber(content.count)

        postOn(SoundKind.MESSAGES, notificationId, builder)
        postGroupSummary(visibility)
    }

    /**
     * A group message (or a reaction to the user's message in a group, or an invitation): the
     * group's notification, titled with the group, each line with its sender. Lock screen
     * previews follow the same setting as for 1:1 chats; there the group stands where a
     * sender would.
     */
    suspend fun showGroupNotification(
        groupId: String,
        groupTitle: String,
        senderId: String,
        senderName: String,
        messageBody: String,
        isReaction: Boolean,
        names: Map<String, String> = emptyMap(),
    ) {
        if (!hasNotificationPermission()) return
        val settings = settingsRepository.getPrivacySettings().firstOrNull() ?: PrivacySettings()
        val unread = messageDao.getUnseenGroupTexts(groupId, NotificationCompat.MessagingStyle.MAXIMUM_RETAINED_MESSAGES)
        val lines = unread.map {
            val kind = com.sotto.app.domain.model.MediaKind.fromWire(it.mediaKind)
            it.senderId to NotifiedMessage(com.sotto.app.domain.model.previewUiText(kind, it.text, it.mediaName.orEmpty()).resolve(context), it.timestamp)
        }
        val content = messageNotificationContent(
            mode = settings.lockScreenPreview,
            senderName = groupTitle,
            unread = lines.map { it.second },
            unreadCount = messageDao.countUnseenIncoming(groupId),
            latest = NotifiedMessage(messageBody, System.currentTimeMillis()),
            latestIsReaction = isReaction,
        )
        val visibility = when (settings.lockScreenPreview) {
            LockScreenPreviewMode.SHOW_ALL, LockScreenPreviewMode.SENDER_ONLY -> NotificationCompat.VISIBILITY_PUBLIC
            LockScreenPreviewMode.HIDE_BODY -> NotificationCompat.VISIBILITY_PRIVATE
            LockScreenPreviewMode.HIDE_ALL -> NotificationCompat.VISIBILITY_SECRET
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_GROUP_ID, groupId)
        }
        val notificationId = groupId.hashCode() and Int.MAX_VALUE
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, groupsChannel) // the channel: postOn
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text.resolve(context))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(visibility)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_CHATS)
        if (content.messages.isNotEmpty()) {
            val style = NotificationCompat.MessagingStyle(Person.Builder().setName(context.getString(R.string.notification_you)).build())
                .setConversationTitle(groupTitle)
                .setGroupConversation(true)
            val people = HashMap<String, Person>()
            fun person(id: String, name: String) = people.getOrPut(id) { Person.Builder().setName(name).setKey(id).build() }
            // The listed lines are the unread ones (and, for a reaction, the reaction last).
            val senders = lines.map { it.first } + if (content.messages.size > lines.size) listOf(senderId) else emptyList()
            content.messages.forEachIndexed { i, m ->
                val id = senders.getOrNull(i) ?: senderId
                val name = if (id == senderId) senderName else names[id] ?: id.take(8)
                style.addMessage(m.text, m.timestamp, person(id, name))
            }
            builder.setStyle(style)
        }
        if (content.count > 1) builder.setNumber(content.count)
        postOn(SoundKind.GROUPS, notificationId, builder)
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
            LockScreenPreviewMode.HIDE_BODY -> callerName to missedCallsText(missed).resolve(context)
            LockScreenPreviewMode.HIDE_ALL -> "Sotto" to missedCallsText(missed).resolve(context)
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
        // On the missed-calls channel: it's never replaced. On a messages channel, a sound change
        // would delete the summary, and Android takes the group's other notifications (group
        // chats, missed calls) with a summary. It never sounds (GROUP_ALERT_CHILDREN).
        val summary = NotificationCompat.Builder(context, CHANNEL_MISSED_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sotto")
            .setContentText(context.getString(R.string.notification_new_messages_summary))
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
     * Post a 1:1 or group message notification on the channel in use at this moment: a sound
     * change (on another thread) replaces the channel and deletes the old one, and a
     * notification for a deleted channel is dropped.
     */
    @Synchronized
    private fun postOn(kind: SoundKind, id: Int, builder: NotificationCompat.Builder) =
        post(id, builder.setChannelId(channelId(kind)).build())

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
        const val CHANNEL_MISSED_CALLS = "missed_calls"
        private const val CHANNEL_SERVICE_LEGACY = "background_service"
        const val CHANNEL_BACKGROUND_CONNECTION = "background_connection"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_CONTACT_ID = "contact_id"
        /** Set on a group notification's intent: open that group's chat. */
        const val EXTRA_GROUP_ID = "group_id"
        /** Set on the summary's intent: open the chat list. */
        const val EXTRA_OPEN_CHATS = "open_chats"
        private const val GROUP_CHATS = "com.sotto.app.CHATS"
        private const val GROUP_SUMMARY_ID = 0x5C11
        private const val MISSED_CALL_PREFIX = "missed-call-"
        /** In a missed-call notification's extras: how many calls it counts. */
        private const val EXTRA_MISSED_CALLS = "com.sotto.app.MISSED_CALLS"
    }
}
