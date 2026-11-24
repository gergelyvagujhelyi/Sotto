package com.sotto.app.notification

import com.sotto.app.R
import com.sotto.app.domain.model.LockScreenPreviewMode
import com.sotto.app.ui.text.UiText

/** A received message a chat's notification lists. */
internal data class NotifiedMessage(val text: String, val timestamp: Long)

/** What a chat's notification says. */
internal data class MessageNotificationContent(
    val title: String,
    /** The collapsed line: the newest message, or how many there are when text is hidden. */
    val text: UiText,
    /** Every listed message, oldest first, for the conversation style; empty when text is hidden. */
    val messages: List<NotifiedMessage>,
    /** How many unread messages the chat has. */
    val count: Int,
)

/**
 * The notification for a chat with [unread] messages (the newest ones, oldest first) out of
 * [unreadCount], under the user's lock-screen preview setting [mode].
 *
 * It lists every message since the user last opened the chat, not only the newest: each new
 * message used to replace the notification, so the earlier ones vanished from it. [latest] is
 * what triggered it, used when the chat has no stored unread message (a new contact's
 * "wants to connect").
 */
internal fun messageNotificationContent(
    mode: LockScreenPreviewMode,
    senderName: String,
    unread: List<NotifiedMessage>,
    unreadCount: Int,
    latest: NotifiedMessage,
    /** [latest] is a reaction, which isn't among [unread] (nor counted in [unreadCount]). */
    latestIsReaction: Boolean = false,
): MessageNotificationContent {
    if (latestIsReaction) {
        val listed = unread + latest
        val howMany = when (unreadCount) {
            0 -> UiText.res(R.string.notification_new_reaction)
            else -> UiText.plural(R.plurals.notification_new_messages_and_reaction, unreadCount)
        }
        return when (mode) {
            LockScreenPreviewMode.SHOW_ALL -> MessageNotificationContent(senderName, UiText.Plain(latest.text), listed, unreadCount)
            LockScreenPreviewMode.SENDER_ONLY,
            LockScreenPreviewMode.HIDE_BODY -> MessageNotificationContent(senderName, howMany, emptyList(), unreadCount)
            LockScreenPreviewMode.HIDE_ALL -> MessageNotificationContent("Sotto", howMany, emptyList(), unreadCount)
        }
    }
    val listed = unread.ifEmpty { listOf(latest) }
    val count = maxOf(unreadCount, listed.size)
    val howMany = UiText.plural(R.plurals.notification_new_messages, count)
    return when (mode) {
        LockScreenPreviewMode.SHOW_ALL -> MessageNotificationContent(senderName, UiText.Plain(listed.last().text), listed, count)
        LockScreenPreviewMode.SENDER_ONLY,
        LockScreenPreviewMode.HIDE_BODY -> MessageNotificationContent(senderName, howMany, emptyList(), count)
        LockScreenPreviewMode.HIDE_ALL -> MessageNotificationContent("Sotto", howMany, emptyList(), count)
    }
}

/** A caller's missed-call notification: how many calls from them went unanswered since it was last seen. */
internal fun missedCallsText(count: Int): UiText =
    UiText.plural(R.plurals.notification_missed_calls, count.coerceAtLeast(1))
