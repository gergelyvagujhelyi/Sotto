package com.sotto.app.ui.settings

import com.sotto.app.R
import com.sotto.app.domain.model.InviteLink
import com.sotto.app.testing.English
import com.sotto.app.testing.Hungarian
import com.sotto.app.testing.StringsXml
import com.sotto.app.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class InviteShareTest {
    private val invite = InviteLink("https://relay.example.com", "K7QXM2PA9TRB", "AbCdEfGhIjKlMn_-")
    private val share = InviteShare(invite.toLink(), "2026-11-26T12:00:00Z")

    @Test
    fun `the message is the link and the invite's last day`() {
        val label = share.expiresLabel("d MMM", Locale.ENGLISH)
        assertTrue(label.startsWith("26"))
        assertEquals(UiText.res(R.string.settings_invite_share_text, invite.toLink(), label), share.shareText(label))
    }

    @Test
    fun `the last day is written the language's way`() {
        assertEquals("26 Nov", expires(English))
        assertEquals("nov. 26.", expires(Hungarian))
    }

    @Test
    fun `month names follow the app's language, not the phone's`() {
        val phone = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMAN) // a language Sotto doesn't have: its text is English
            assertEquals("26 Nov", expires(English))
        } finally {
            Locale.setDefault(phone)
        }
    }

    private fun expires(strings: StringsXml) = share.expiresLabel(
        strings["settings_invite_date_pattern"], Locale.forLanguageTag(strings["chat_date_locale"]),
    )

    @Test
    fun `pasting the whole message in setup gives back the invite, in every language`() {
        for ((language, strings) in listOf("en" to English, "hu" to Hungarian)) {
            val message = strings["settings_invite_share_text"].format(invite.toLink(), expires(strings))
            assertEquals(language, 1, Regex("https://").findAll(message).count())
            assertEquals(language, invite, InviteLink.find(message))
        }
    }

    @Test
    fun `an unreadable expiry is shown as it came`() {
        assertEquals("soon", share.copy(expiresAt = "soon").expiresLabel("d MMM", Locale.ENGLISH))
    }
}
