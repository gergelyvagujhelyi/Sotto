package com.sotto.app.data.repository

import com.sotto.app.crypto.ClassicalKeyAgreement
import com.sotto.app.crypto.ContactKeyVerifier
import com.sotto.app.crypto.KeyCheck
import com.sotto.app.crypto.PqcProvider
import com.sotto.app.crypto.VerifiedKeys
import com.sotto.app.data.local.dao.ContactDao
import com.sotto.app.data.remote.ApiService
import com.sotto.app.domain.model.GroupKeyState
import com.sotto.app.domain.repository.CryptoEngine
import com.sotto.app.group.GroupSignatures
import com.sotto.app.group.SigningKeys
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** The keys a phone uses for group members (ADR-32). */
interface MemberKeys {
    /**
     * Makes sure [userId] has keys pinned that match [fingerprint], the one their group's admin
     * vouched for. The user's own contacts keep their own keys (which must match); anyone else
     * gets a group-only entry, fetched from the relay and kept only if it matches.
     */
    suspend fun ensure(userId: String, fingerprint: String, name: String): GroupKeyState

    /** The signing keys pinned for [userId], or null. */
    suspend fun signingKeys(userId: String): SigningKeys?

    /** The fingerprint pinned for [userId], or null. */
    suspend fun pinnedFingerprint(userId: String): String?

    /** Pins keys that already verified an envelope from [userId] (a vouched member's first message). */
    suspend fun adopt(keys: VerifiedKeys, name: String)
}

@Singleton
class GroupKeys @Inject constructor(
    private val contactDao: ContactDao,
    private val apiService: ApiService,
    private val keyVerifier: ContactKeyVerifier,
    private val cryptoEngine: CryptoEngine,
) : MemberKeys {

    override suspend fun ensure(userId: String, fingerprint: String, name: String): GroupKeyState {
        val contact = contactDao.getByUserId(userId)
        if (contact != null && !contact.groupOnly) {
            // The user's own contact: their pin wins and must agree with the admin's.
            var pinned = contact.keyFingerprint
            if (pinned == null) {
                // Saved before fingerprints: the verifier fills it in from keys we trust.
                if (keyVerifier.check(userId) is KeyCheck.Unavailable) return GroupKeyState.PENDING
                pinned = contactDao.getByUserId(userId)?.keyFingerprint
            }
            return if (pinned == fingerprint) GroupKeyState.OK else GroupKeyState.MISMATCH
        }
        if (contact != null && contact.keyFingerprint == fingerprint) return GroupKeyState.OK
        // Not pinned yet, or the admin now vouches for other keys: take the relay's only if they match.
        val bundle = try {
            apiService.fetchKeyBundle(userId)
        } catch (_: Exception) {
            return GroupKeyState.PENDING
        } ?: return GroupKeyState.MISMATCH
        if (bundle.userId != userId) return GroupKeyState.MISMATCH
        val keys = VerifiedKeys.parse(
            userId, bundle.publicIdentityKey, bundle.publicSigningKey, bundle.pqcEncapsulationKey, bundle.pqcSigningKey,
        ) ?: return GroupKeyState.MISMATCH
        if (keys.fingerprintHex != fingerprint) return GroupKeyState.MISMATCH
        val pinned = contactDao.getByUserId(userId)
        if (pinned != null && !pinned.groupOnly) {
            // They became a contact meanwhile: as above.
            return if (pinned.keyFingerprint == fingerprint) GroupKeyState.OK else GroupKeyState.MISMATCH
        }
        if (pinned != null) {
            // A group-only pin the admin replaced: sessions bound to the old keys go.
            cryptoEngine.deleteSession(userId)
        }
        adopt(keys, name.ifBlank { pinned?.displayName ?: userId.take(8) })
        return GroupKeyState.OK
    }

    override suspend fun signingKeys(userId: String): SigningKeys? =
        contactDao.getByUserId(userId)?.let { SigningKeys(it.publicSigningKey, it.pqcSigningKey) }

    override suspend fun pinnedFingerprint(userId: String): String? {
        val contact = contactDao.getByUserId(userId) ?: return null
        contact.keyFingerprint?.let { return it }
        // Saved before fingerprints: filled in from the keys we trust, if the relay answers.
        keyVerifier.check(userId)
        return contactDao.getByUserId(userId)?.keyFingerprint
    }

    override suspend fun adopt(keys: VerifiedKeys, name: String) {
        val existing = contactDao.getByUserId(keys.userId)
        if (existing != null && !existing.groupOnly) return
        contactDao.upsert(
            keys.toNewContact(verified = false).copy(
                displayName = name.ifBlank { keys.userId.take(8) },
                groupOnly = true,
                pqcEstablished = existing?.pqcEstablished == true,
            ),
        )
    }
}

/** Signs as this account and checks signatures (ADR-15: Ed25519, plus ML-DSA-65 when there's a key). */
@Singleton
class AccountGroupSignatures @Inject constructor(
    private val keyManager: com.sotto.app.crypto.KeyManager,
    private val classical: ClassicalKeyAgreement,
    private val pqcProvider: PqcProvider,
) : GroupSignatures {
    override fun sign(data: ByteArray): Pair<String, String> {
        val ed = Base64.getEncoder().encodeToString(keyManager.sign(data))
        val mlDsaKey = keyManager.getMlDsaPrivateKey() ?: return ed to ""
        return try {
            ed to Base64.getEncoder().encodeToString(pqcProvider.sign(data, mlDsaKey))
        } finally {
            mlDsaKey.fill(0)
        }
    }

    override fun verify(data: ByteArray, signature: String, pqcSignature: String, keys: SigningKeys): Boolean {
        val ed = try {
            Base64.getDecoder().decode(signature)
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (!runCatching { classical.verify(data, ed, keys.ed25519) }.getOrDefault(false)) return false
        if (keys.mlDsa.isEmpty()) return true
        // A post-quantum account must sign with ML-DSA too: dropping it would be a downgrade.
        if (pqcSignature.isEmpty()) return false
        val pq = try {
            Base64.getDecoder().decode(pqcSignature)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return runCatching { pqcProvider.verify(data, pq, keys.mlDsa) }.getOrDefault(false)
    }
}
