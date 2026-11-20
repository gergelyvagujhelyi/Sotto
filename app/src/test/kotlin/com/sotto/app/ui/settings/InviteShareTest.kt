package com.sotto.app.ui.settings

import com.sotto.app.domain.model.InviteLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteShareTest {
    private val invite = InviteLink("https://relay.example.com", "K7QXM2PA9TRB", "AbCdEfGhIjKlMn_-")
    private val share = InviteShare(invite.toLink(), "2026-11-26T12:00:00Z")

    @Test
    fun `the message is one link and the invite's last day`() {
        val text = share.shareText()
        assertEquals(1, Regex("https://").findAll(text).count())
        assertTrue(text.contains(invite.toLink()))
        assertTrue(share.expiresLabel.startsWith("26"))
        assertTrue(text.contains("until ${share.expiresLabel}"))
    }

    @Test
    fun `pasting the whole message in setup gives back the invite`() {
        assertEquals(invite, InviteLink.find(share.shareText()))
    }

    @Test
    fun `an unreadable expiry is shown as it came`() {
        assertEquals("soon", share.copy(expiresAt = "soon").expiresLabel)
    }
}
