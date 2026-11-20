package com.sotto.app.notification

import com.sotto.app.domain.model.LockScreenPreviewMode
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
        assertEquals("Ring mig", content.text)
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
            assertEquals(mode.name, "3 new messages", content.text)
            assertTrue(mode.name, content.messages.isEmpty())
        }
    }

    @Test
    fun `one message is just a new message`() {
        val one = listOf(NotifiedMessage("Hej!", 1_000))
        assertEquals("New message", messageNotificationContent(LockScreenPreviewMode.HIDE_BODY, "Maja", one, 1, one[0]).text)
        assertEquals("Hej!", messageNotificationContent(LockScreenPreviewMode.SHOW_ALL, "Maja", one, 1, one[0]).text)
    }

    @Test
    fun `more unread than listed are still counted`() {
        val content = messageNotificationContent(LockScreenPreviewMode.SENDER_ONLY, "Maja", unread, 40, latest)
        assertEquals("40 new messages", content.text)
        assertEquals(40, content.count)
    }

    @Test
    fun `a chat with nothing stored yet shows what triggered it`() {
        // A new contact's first notice ("wants to connect") isn't a stored message.
        val notice = NotifiedMessage("wants to connect", 5_000)
        val content = messageNotificationContent(LockScreenPreviewMode.SHOW_ALL, "Maja", emptyList(), 0, notice)
        assertEquals(listOf(notice), content.messages)
        assertEquals("wants to connect", content.text)
        assertEquals(1, content.count)
    }
}
