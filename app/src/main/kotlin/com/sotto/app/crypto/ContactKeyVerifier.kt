package com.sotto.app.crypto

import com.sotto.app.data.local.dao.ContactDao
import com.sotto.app.data.local.entity.ContactEntity
import com.sotto.app.data.remote.ApiService
import com.sotto.app.data.remote.dto.KeyBundleDto
import com.sotto.app.domain.repository.CryptoEngine
import com.sotto.app.domain.repository.PeerKeys
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Public keys of a peer whose fingerprint has been computed locally. */
class VerifiedKeys(
    val userId: String,
    val publicIdentityKey: ByteArray,
    val publicSigningKey: ByteArray,
    val pqcEncapsulationKey: ByteArray,
    val pqcSigningKey: ByteArray,
) {
    val fingerprint: ByteArray =
        KeyFingerprint.compute(publicIdentityKey, publicSigningKey, pqcEncapsulationKey, pqcSigningKey)
    val fingerprintHex: String = KeyFingerprint.toHex(fingerprint)
    val shareCode: String = KeyFingerprint.shareCode(fingerprint)

    fun toPeerKeys() = PeerKeys(userId, publicIdentityKey, pqcEncapsulationKey)

    fun toNewContact(verified: Boolean) = ContactEntity(
        userId = userId,
        displayName = userId.take(8),
        publicIdentityKey = publicIdentityKey,
        publicSigningKey = publicSigningKey,
        pqcSigningKey = pqcSigningKey,
        verified = verified,
        keyFingerprint = fingerprintHex,
    )

    companion object {
        /** Decode base64 key fields; returns null if any field is malformed. */
        fun parse(
            userId: String,
            publicIdentityKey: String,
            publicSigningKey: String,
            pqcEncapsulationKey: String,
            pqcSigningKey: String,
        ): VerifiedKeys? = try {
            val dec = Base64.getDecoder()
            VerifiedKeys(
                userId = userId,
                publicIdentityKey = dec.decode(publicIdentityKey),
                publicSigningKey = dec.decode(publicSigningKey),
                pqcEncapsulationKey = if (pqcEncapsulationKey.isEmpty()) ByteArray(0) else dec.decode(pqcEncapsulationKey),
                pqcSigningKey = if (pqcSigningKey.isEmpty()) ByteArray(0) else dec.decode(pqcSigningKey),
            ).takeIf { it.publicIdentityKey.isNotEmpty() && it.publicSigningKey.isNotEmpty() }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

sealed interface KeyCheck {
    /** Stored contact; the server bundle matches it (or is a properly endorsed rotation). */
    data class Known(val keys: VerifiedKeys, val contact: ContactEntity, val rotated: Boolean) : KeyCheck
    /** No stored contact: keys come from the server unverified (trust on first use). */
    data class Unknown(val keys: VerifiedKeys) : KeyCheck
    /** The server returned keys that do not match what we trust for this user. */
    data class Mismatch(val reason: String) : KeyCheck
    /** The bundle could not be fetched right now (network/server error): retry later. */
    data object Unavailable : KeyCheck
    /** The server has no such user (404): permanent, e.g. the account was deleted. */
    data object NotFound : KeyCheck
}

/**
 * Decides whether keys handed out by the relay server can be trusted.
 *
 * The server is not trusted. Keys are accepted only if:
 * - they match the fingerprint stored for the contact (which originally came from
 *   a share code obtained out-of-band, or from first contact), or
 * - they are a rotation endorsed by signatures from the contact's stored keys
 *   (Ed25519, plus ML-DSA-65 when the contact has a PQC signing key).
 *
 * Anything else is a [KeyCheck.Mismatch]; callers must refuse to use the keys.
 */
@Singleton
class ContactKeyVerifier @Inject constructor(
    private val apiService: ApiService,
    private val contactDao: ContactDao,
    private val classicalKeyAgreement: ClassicalKeyAgreement,
    private val pqcProvider: PqcProvider,
    private val cryptoEngine: CryptoEngine,
) {

    suspend fun check(userId: String): KeyCheck {
        val bundle = try {
            apiService.fetchKeyBundle(userId)
        } catch (_: Exception) {
            return KeyCheck.Unavailable
        } ?: return KeyCheck.NotFound
        if (bundle.userId != userId) return KeyCheck.Mismatch("Bundle is for a different user")

        val keys = VerifiedKeys.parse(
            userId, bundle.publicIdentityKey, bundle.publicSigningKey,
            bundle.pqcEncapsulationKey, bundle.pqcSigningKey,
        ) ?: return KeyCheck.Mismatch("Malformed key bundle")

        val contact = contactDao.getByUserId(userId) ?: return KeyCheck.Unknown(keys)

        if (matchesStored(contact, keys)) {
            val updated = if (contact.keyFingerprint == null) {
                // Legacy contact: backfill the fingerprint now that the keys we
                // already trusted are confirmed to be part of this bundle.
                contact.copy(keyFingerprint = keys.fingerprintHex, pqcSigningKey = keys.pqcSigningKey)
                    .also { contactDao.upsert(it) }
            } else contact
            return KeyCheck.Known(keys, updated, rotated = false)
        }

        if (isEndorsedRotation(contact, bundle, keys)) {
            val updated = contact.copy(
                publicIdentityKey = keys.publicIdentityKey,
                publicSigningKey = keys.publicSigningKey,
                pqcSigningKey = keys.pqcSigningKey,
                keyFingerprint = keys.fingerprintHex,
            )
            contactDao.upsert(updated)
            // Sessions were bound to the old identity key; never reuse them.
            cryptoEngine.deleteSession(userId)
            return KeyCheck.Known(keys, updated, rotated = true)
        }

        return KeyCheck.Mismatch("Keys changed without an endorsement from the previous keys")
    }

    private fun matchesStored(contact: ContactEntity, keys: VerifiedKeys): Boolean {
        contact.keyFingerprint?.let { return it == keys.fingerprintHex }
        // Legacy contact without a fingerprint: compare the keys we did store.
        // (The ML-KEM key was never stored, so it is trusted on first use here.)
        return contact.publicIdentityKey.contentEquals(keys.publicIdentityKey) &&
            contact.publicSigningKey.contentEquals(keys.publicSigningKey) &&
            (contact.pqcSigningKey.isEmpty() || contact.pqcSigningKey.contentEquals(keys.pqcSigningKey))
    }

    private fun isEndorsedRotation(contact: ContactEntity, bundle: KeyBundleDto, keys: VerifiedKeys): Boolean {
        if (bundle.rotationSignature.isEmpty() || bundle.rotationPrevFingerprint.isEmpty()) return false
        // The endorsement must come from the key set we currently trust.
        contact.keyFingerprint?.let { if (it != bundle.rotationPrevFingerprint) return false }
        // Never accept a rotation that drops post-quantum signing.
        if (contact.pqcSigningKey.isNotEmpty() && keys.pqcSigningKey.isEmpty()) return false

        return try {
            val prevFp = KeyFingerprint.fromHex(bundle.rotationPrevFingerprint)
            val payload = KeyFingerprint.rotationPayload(contact.userId, prevFp, keys.fingerprint)
            val sig = Base64.getDecoder().decode(bundle.rotationSignature)
            if (!classicalKeyAgreement.verify(payload, sig, contact.publicSigningKey)) return false
            if (contact.pqcSigningKey.isNotEmpty()) {
                if (bundle.rotationPqcSignature.isEmpty()) return false
                val pqcSig = Base64.getDecoder().decode(bundle.rotationPqcSignature)
                if (!pqcProvider.verify(payload, pqcSig, contact.pqcSigningKey)) return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
