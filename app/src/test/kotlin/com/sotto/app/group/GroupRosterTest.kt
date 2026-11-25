package com.sotto.app.group

import com.sotto.app.group.RosterRules.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Signed rosters (ADR-32): only the admin changes a group, every change is signed, epochs only grow. */
class GroupRosterTest {

    private val astrid = TestAccount("astrid")
    private val bjorn = TestAccount("bjorn")
    private val cecilia = TestAccount("cecilia")
    private val dagny = TestAccount("dagny", postQuantum = false)
    private val everyone = listOf(astrid, bjorn, cecilia, dagny).associateBy { it.userId }
    private val keys: (String) -> SigningKeys? = { everyone[it]?.signingKeys }
    private val groupId = GroupIds.newId()

    private fun roster(
        epoch: Long = 1,
        admin: TestAccount = astrid,
        issuer: TestAccount = admin,
        members: List<RosterMember> = listOf(astrid.member(), bjorn.member(), cecilia.member(RosterMember.INVITED)),
        transfers: List<AdminTransfer> = emptyList(),
    ) = RosterRules.sign(
        GroupRoster(
            groupId = groupId, epoch = epoch, issuer = issuer.userId, admin = admin.userId, title = "Fika",
            timerMs = 0, members = members.sortedBy { it.userId }, transfers = transfers,
        ),
        issuer.signatures,
    )

    private fun verify(new: GroupRoster, stored: GroupRoster?, sender: TestAccount) =
        RosterRules.verifyUpdate(new, stored, sender.userId, keys, bjorn.signatures)

    @Test
    fun `an invitation signed by its admin and sent by them is taken`() {
        assertEquals(Verdict.Accept, verify(roster(), null, astrid))
    }

    @Test
    fun `an invitation sent by anyone but its admin is refused`() {
        val r = roster()
        assertTrue(verify(r, null, bjorn) is Verdict.Reject)
        // Even one bjorn signed himself, naming astrid admin.
        val forged = RosterRules.sign(r.unsigned().copy(issuer = bjorn.userId), bjorn.signatures)
        assertTrue(verify(forged, null, bjorn) is Verdict.Reject)
    }

    @Test
    fun `changing any signed field breaks the signature`() {
        val r = roster()
        val tampered = listOf(
            r.copy(title = "Fika!"),
            r.copy(timerMs = 3_600_000),
            r.copy(deleted = true),
            r.copy(members = r.members.map { if (it.userId == "cecilia") it.copy(status = RosterMember.ACTIVE) else it }),
            r.copy(members = r.members.map { if (it.userId == "bjorn") it.copy(fingerprint = dagny.fingerprint) else it }),
            r.copy(members = r.members.map { if (it.userId == "bjorn") it.copy(name = "Björn") else it }),
            r.copy(members = r.members.map { if (it.userId == "bjorn") it.copy(trust = RosterMember.TRUST_NONE) else it }),
            r.copy(members = (r.members + dagny.member()).sortedBy { it.userId }),
        )
        for (t in tampered) {
            assertEquals(Verdict.Reject("roster signature"), verify(t, null, astrid))
        }
        // The epoch is signed too.
        val stored = roster()
        assertEquals(Verdict.Reject("roster signature"), verify(roster(epoch = 2).copy(epoch = 3), stored, astrid))
    }

    @Test
    fun `a post-quantum admin's roster without its ML-DSA signature is refused`() {
        val r = roster()
        assertEquals(Verdict.Reject("roster signature"), verify(r.copy(pqcSignature = ""), null, astrid))
        // An account without ML-DSA keys signs with Ed25519 only, and that's enough for it.
        val dagnys = roster(admin = dagny, members = listOf(dagny.member(), bjorn.member(RosterMember.INVITED)))
        assertEquals("", dagnys.pqcSignature)
        assertEquals(Verdict.Accept, verify(dagnys, null, dagny))
    }

    @Test
    fun `updates need a higher epoch from the admin held`() {
        val stored = roster(epoch = 3)
        assertEquals(Verdict.Accept, verify(roster(epoch = 4), stored, astrid))
        assertEquals(Verdict.Stale, verify(roster(epoch = 3), stored, astrid))
        assertEquals(Verdict.Stale, verify(roster(epoch = 2), stored, astrid))
    }

    @Test
    fun `a member can't issue a roster`() {
        val stored = roster(epoch = 3)
        val bjorns = roster(epoch = 4, admin = bjorn)
        assertEquals(Verdict.Reject("issuer is not the admin"), verify(bjorns, stored, bjorn))
        // Nor relay one signed by the admin: rosters come from their issuer.
        assertEquals(Verdict.Reject("not sent by its issuer"), verify(roster(epoch = 4), stored, bjorn))
    }

    @Test
    fun `the admin hands over with a signed handover, and the new admin's rosters follow`() {
        val before = roster(epoch = 5)
        val handover = RosterRules.transfer(groupId, 6, astrid.userId, bjorn.userId, astrid.signatures)
        val atHandover = roster(epoch = 6, admin = bjorn, issuer = astrid, transfers = listOf(handover))
        assertEquals(Verdict.Accept, verify(atHandover, before, astrid))

        val bjorns = roster(epoch = 7, admin = bjorn, transfers = listOf(handover))
        // A phone that took the handover roster...
        assertEquals(Verdict.Accept, verify(bjorns, atHandover, bjorn))
        // ...and one that missed it: the handover it carries leads from astrid to bjorn.
        assertEquals(Verdict.Accept, verify(bjorns, before, bjorn))
        // Without it, a phone that missed the handover can't tell bjorn is admin.
        assertEquals(Verdict.Reject("issuer is not the admin"), verify(roster(epoch = 7, admin = bjorn), before, bjorn))
    }

    @Test
    fun `a handover not signed by the admin is refused`() {
        val before = roster(epoch = 5)
        val forged = RosterRules.transfer(groupId, 6, astrid.userId, bjorn.userId, bjorn.signatures)
        val bjorns = roster(epoch = 7, admin = bjorn, transfers = listOf(forged))
        assertEquals(Verdict.Reject("handover signature"), verify(bjorns, before, bjorn))
        // Nor one from someone who wasn't admin.
        val fromCecilia = RosterRules.transfer(groupId, 6, cecilia.userId, bjorn.userId, cecilia.signatures)
        assertEquals(
            Verdict.Reject("handover from someone not admin"),
            verify(roster(epoch = 7, admin = bjorn, transfers = listOf(fromCecilia)), before, bjorn),
        )
        // A new admin needs a handover for the roster's own epoch.
        assertEquals(Verdict.Reject("new admin without a handover"), verify(roster(epoch = 6, admin = bjorn, issuer = astrid), before, astrid))
    }

    @Test
    fun `keys this phone doesn't have are asked for, not guessed`() {
        val stranger = TestAccount("eskil")
        val r = roster(admin = stranger, members = listOf(stranger.member(), bjorn.member(RosterMember.INVITED)))
        assertEquals(Verdict.NeedKeys("eskil"), verify(r, null, stranger))
    }

    @Test
    fun `badly formed rosters are refused before any signature is checked`() {
        val r = roster()
        assertNull(RosterRules.validate(r))
        assertNotNull(RosterRules.validate(r.copy(members = r.members.reversed())))
        assertNotNull(RosterRules.validate(r.copy(members = r.members + r.members.first())))
        assertNotNull(RosterRules.validate(r.copy(title = " ")))
        assertNotNull(RosterRules.validate(r.copy(title = "x".repeat(65))))
        assertNotNull(RosterRules.validate(r.copy(title = "a\u0000b")))
        assertNotNull(RosterRules.validate(r.copy(groupId = "not-hex")))
        assertNotNull(RosterRules.validate(r.copy(epoch = 0)))
        assertNotNull(RosterRules.validate(r.copy(timerMs = -1)))
        assertNotNull(RosterRules.validate(r.copy(timerMs = GroupRoster.MAX_TIMER_MS + 1)))
        assertNotNull(RosterRules.validate(r.copy(members = r.members.map { it.copy(fingerprint = "ab") })))
        assertNotNull(RosterRules.validate(r.copy(members = r.members.map { it.copy(trust = "x") })))
        assertNotNull(RosterRules.validate(r.copy(admin = "cecilia"))) // invited, not active
        assertNotNull(RosterRules.validate(r.copy(signature = "")))
        val many = (0 until 33).map { TestAccount.run { RosterMember("u%02d".format(it), astrid.fingerprint, "", RosterMember.ACTIVE, "n") } }
        assertNotNull(RosterRules.validate(r.copy(admin = "u00", issuer = "u00", members = many)))
        assertNull(RosterRules.validate(r.copy(admin = "u00", issuer = "u00", members = many.take(32))))
    }

    @Test
    fun `names and titles lose control and direction characters`() {
        assertEquals("Fika", RosterRules.cleanTitle("  Fi‮ka\u0007 "))
        assertEquals("Björn", RosterRules.cleanName("Bj‎örn"))
        assertEquals(40, RosterRules.cleanName("x".repeat(80)).length)
    }
}
