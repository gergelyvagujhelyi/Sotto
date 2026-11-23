package com.sotto.app.crypto

import com.sotto.app.data.local.dao.ContactDao
import com.sotto.app.data.local.entity.ContactEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class InPersonVerifierTest {

    private val contactId = "0123456789abcdef"
    private val pinned = ByteArray(32) { 7 }
    private val rotated = ByteArray(32) { 8 }
    private val contactDao = mockk<ContactDao>()
    private val keyVerifier = mockk<ContactKeyVerifier>()
    private val classical = mockk<ClassicalKeyAgreement>()
    private val verifier = InPersonVerifier(contactDao, keyVerifier, mockk(relaxed = true), classical)
    private val now = 1_800_000_000_000L
    private val signingKey = ByteArray(32) { 9 }

    private fun contact(fingerprint: ByteArray?) = ContactEntity(
        userId = contactId,
        displayName = "Astrid",
        publicIdentityKey = ByteArray(32),
        publicSigningKey = signingKey,
        keyFingerprint = fingerprint?.let(KeyFingerprint::toHex),
    )

    private fun known(fingerprint: ByteArray) = KeyCheck.Known(
        keys = mockk(relaxed = true),
        contact = contact(fingerprint),
        rotated = true,
    )

    private fun verify(code: InPersonCode) = runBlocking { verifier.verify(contactId, code) }

    @Test
    fun `the pinned keys on their phone verify them`() {
        coEvery { contactDao.getByUserId(contactId) } returns contact(pinned)
        coEvery { contactDao.markVerifiedInPerson(contactId, KeyFingerprint.toHex(pinned)) } returns 1

        assertEquals(InPersonResult.Verified, verify(InPersonCode(contactId, pinned)))
        coVerify(exactly = 0) { keyVerifier.check(any()) }
    }

    @Test
    fun `another account's code never verifies, and nothing is looked up`() {
        assertEquals(InPersonResult.OtherAccount, verify(InPersonCode("fedcba9876543210", pinned)))
        coVerify(exactly = 0) { contactDao.markVerifiedInPerson(any(), any()) }
    }

    @Test
    fun `no contact, nothing to verify`() {
        coEvery { contactDao.getByUserId(contactId) } returns null
        assertEquals(InPersonResult.NotAContact, verify(InPersonCode(contactId, pinned)))
    }

    @Test
    fun `a rotation their old keys endorsed is followed before comparing`() {
        coEvery { contactDao.getByUserId(contactId) } returns contact(pinned)
        coEvery { keyVerifier.check(contactId) } returns known(rotated)
        coEvery { contactDao.markVerifiedInPerson(contactId, KeyFingerprint.toHex(rotated)) } returns 1

        assertEquals(InPersonResult.Verified, verify(InPersonCode(contactId, rotated)))
    }

    @Test
    fun `keys other than the ones this chat uses are a mismatch`() {
        coEvery { contactDao.getByUserId(contactId) } returns contact(pinned)
        coEvery { keyVerifier.check(contactId) } returns known(pinned)

        assertEquals(InPersonResult.KeysDiffer, verify(InPersonCode(contactId, rotated)))
        coVerify(exactly = 0) { contactDao.markVerifiedInPerson(any(), any()) }
    }

    @Test
    fun `keys the relay changed without an endorsement are a mismatch`() {
        coEvery { contactDao.getByUserId(contactId) } returns contact(pinned)
        coEvery { keyVerifier.check(contactId) } returns KeyCheck.Mismatch("unendorsed")

        assertEquals(InPersonResult.KeysDiffer, verify(InPersonCode(contactId, rotated)))
    }

    @Test
    fun `offline, a newer code gets no verdict either way`() {
        coEvery { contactDao.getByUserId(contactId) } returns contact(pinned)
        coEvery { keyVerifier.check(contactId) } returns KeyCheck.Unavailable

        assertEquals(InPersonResult.CantCheck, verify(InPersonCode(contactId, rotated)))
        coVerify(exactly = 0) { contactDao.markVerifiedInPerson(any(), any()) }
    }

    @Test
    fun `a contact from before fingerprints is checked once the bundle confirms its keys`() {
        coEvery { contactDao.getByUserId(contactId) } returns contact(null)
        coEvery { keyVerifier.check(contactId) } returns known(pinned)
        coEvery { contactDao.markVerifiedInPerson(contactId, KeyFingerprint.toHex(pinned)) } returns 1

        assertEquals(InPersonResult.Verified, verify(InPersonCode(contactId, pinned)))
    }

    @Test
    fun `keys that moved on before the write leave the contact unverified`() {
        coEvery { contactDao.getByUserId(contactId) } returns contact(pinned)
        coEvery { contactDao.markVerifiedInPerson(contactId, any()) } returns 0

        assertEquals(InPersonResult.CantCheck, verify(InPersonCode(contactId, pinned)))
    }

    private fun live(at: Long, sig: ByteArray = ByteArray(64) { 4 }) =
        InPersonCode(contactId, pinned, issuedAt = at, signature = sig)

    private fun verifyQr(code: InPersonCode) = runBlocking { verifier.verify(contactId, code, requireLive = true, nowMs = now) }

    @Test
    fun `a live QR code, fresh and signed by their key, verifies them`() {
        val code = live(now / 1000 - 5)
        coEvery { contactDao.getByUserId(contactId) } returns contact(pinned)
        every { classical.verify(any(), code.signature!!, signingKey) } returns true
        coEvery { contactDao.markVerifiedInPerson(contactId, KeyFingerprint.toHex(pinned)) } returns 1

        assertEquals(InPersonResult.Verified, verifyQr(code))
    }

    @Test
    fun `a QR code older than a minute is a picture, not their phone`() {
        coEvery { contactDao.getByUserId(contactId) } returns contact(pinned)
        every { classical.verify(any(), any(), any()) } returns true

        assertEquals(InPersonResult.NotLive, verifyQr(live(now / 1000 - InPersonVerifier.LIVE_WINDOW_S - 1)))
        coVerify(exactly = 0) { contactDao.markVerifiedInPerson(any(), any()) }
    }

    @Test
    fun `a QR code not signed by their key is not live`() {
        coEvery { contactDao.getByUserId(contactId) } returns contact(pinned)
        every { classical.verify(any(), any(), any()) } returns false

        assertEquals(InPersonResult.NotLive, verifyQr(live(now / 1000)))
    }

    @Test
    fun `a QR code without a time and signature, from an older Sotto, never counts as in person`() {
        coEvery { contactDao.getByUserId(contactId) } returns contact(pinned)

        assertEquals(InPersonResult.OlderApp, verifyQr(InPersonCode(contactId, pinned)))
    }

    @Test
    fun `the signature covers who, which keys and when`() {
        val a = InPersonCode.livePayload(contactId, pinned, 100)
        assertNotEquals(a.toList(), InPersonCode.livePayload(contactId, pinned, 101).toList())
        assertNotEquals(a.toList(), InPersonCode.livePayload(contactId, rotated, 100).toList())
        assertNotEquals(a.toList(), InPersonCode.livePayload("fedcba9876543210", pinned, 100).toList())
    }
}
