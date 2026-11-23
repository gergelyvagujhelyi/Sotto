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

    /**
     * Their keys match, but the QR code wasn't live: older than [InPersonVerifier.LIVE_WINDOW_S],
     * or not signed by them. A picture of their code, not their phone: not verified in person.
     */
    data object NotLive : InPersonResult

    /**
     * A QR code without the live part: their Sotto is older than 0.7.11. Their keys match,
     * but a plain code can't prove it's their phone: not verified in person.
     */
    data object OlderApp : InPersonResult
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
    private val classical: ClassicalKeyAgreement,
) {

    /**
     * This phone's live QR code ([InPersonCode], version 2): made for [nowMs] and signed with
     * this account's Ed25519 key, so a scanner can tell it's fresh. Shown anew every second.
     */
    fun myLiveCode(nowMs: Long = System.currentTimeMillis()): InPersonCode? = try {
        val code = myCode()
        if (code == null) {
            null
        } else {
            val at = nowMs / 1000
            val payload = InPersonCode.livePayload(code.userId, code.fingerprint, at)
            code.copy(issuedAt = at, signature = keyManager.sign(payload))
        }
    } catch (e: Exception) {
        // No signing key right now (a key change or a wipe going on): no QR code this second,
        // rather than a crash in the screen that shows it.
        null
    }

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

    /**
     * [requireLive]: the code came from a QR code, so it must be a fresh live one, signed by
     * the very keys it names. NFC codes are exempt: NFC only reaches a phone held against this one.
     */
    suspend fun verify(
        contactId: String,
        code: InPersonCode,
        requireLive: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): InPersonResult {
        if (code.userId != contactId) return InPersonResult.OtherAccount
        var contact = contactDao.getByUserId(contactId) ?: return InPersonResult.NotAContact
        var pinned = contact.keyFingerprint
        if (pinned != code.fingerprintHex) {
            // They may have rotated keys since this phone last looked (or the contact predates
            // fingerprints): take the relay's bundle only as far as the keys we hold endorse it.
            pinned = when (val check = keyVerifier.check(contactId)) {
                is KeyCheck.Known -> {
                    contact = check.contact
                    check.contact.keyFingerprint
                }
                is KeyCheck.Mismatch -> return InPersonResult.KeysDiffer
                is KeyCheck.NotFound -> return InPersonResult.KeysDiffer
                // Offline, a rotation can't be ruled out: no verdict either way.
                is KeyCheck.Unavailable -> return InPersonResult.CantCheck
                is KeyCheck.Unknown -> return InPersonResult.NotAContact
            }
        }
        if (pinned != code.fingerprintHex) return InPersonResult.KeysDiffer
        if (requireLive && !code.isLive) return InPersonResult.OlderApp
        if (requireLive && !isLive(code, contact.publicSigningKey, nowMs)) return InPersonResult.NotLive
        return if (contactDao.markVerifiedInPerson(contactId, code.fingerprintHex) > 0) {
            InPersonResult.Verified
        } else {
            // The keys moved on between the check and the write: let the user try again.
            InPersonResult.CantCheck
        }
    }

    /** Fresh, and signed by [signingKey] (theirs, part of the fingerprint that just matched). */
    private fun isLive(code: InPersonCode, signingKey: ByteArray, nowMs: Long): Boolean {
        val at = code.issuedAt ?: return false
        val signature = code.signature ?: return false
        if (kotlin.math.abs(nowMs / 1000 - at) > LIVE_WINDOW_S) return false
        return try {
            classical.verify(code.livePayload(), signature, signingKey)
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /**
         * How old (or, with a clock running ahead, how new) a live code may be. Generous enough
         * for two phones' clocks a little apart; a screenshot sent on later is too old.
         */
        const val LIVE_WINDOW_S = 60L
    }
}
