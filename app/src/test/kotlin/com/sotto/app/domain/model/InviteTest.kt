package com.sotto.app.domain.model

import com.sotto.app.R
import com.sotto.app.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteTest {
    private val invite = InviteLink("https://relay.example.com", "K7QXM2PA9TRB", "AbCdEfGhIjKlMn_-")

    @Test
    fun `a member who opens their own invite is told so, not offered to add themselves`() {
        assertEquals(
            MemberInviteAction.OwnInvite,
            invite.actionForMember("https://relay.example.com", myShareCode = "AbCdEfGhIjKlMn_-"),
        )
    }

    @Test
    fun `a member who opens someone else's invite can add them`() {
        assertEquals(
            MemberInviteAction.AddInviter("AbCdEfGhIjKlMn_-"),
            invite.actionForMember("https://relay.example.com", myShareCode = "ZzZzZzZzZzZzZzZz"),
        )
        // Before the member's own identity has loaded, it's still offered (the add itself checks).
        assertEquals(
            MemberInviteAction.AddInviter("AbCdEfGhIjKlMn_-"),
            invite.actionForMember("https://relay.example.com", myShareCode = null),
        )
    }

    @Test
    fun `invites without an inviter or for another relay only explain`() {
        assertEquals(
            MemberInviteAction.AlreadyMember("relay.example.com"),
            invite.copy(inviterShareCode = null).actionForMember("https://relay.example.com", "ZzZzZzZzZzZzZzZz"),
        )
        // Another relay wins over everything else, the member's own share code included.
        assertEquals(
            MemberInviteAction.OtherRelay("relay.example.com"),
            invite.actionForMember("https://other.example.org", myShareCode = "AbCdEfGhIjKlMn_-"),
        )
    }

    @Test
    fun `codes ignore case, spaces and dashes, and read O as 0 and I or L as 1`() {
        assertEquals("K7QXM2PA9TRB", normalizeInviteCode("k7qx-m2pa-9trb"))
        assertEquals("K7QXM2PA9TRB", normalizeInviteCode(" K7QX M2PA 9TRB "))
        assertEquals("0000111100AB", normalizeInviteCode("OOOO-IIIL-OOAB"))
        assertNull(normalizeInviteCode("K7QX-M2PA")) // too short
        assertNull(normalizeInviteCode("K7QX-M2PA-9TRU")) // U isn't a symbol
        assertEquals("K7QX-M2PA-9TRB", displayInviteCode("K7QXM2PA9TRB"))
    }

    @Test
    fun `invites from ChatControll, the app's old name, still work`() {
        val old = "Join me!\nchatcontroll://invite?r=https%3A%2F%2Frelay.example.com&c=K7QX-M2PA-9TRB&s=AbCdEfGhIjKlMn_-\nThanks"
        assertEquals(invite, InviteLink.find(old))
        assertTrue(invite.toLink().startsWith("https://relay.example.com/invite#"))
    }

    @Test
    fun `the app's own form, which the invite page opens, still works`() {
        val app = "sotto://invite?r=https%3A%2F%2Frelay.example.com&c=K7QX-M2PA-9TRB&s=AbCdEfGhIjKlMn_-"
        assertEquals(invite, InviteLink.find(app))
    }

    @Test
    fun `a link is one https link, the relay's address with the code, and reads back as the same invite`() {
        val link = invite.toLink()
        assertEquals("https://relay.example.com/invite#c=K7QX-M2PA-9TRB&s=AbCdEfGhIjKlMn_-", link)
        assertEquals(invite, InviteLink.find(link))
    }

    @Test
    fun `a link at the end of a sentence keeps its share code`() {
        assertEquals(invite, InviteLink.find("Here's my invite: ${invite.toLink()}."))
        assertEquals(invite, InviteLink.find("(${invite.toLink()})"))
    }

    @Test
    fun `the first invite in a message wins`() {
        val other = InviteLink("https://other.example.com", "ABCDABCDABCD", null)
        assertEquals(invite, InviteLink.find("${invite.toLink()} or ${other.toLink()}"))
        assertEquals(other, InviteLink.find("${other.toLink()} or chatcontroll://invite?r=relay.example.com&c=K7QXM2PA9TRB"))
    }

    @Test
    fun `plain http links work only for a debug build's local relay`() {
        val local = "http://10.0.2.2:8000/invite#c=K7QXM2PA9TRB"
        assertNull(InviteLink.find(local))
        assertEquals(InviteLink("http://10.0.2.2:8000", "K7QXM2PA9TRB", null), InviteLink.find(local, setOf("10.0.2.2")))
    }

    @Test
    fun `the link is found inside a whole pasted message`() {
        val message = "Join me on Sotto!\nInstall it from https://github.com/x/y/releases/latest, " +
            "then paste this whole message into its setup:\n${invite.toLink()}\nThe invite works once, for 7 days."
        assertEquals(invite, InviteLink.find(message))
    }

    @Test
    fun `the inviter's share code is optional`() {
        val found = InviteLink.find("sotto://invite?r=https%3A%2F%2Frelay.example.com&c=K7QXM2PA9TRB")
        assertNotNull(found)
        assertNull(found!!.inviterShareCode)
        assertNull(InviteLink.find("sotto://invite?r=relay.example.com&c=K7QXM2PA9TRB&s=short")!!.inviterShareCode)
    }

    @Test
    fun `broken invites are refused`() {
        assertNull(InviteLink.find("no link here"))
        assertNull(InviteLink.find("sotto://invite?c=K7QXM2PA9TRB")) // no relay
        assertNull(InviteLink.find("sotto://invite?r=relay.example.com")) // no code
        assertNull(InviteLink.find("sotto://invite?r=http%3A%2F%2Frelay.example.com&c=K7QXM2PA9TRB")) // plain http
        assertNull(InviteLink.find("sotto://invite?r=relay.example.com&c=NOPE")) // not a code
        assertNull(InviteLink.find("https://relay.example.com/invite#s=AbCdEfGhIjKlMn_-")) // no code
        assertNull(InviteLink.find("https://relay.example.com/invite#c=NOPE")) // not a code
        assertNull(InviteLink.find("https://relay.example.com/other#c=K7QXM2PA9TRB")) // not the invite page
        assertNull(InviteLink.find("https://relay.example.com/invite?c=K7QXM2PA9TRB")) // code in the query
        assertNull(InviteLink.find("https://relay.example.com/x/invite#c=K7QXM2PA9TRB")) // not at the root
    }

    @Test
    fun `the relay's refusals become plain words`() {
        assertEquals(UiText.res(R.string.onboarding_invite_used), inviteErrorMessage("invite_used"))
        assertNotNull(inviteErrorMessage("invite_required"))
        assertNotNull(inviteErrorMessage("invite_invalid"))
        assertNotNull(inviteErrorMessage("invite_expired"))
        assertNull(inviteErrorMessage("Bootstrap failed: 500"))
        assertEquals(UiText.res(R.string.settings_invite_limit_daily), inviteLimitMessage("invite_limit_daily"))
        assertEquals(UiText.res(R.string.settings_invite_failed), inviteLimitMessage("something else"))
    }
}
