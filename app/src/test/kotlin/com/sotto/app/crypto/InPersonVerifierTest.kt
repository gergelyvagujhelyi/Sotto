package com.sotto.app.crypto

import com.sotto.app.data.local.dao.ContactDao
import com.sotto.app.data.local.entity.ContactEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class InPersonVerifierTest {

    private val contactId = "0123456789abcdef"
    private val pinned = ByteArray(32) { 7 }
    private val rotated = ByteArray(32) { 8 }
    private val contactDao = mockk<ContactDao>()
    private val keyVerifier = mockk<ContactKeyVerifier>()
    private val verifier = InPersonVerifier(contactDao, keyVerifier, mockk(relaxed = true))

    private fun contact(fingerprint: ByteArray?) = ContactEntity(
        userId = contactId,
        displayName = "Astrid",
        publicIdentityKey = ByteArray(32),
        publicSigningKey = ByteArray(32),
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
}
