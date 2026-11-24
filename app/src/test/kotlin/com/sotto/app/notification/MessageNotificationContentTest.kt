package com.sotto.app.notification

import com.sotto.app.R
import com.sotto.app.domain.model.LockScreenPreviewMode
import com.sotto.app.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageNotificationContentTest {
    private val unread = listOf(
        NotifiedMessage("Hej!", 1_000),
        NotifiedMessage("Är du hemma?", 2_000),
        NotifiedMessage("Ring mig", 3_000),
    )
    private val latest = NotifiedMessage("Ring mig", 3_000)

    @Test
    fun `the notification lists every message since the chat was last opened, oldest first`() {
        // The owner's report: "notifications only show the last one always".
        val content = messageNotificationContent(LockScreenPreviewMode.SHOW_ALL, "Maja", unread, 3, latest)
        assertEquals(listOf("Hej!", "Är du hemma?", "Ring mig"), content.messages.map { it.text })
        assertEquals("Maja", content.title)
        assertEquals(UiText.Plain("Ring mig"), content.text)
        assertEquals(3, content.count)
    }

    @Test
    fun `with text hidden it says how many there are, and lists none`() {
        mapOf(
            LockScreenPreviewMode.SENDER_ONLY to "Maja",
            LockScreenPreviewMode.HIDE_BODY to "Maja",
            LockScreenPreviewMode.HIDE_ALL to "Sotto",
        ).forEach { (mode, title) ->
            val content = messageNotificationContent(mode, "Maja", unread, 3, latest)
            assertEquals(mode.name, title, content.title)
            assertEquals(mode.name, UiText.plural(R.plurals.notification_new_messages, 3), content.text)
            assertTrue(mode.name, content.messages.isEmpty())
        }
    }

    @Test
    fun `one message is just a new message`() {
        val one = listOf(NotifiedMessage("Hej!", 1_000))
        assertEquals(UiText.plural(R.plurals.notification_new_messages, 1), messageNotificationContent(LockScreenPreviewMode.HIDE_BODY, "Maja", one, 1, one[0]).text)
        assertEquals(UiText.Plain("Hej!"), messageNotificationContent(LockScreenPreviewMode.SHOW_ALL, "Maja", one, 1, one[0]).text)
    }

    @Test
    fun `more unread than listed are still counted`() {
        val content = messageNotificationContent(LockScreenPreviewMode.SENDER_ONLY, "Maja", unread, 40, latest)
        assertEquals(UiText.plural(R.plurals.notification_new_messages, 40), content.text)
        assertEquals(40, content.count)
    }

    @Test
    fun `a chat with nothing stored yet shows what triggered it`() {
        // A new contact's first notice ("wants to connect") isn't a stored message.
        val notice = NotifiedMessage("wants to connect", 5_000)
        val content = messageNotificationContent(LockScreenPreviewMode.SHOW_ALL, "Maja", emptyList(), 0, notice)
        assertEquals(listOf(notice), content.messages)
        assertEquals(UiText.Plain("wants to connect"), content.text)
        assertEquals(1, content.count)
    }

    @Test
    fun `missed calls from one person are counted`() {
        assertEquals(UiText.plural(R.plurals.notification_missed_calls, 1), missedCallsText(1))
        assertEquals(UiText.plural(R.plurals.notification_missed_calls, 2), missedCallsText(2))
        assertEquals(UiText.plural(R.plurals.notification_missed_calls, 5), missedCallsText(5))
        assertEquals(listOf(5), (missedCallsText(5) as UiText.Plural).args)
    }

    @Test
    fun `a reaction is listed after the unread messages and isn't counted as one`() {
        val reaction = NotifiedMessage("Reacted ❤️ to \"Fika?\"", 4_000)
        val content = messageNotificationContent(LockScreenPreviewMode.SHOW_ALL, "Maja", unread, 3, reaction, latestIsReaction = true)
        assertEquals(listOf("Hej!", "Är du hemma?", "Ring mig", reaction.text), content.messages.map { it.text })
        assertEquals(UiText.Plain(reaction.text), content.text)
        assertEquals(3, content.count)

        val alone = messageNotificationContent(LockScreenPreviewMode.SHOW_ALL, "Maja", emptyList(), 0, reaction, latestIsReaction = true)
        assertEquals(listOf(reaction.text), alone.messages.map { it.text })
        assertEquals(0, alone.count)
    }

    @Test
    fun `with text hidden a reaction says only that there is one`() {
        val reaction = NotifiedMessage("Reacted ❤️ to \"Fika?\"", 4_000)
        assertEquals(UiText.res(R.string.notification_new_reaction), messageNotificationContent(LockScreenPreviewMode.HIDE_BODY, "Maja", emptyList(), 0, reaction, true).text)
        assertEquals(UiText.plural(R.plurals.notification_new_messages_and_reaction, 3), messageNotificationContent(LockScreenPreviewMode.SENDER_ONLY, "Maja", unread, 3, reaction, true).text)
        val hidden = messageNotificationContent(LockScreenPreviewMode.HIDE_ALL, "Maja", unread, 3, reaction, true)
        assertEquals("Sotto", hidden.title)
        assertTrue(hidden.messages.isEmpty())
    }
}
