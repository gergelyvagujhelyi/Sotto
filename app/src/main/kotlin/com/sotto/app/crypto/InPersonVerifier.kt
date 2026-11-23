package com.sotto.app.crypto

import com.sotto.app.data.local.dao.ContactDao
import javax.inject.Inject
import javax.inject.Singleton

/** How checking a contact face to face went. */
sealed interface InPersonResult {
    /** Their phone showed exactly the keys this chat uses: marked verified in person. */
    data object Verified : InPersonResult

    /** The code is another account's: the phone in front of you isn't the one in this chat. */
    data object OtherAccount : InPersonResult

    /** Same account, other keys: this chat doesn't use the keys on their phone. */
    data object KeysDiffer : InPersonResult

    /** Their keys couldn't be confirmed right now (no connection to the relay): try again. */
    data object CantCheck : InPersonResult

    /** Not a contact on this phone (any more). */
    data object NotAContact : InPersonResult
}

/**
 * Verifies a contact in person: compares the [InPersonCode] their phone showed (QR code) or
 * sent (NFC) with the fingerprint this phone pinned for them, which every message and call to
 * them is encrypted with. Only a match marks them verified in person.
 *
 * Nothing here trusts the relay. The code comes straight from the other phone; the relay's
 * part is at most handing over a rotation their old keys endorsed ([ContactKeyVerifier]),
 * which is fetched only when the pinned fingerprint is older than the code.
 */
@Singleton
class InPersonVerifier @Inject constructor(
    private val contactDao: ContactDao,
    private val keyVerifier: ContactKeyVerifier,
    private val keyManager: KeyManager,
) {

    /** This phone's own code, to show or send; null before the account exists. */
    fun myCode(): InPersonCode? {
        val userId = keyManager.getUserId() ?: return null
        val fingerprint = keyManager.withIdentityKeyPair { keys ->
            KeyFingerprint.compute(
                keys.publicIdentityKey,
                keys.publicSigningKey,
                keyManager.getPqcEncapsulationKey() ?: ByteArray(0),
                keyManager.getMlDsaPublicKey() ?: ByteArray(0),
            )
        } ?: return null
        return InPersonCode(userId, fingerprint)
    }

    suspend fun verify(contactId: String, code: InPersonCode): InPersonResult {
        if (code.userId != contactId) return InPersonResult.OtherAccount
        val contact = contactDao.getByUserId(contactId) ?: return InPersonResult.NotAContact
        var pinned = contact.keyFingerprint
        if (pinned != code.fingerprintHex) {
            // They may have rotated keys since this phone last looked (or the contact predates
            // fingerprints): take the relay's bundle only as far as the keys we hold endorse it.
            pinned = when (val check = keyVerifier.check(contactId)) {
                is KeyCheck.Known -> check.contact.keyFingerprint
                is KeyCheck.Mismatch -> return InPersonResult.KeysDiffer
                is KeyCheck.NotFound -> return InPersonResult.KeysDiffer
                // Offline, a rotation can't be ruled out: no verdict either way.
                is KeyCheck.Unavailable -> return InPersonResult.CantCheck
                is KeyCheck.Unknown -> return InPersonResult.NotAContact
            }
        }
        if (pinned != code.fingerprintHex) return InPersonResult.KeysDiffer
        return if (contactDao.markVerifiedInPerson(contactId, code.fingerprintHex) > 0) {
            InPersonResult.Verified
        } else {
            // The keys moved on between the check and the write: let the user try again.
            InPersonResult.CantCheck
        }
    }
}
