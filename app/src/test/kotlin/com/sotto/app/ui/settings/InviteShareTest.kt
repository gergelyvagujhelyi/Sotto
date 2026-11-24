package com.sotto.app.ui.settings

import com.sotto.app.R
import com.sotto.app.domain.model.InviteLink
import com.sotto.app.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InviteShareTest {
    private val invite = InviteLink("https://relay.example.com", "K7QXM2PA9TRB", "AbCdEfGhIjKlMn_-")
    private val share = InviteShare(invite.toLink(), "2026-11-26T12:00:00Z")

    @Test
    fun `the message is the link and the invite's last day`() {
        val label = share.expiresLabel("d MMM")
        assertTrue(label.startsWith("26"))
        assertEquals(UiText.res(R.string.settings_invite_share_text, invite.toLink(), label), share.shareText(label))
    }

    @Test
    fun `the last day is written the language's way`() {
        assertTrue(share.expiresLabel("MMM d.").endsWith(" 26."))
    }

    @Test
    fun `pasting the whole message in setup gives back the invite, in every language`() {
        for (values in listOf("values", "values-hu")) {
            val message = shareTemplate(values).format(invite.toLink(), share.expiresLabel("d MMM"))
            assertEquals(values, 1, Regex("https://").findAll(message).count())
            assertEquals(values, invite, InviteLink.find(message))
        }
    }

    @Test
    fun `an unreadable expiry is shown as it came`() {
        assertEquals("soon", share.copy(expiresAt = "soon").expiresLabel("d MMM"))
    }

    /** The share message as a language's strings file writes it (tests run in the app module). */
    private fun shareTemplate(values: String): String {
        val xml = File("src/main/res/$values/strings_settings.xml").readText()
        val raw = Regex("""<string name="settings_invite_share_text">(.*?)</string>""").find(xml)!!.groupValues[1]
        return raw.replace("\\n", "\n").replace("\\'", "'")
    }
}
