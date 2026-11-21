package com.sotto.app.data.repository

import android.util.Base64
import com.sotto.app.crypto.KeyFingerprint
import com.sotto.app.crypto.KeyManager
import com.sotto.app.crypto.VerifiedKeys
import com.sotto.app.crypto.SessionResetSender
import com.sotto.app.data.local.dao.ContactDao
import com.sotto.app.data.local.dao.ConversationDao
import com.sotto.app.data.local.dao.MessageDao
import com.sotto.app.data.local.entity.ContactEntity
import com.sotto.app.data.local.entity.MessageEntity
import com.sotto.app.data.remote.ApiService
import com.sotto.app.data.remote.dto.BootstrapRequest
import com.sotto.app.data.remote.dto.KeyRotationRequest
import com.sotto.app.domain.model.Contact
import com.sotto.app.domain.model.DisplayNames
import com.sotto.app.domain.model.Identity
import com.sotto.app.domain.model.MessageState
import com.sotto.app.crypto.PqcProvider
import com.sotto.app.domain.model.KeyType
import com.sotto.app.domain.repository.CryptoEngine
import com.sotto.app.domain.repository.IdentityRepository
import com.sotto.app.domain.repository.KeyPair
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityRepositoryImpl @Inject constructor(
    private val cryptoEngine: CryptoEngine,
    private val pqcProvider: PqcProvider,
    private val keyManager: KeyManager,
    private val apiService: ApiService,
    private val contactDao: ContactDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val sessionResetSender: SessionResetSender,
    private val keyVerifier: com.sotto.app.crypto.ContactKeyVerifier,
    private val contactNames: ContactNameStore,
) : IdentityRepository {

    override suspend fun hasIdentity(): Boolean = keyManager.hasIdentity()

    /**
     * Startup part of crash recovery (no network; runs before any identity access).
     *
     * Staged keys with the server-confirmed flag: the app died between the server
     * accepting the rotation and local promotion. Promote them now. Returns true
     * if keys were promoted, so the caller can notify contacts afterwards.
     *
     * Staged keys WITHOUT the flag are not discarded here: the server may have
     * accepted them even though we never saw its answer (timeout, process death).
     * Discarding them would leave the server on keys we no longer have, locking
     * the account out for good. [resolvePendingRotation] asks the server.
     */
    suspend fun recoverFromInterruptedKeyRotation(): Boolean {
        if (!keyManager.hasStagedKeys()) return false
        if (!keyManager.isRotationConfirmedByServer()) return false
        promoteConfirmedRotation()
        return true
    }

    /**
     * Settle an unconfirmed staged rotation by checking which signing key the
     * server holds for us. Returns true if nothing is pending any more.
     * Throws if the server can't be asked (the staged keys are kept).
     */
    suspend fun resolvePendingRotation(): Boolean = rotationMutex.withLock { resolvePendingRotationLocked() }

    private suspend fun resolvePendingRotationLocked(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        if (!keyManager.hasStagedKeys()) return@withContext true
        if (keyManager.isRotationConfirmedByServer()) {
            promoteConfirmedRotation()
            notifyContactsOfRotation()
            return@withContext true
        }
        val staged = keyManager.getStagedPublicSigningKey()
        val userId = keyManager.getUserId()
        if (staged == null || userId == null) {
            keyManager.clearStagedKeys()
            return@withContext true
        }
        val bundle = apiService.fetchKeyBundle(userId) // throws on network error: keep staged keys
        val serverSigningKey = bundle?.publicSigningKey?.let {
            try { Base64.decode(it, Base64.NO_WRAP) } catch (_: IllegalArgumentException) { null }
        }
        if (serverSigningKey != null && serverSigningKey.contentEquals(staged)) {
            // The server took the rotation; we just never saw its answer.
            keyManager.markRotationConfirmedByServer()
            promoteConfirmedRotation()
            notifyContactsOfRotation()
        } else {
            // Server still has the old keys (or none): the rotation did not happen.
            keyManager.clearStagedKeys()
        }
        true
    }

    /** Promote confirmed staged keys and drop sessions bound to the old identity key. */
    private suspend fun promoteConfirmedRotation() {
        keyManager.promoteStagedKeys()
        keyManager.getUserId()?.let {
            keyManager.withIdentityKeyPair { kp ->
                keyManager.storeShareCode(localShareCode(kp.publicIdentityKey, kp.publicSigningKey))
            }
        }
        keyManager.clearAllPeerSessionFlags()
        cryptoEngine.clearAllSessions()
    }

    private val rotationMutex = kotlinx.coroutines.sync.Mutex()

    private companion object {
        const val ROTATION_SETTLE_DELAY_MS = 3_000L
    }

    override suspend fun getIdentity(): Identity? {
        // Public keys only: the pair comes back with its private keys wiped.
        val keyPair = keyManager.withIdentityKeyPair { it } ?: return null
        val userId = keyManager.getUserId() ?: return null
        // Always derived locally from our own keys: the share code is a fingerprint
        // of the full key bundle, and must never depend on what the server says.
        val shareCode = localShareCode(keyPair.publicIdentityKey, keyPair.publicSigningKey)
        if (keyManager.getShareCode() != shareCode) keyManager.storeShareCode(shareCode)
        val createdAtMs = keyManager.getCreatedAt()
        val createdAt = if (createdAtMs > 0) {
            Instant.fromEpochMilliseconds(createdAtMs)
        } else {
            // Legacy identity without stored timestamp — backfill with now
            val now = Clock.System.now()
            keyManager.storeCreatedAt(now.toEpochMilliseconds())
            now
        }
        return Identity(
            userId = userId,
            publicSigningKey = keyPair.publicSigningKey,
            publicIdentityKey = keyPair.publicIdentityKey,
            createdAt = createdAt,
            shareCode = shareCode,
        )
    }

    override suspend fun getOrCreateIdentity(keyType: KeyType, inviteCode: String): Identity {
        getIdentity()?.let { return it }

        // Keys without a userId: an earlier sign-up failed, or its reply got lost after the
        // relay had made the account. Reuse them (the relay answers the same keys with the
        // same account), so a lost reply can't use up a one-time invite.
        reusableKeys(keyType)?.let { (keyPair, pqcEk, pqcSigningKey) ->
            return register(keyPair, pqcEk, pqcSigningKey, inviteCode)
        }

        val keyPair = run {
            val kp = cryptoEngine.generateIdentity()
            keyManager.storeIdentityKeyPair(kp)
            kp.wipePrivateKeys()
        }

        // Generate PQC keys only if the user chose hybrid post-quantum.
        // If the user explicitly chose PQC, key generation MUST succeed —
        // silent fallback to classical would be a cryptographic downgrade.
        // Always regenerate PQC keys alongside classical keys to keep the
        // key bundle consistent — reusing orphaned PQC keys with new classical
        // keys would produce a mismatched bundle.
        var pqcEk = ByteArray(0)
        var pqcSigningKey = ByteArray(0)
        // A failed earlier onboarding attempt may have left post-quantum keys behind.
        // A classical identity must not keep them: they would change the local share
        // code and make us demand post-quantum sessions we never published keys for.
        keyManager.clearPqcKeys()
        if (keyType == KeyType.HYBRID_POST_QUANTUM) {
            val kemKeyPair = pqcProvider.generateKemKeyPair()
            keyManager.storePqcKeys(kemKeyPair.encapsulationKey, kemKeyPair.decapsulationKey)
            pqcEk = kemKeyPair.encapsulationKey
            kemKeyPair.decapsulationKey.fill(0)

            val dsaKeyPair = pqcProvider.generateSigningKeyPair()
            keyManager.storeMlDsaKeys(dsaKeyPair.publicKey, dsaKeyPair.privateKey)
            pqcSigningKey = dsaKeyPair.publicKey
            dsaKeyPair.privateKey.fill(0)
        }

        return register(keyPair, pqcEk, pqcSigningKey, inviteCode)
    }

    /**
     * The keys an earlier, unfinished sign-up stored, when they match the chosen key type
     * (post-quantum keys present exactly when post-quantum was chosen); null otherwise.
     */
    private fun reusableKeys(keyType: KeyType): Triple<KeyPair, ByteArray, ByteArray>? {
        // Registering needs the public keys only: the pair comes back with its private keys wiped.
        val keyPair = keyManager.withIdentityKeyPair { it } ?: return null
        val pqcEk = keyManager.getPqcEncapsulationKey()
        val pqcSigningKey = keyManager.getMlDsaPublicKey()
        return when {
            keyType == KeyType.HYBRID_POST_QUANTUM && pqcEk != null && pqcSigningKey != null ->
                Triple(keyPair, pqcEk, pqcSigningKey)
            keyType != KeyType.HYBRID_POST_QUANTUM && pqcEk == null && pqcSigningKey == null ->
                Triple(keyPair, ByteArray(0), ByteArray(0))
            else -> null
        }
    }

    /** Registers the keys with the relay and stores the account it gives them. */
    private suspend fun register(keyPair: KeyPair, pqcEk: ByteArray, pqcSigningKey: ByteArray, inviteCode: String): Identity {
        val response = apiService.bootstrapIdentity(
            BootstrapRequest(
                publicSigningKey = Base64.encodeToString(keyPair.publicSigningKey, Base64.NO_WRAP),
                publicIdentityKey = Base64.encodeToString(keyPair.publicIdentityKey, Base64.NO_WRAP),
                pqcEncapsulationKey = if (pqcEk.isNotEmpty()) Base64.encodeToString(pqcEk, Base64.NO_WRAP) else "",
                pqcSigningKey = if (pqcSigningKey.isNotEmpty()) Base64.encodeToString(pqcSigningKey, Base64.NO_WRAP) else "",
                inviteCode = inviteCode,
            )
        )

        val now = Clock.System.now()
        val shareCode = KeyFingerprint.shareCode(
            KeyFingerprint.compute(keyPair.publicIdentityKey, keyPair.publicSigningKey, pqcEk, pqcSigningKey)
        )
        if (response.shareCode != shareCode) {
            // Peers verify the share code against our keys, so the server's value
            // must match. A mismatch means the server derives it differently.
            android.util.Log.e("IdentityRepo", "Server share code does not match local key fingerprint")
        }
        keyManager.storeUserId(response.userId)
        keyManager.storeShareCode(shareCode)
        keyManager.storeCreatedAt(now.toEpochMilliseconds())

        return Identity(
            userId = response.userId,
            publicSigningKey = keyPair.publicSigningKey,
            publicIdentityKey = keyPair.publicIdentityKey,
            createdAt = now,
            shareCode = shareCode,
        )
    }

    override suspend fun addContact(shareCode: String): Contact {
        val resolved = apiService.resolveShareCode(shareCode)
            ?: throw IllegalArgumentException("Unknown share code")

        if (resolved.userId == keyManager.getUserId()) {
            throw IllegalArgumentException("Cannot add yourself as a contact")
        }

        val keys = VerifiedKeys.parse(
            userId = resolved.userId,
            publicIdentityKey = resolved.publicIdentityKey,
            publicSigningKey = resolved.publicSigningKey,
            pqcEncapsulationKey = resolved.pqcEncapsulationKey,
            pqcSigningKey = resolved.pqcSigningKey,
        ) ?: throw IllegalStateException("Malformed key bundle for ${resolved.userId.take(8)}")

        // The share code is a fingerprint of the full key bundle. If the keys the
        // server returned don't hash to the code the user entered/scanned, the
        // server (or someone in between) substituted them.
        if (keys.shareCode != shareCode) {
            throw SecurityException("The keys returned for this share code do not match it — refusing to add contact")
        }

        val existing = contactDao.getByUserId(keys.userId)
        var keysReplacedByShareCode = false
        val contactEntity = if (existing != null) {
            val existingFp = existing.keyFingerprint
            var sameKeys = if (existingFp != null) {
                existingFp == keys.fingerprintHex
            } else {
                existing.publicIdentityKey.contentEquals(keys.publicIdentityKey) &&
                    existing.publicSigningKey.contentEquals(keys.publicSigningKey)
            }
            if (!sameKeys) {
                // The contact may have rotated keys with a valid endorsement that we
                // haven't picked up yet (the verifier then updates the contact).
                val check = keyVerifier.check(keys.userId)
                sameKeys = check is com.sotto.app.crypto.KeyCheck.Known &&
                    check.keys.fingerprintHex == keys.fingerprintHex
            }
            if (!sameKeys) {
                // Keys changed without an endorsement we can verify (e.g. they
                // rotated twice while we were offline: the server only keeps the
                // latest endorsement). The user just entered a share code that
                // commits to exactly these keys, which is an out-of-band
                // confirmation, so adopt them. Without this the contact could
                // never be recovered.
                keysReplacedByShareCode = true
            }
            // Out-of-band share code matches the keys: verified.
            (contactDao.getByUserId(keys.userId) ?: existing).copy(
                verified = true,
                publicIdentityKey = keys.publicIdentityKey,
                publicSigningKey = keys.publicSigningKey,
                pqcSigningKey = keys.pqcSigningKey,
                keyFingerprint = keys.fingerprintHex,
            )
        } else {
            keys.toNewContact(verified = true)
        }

        // Establish the crypto session BEFORE persisting a new contact, so a failure
        // doesn't leave an orphaned contact without a working session.
        if (existing == null) {
            val info = cryptoEngine.ensureSession(keys.toPeerKeys())
            keyManager.setPeerPqcEstablished(keys.userId, info.pqcEstablished)
            contactDao.upsert(contactEntity.copy(pqcEstablished = info.pqcEstablished))
        } else if (keysReplacedByShareCode) {
            // Sessions were bound to the old keys; start over with the confirmed ones.
            cryptoEngine.deleteSession(keys.userId)
            keyManager.clearPeerSessionFlags(keys.userId)
            val info = cryptoEngine.ensureSession(keys.toPeerKeys())
            keyManager.setPeerPqcEstablished(keys.userId, info.pqcEstablished)
            contactDao.upsert(contactEntity.copy(pqcEstablished = info.pqcEstablished))
            recordKeyChangeConfirmed(keys.userId)
        } else {
            contactDao.upsert(contactEntity)
        }

        return contactEntity.toContact().copy(verified = true)
    }

    override fun getContacts(): Flow<List<Contact>> {
        return contactDao.getAll().map { entities -> entities.map { it.toContact() } }
    }

    override suspend fun getContact(userId: String): Contact? {
        return contactDao.getByUserId(userId)?.toContact()
    }

    override suspend fun rotateIdentityKeys() = rotationMutex.withLock {
        // A previous rotation whose outcome we never learned must be settled first:
        // staging new keys would overwrite keys the server may already be using.
        resolvePendingRotationLocked()
        rotateIdentityKeysLocked()
    }

    private suspend fun rotateIdentityKeysLocked() {
        // Generate fresh identity keys
        val newKeyPair = cryptoEngine.generateIdentity()

        // Generate fresh PQC keys if current identity has them.
        // If the user has PQC keys, regeneration MUST succeed — partial rotation
        // (new classical + stale PQC) would mix key epochs.
        val currentPqcEk = keyManager.getPqcEncapsulationKey()
        val hasPqc = currentPqcEk != null && currentPqcEk.isNotEmpty()
        val newPqcEk = if (hasPqc) {
            pqcProvider.generateKemKeyPair()
        } else null
        val currentMlDsa = keyManager.getMlDsaPublicKey()
        val hasMlDsa = currentMlDsa != null && currentMlDsa.isNotEmpty()
        val newMlDsa = if (hasMlDsa) {
            pqcProvider.generateSigningKeyPair()
        } else null

        // Base64-encode the new public keys
        val newSignB64 = Base64.encodeToString(newKeyPair.publicSigningKey, Base64.NO_WRAP)
        val newIdB64 = Base64.encodeToString(newKeyPair.publicIdentityKey, Base64.NO_WRAP)
        val newPqcB64 = newPqcEk?.let {
            Base64.encodeToString(it.encapsulationKey, Base64.NO_WRAP)
        }
        val newMlDsaB64 = newMlDsa?.let {
            Base64.encodeToString(it.publicKey, Base64.NO_WRAP)
        }

        // Proof of possession: sign the new public_signing_key B64 string
        // with the NEW private signing key (server verifies with new public key)
        val proofData = newSignB64.toByteArray(Charsets.UTF_8)
        val kf = java.security.KeyFactory.getInstance("Ed25519", "BC")
        val newPrivKey = kf.generatePrivate(
            java.security.spec.PKCS8EncodedKeySpec(newKeyPair.privateSigningKey)
        )
        val sig = java.security.Signature.getInstance("Ed25519", "BC")
        sig.initSign(newPrivKey)
        sig.update(proofData)
        val proofB64 = Base64.encodeToString(sig.sign(), Base64.NO_WRAP)

        // ML-DSA proof-of-possession: sign the new pqc_signing_key B64 with the new
        // ML-DSA private key. Done BEFORE staging: if it fails the whole rotation
        // aborts. Rotating without the ML-DSA key would leave the server and the
        // endorsement on the old key while the new one got promoted locally.
        val pqcProofB64 = if (newMlDsa != null && newMlDsaB64 != null) {
            Base64.encodeToString(
                pqcProvider.sign(newMlDsaB64.toByteArray(Charsets.UTF_8), newMlDsa.privateKey),
                Base64.NO_WRAP,
            )
        } else ""

        // Stage new keys locally BEFORE the server call so that if the server
        // accepts but the app crashes before local promotion, the next launch
        // can recover by promoting the staged keys.
        keyManager.stageIdentityKeyPair(newKeyPair)
        if (newPqcEk != null) {
            keyManager.stagePqcKeys(newPqcEk.encapsulationKey, newPqcEk.decapsulationKey)
        }
        if (newMlDsa != null) {
            keyManager.stageMlDsaKeys(newMlDsa.publicKey, newMlDsa.privateKey)
        }

        // Zeroize PQC private keys now that staging and proof signing are done
        newPqcEk?.decapsulationKey?.fill(0)
        newMlDsa?.privateKey?.fill(0)

        // Endorse the new key set with the CURRENT keys. Contacts only accept new
        // keys for us if this endorsement verifies against the keys they trust.
        val previousFingerprint = keyManager.withIdentityKeyPair { currentKeys ->
            KeyFingerprint.compute(
                currentKeys.publicIdentityKey,
                currentKeys.publicSigningKey,
                currentPqcEk ?: ByteArray(0),
                currentMlDsa ?: ByteArray(0),
            )
        } ?: throw IllegalStateException("No local identity")
        val newFingerprint = KeyFingerprint.compute(
            newKeyPair.publicIdentityKey,
            newKeyPair.publicSigningKey,
            newPqcEk?.encapsulationKey ?: (currentPqcEk ?: ByteArray(0)),
            newMlDsa?.publicKey ?: (currentMlDsa ?: ByteArray(0)),
        )
        val localUserId = keyManager.getUserId() ?: throw IllegalStateException("No identity")
        val rotationPayload = KeyFingerprint.rotationPayload(localUserId, previousFingerprint, newFingerprint)
        val rotationSigB64 = Base64.encodeToString(keyManager.sign(rotationPayload), Base64.NO_WRAP)
        val rotationPqcSigB64 = if (hasMlDsa) {
            val mlDsaPriv = keyManager.getMlDsaPrivateKey()
                ?: throw IllegalStateException("ML-DSA key missing — cannot endorse rotation")
            try {
                Base64.encodeToString(pqcProvider.sign(rotationPayload, mlDsaPriv), Base64.NO_WRAP)
            } finally {
                mlDsaPriv.fill(0)
            }
        } else ""

        // From here on the outcome must be settled even if the screen is left:
        // a cancelled call may still have reached the server.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            completeRotation(
                KeyRotationRequest(
                    publicSigningKey = newSignB64,
                    publicIdentityKey = newIdB64,
                    pqcEncapsulationKey = newPqcB64,
                    pqcSigningKey = newMlDsaB64,
                    newKeyProof = proofB64,
                    pqcKeyProof = pqcProofB64,
                    rotationPrevFingerprint = KeyFingerprint.toHex(previousFingerprint),
                    rotationSignature = rotationSigB64,
                    rotationPqcSignature = rotationPqcSigB64,
                ),
                newFingerprint,
            )
        }
    }

    private suspend fun completeRotation(request: KeyRotationRequest, newFingerprint: ByteArray) {
        // Call server (authenticated with the CURRENT signing key via authToken)
        val response = try {
            apiService.rotateKeys(request)
        } catch (e: Exception) {
            // The request may have reached the server even though we got no answer
            // (timeout, lost connection). Ask the server which keys it holds instead
            // of assuming it didn't. If it can't be asked, the staged keys stay and
            // are settled on the next start or rotation attempt.
            try {
                // A timed-out request may still be running on the server; give it
                // a moment to commit before checking which keys it holds.
                kotlinx.coroutines.delay(ROTATION_SETTLE_DELAY_MS)
                resolvePendingRotationLocked()
            } catch (_: Exception) {
            }
            val rotated = keyManager.withIdentityKeyPair { active ->
                Base64.encodeToString(active.publicSigningKey, Base64.NO_WRAP) == request.publicSigningKey
            } == true
            if (rotated) return // the server had taken it; already promoted and contacts notified
            throw e
        }

        // Server accepted — mark confirmed (persisted synchronously) so crash
        // recovery knows the server has the new keys, then promote.
        keyManager.markRotationConfirmedByServer()
        if (response.shareCode != KeyFingerprint.shareCode(newFingerprint)) {
            android.util.Log.e("IdentityRepo", "Server share code does not match local key fingerprint")
        }
        promoteConfirmedRotation()
        notifyContactsOfRotation()
    }

    /**
     * Record key-change events and send session_reset to every contact.
     * Best-effort — a contact we can't reach recovers via decrypt-failure handling.
     */
    suspend fun notifyContactsOfRotation() {
        val localUserId = keyManager.getUserId() ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        try {
            val contacts = contactDao.getAll().first()
            for (contact in contacts) {
                // Insert a key-change event into each conversation
                val conversation = conversationDao.getByContactId(contact.userId)
                if (conversation != null) {
                    messageDao.insert(MessageEntity(
                        id = "keychange-local-${contact.userId}-$now",
                        conversationId = conversation.id,
                        senderId = localUserId,
                        recipientId = contact.userId,
                        encryptedBody = ByteArray(0),
                        nonce = ByteArray(0),
                        plaintext = "",
                        state = MessageState.KEY_ROTATED_LOCAL.name,
                        timestamp = now,
                        expiresAt = null,
                        isOutgoing = true,
                    ))
                }

                try {
                    sessionResetSender.send(contact.userId, waitForRateLimit = true)
                } catch (e: Exception) {
                    android.util.Log.w("IdentityRepo",
                        "Failed to send session_reset to ${contact.userId.take(8)}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("IdentityRepo",
                "Failed to notify contacts of key rotation: ${e.message}")
        }
    }

    /** Show a "keys changed" event in the conversation after keys were replaced via share code. */
    private suspend fun recordKeyChangeConfirmed(peerId: String) {
        val conversation = conversationDao.getByContactId(peerId) ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        messageDao.insert(MessageEntity(
            id = "keychange-$peerId-$now",
            conversationId = conversation.id,
            senderId = peerId,
            recipientId = keyManager.getUserId() ?: "",
            encryptedBody = ByteArray(0),
            nonce = ByteArray(0),
            plaintext = "",
            state = MessageState.KEY_ROTATED_REMOTE.name,
            timestamp = now,
            expiresAt = null,
            isOutgoing = false,
        ))
    }

    /** Zeroes the private keys in place (for a new pair once it is stored); returns the pair. */
    private fun KeyPair.wipePrivateKeys(): KeyPair = apply {
        privateIdentityKey.fill(0)
        privateSigningKey.fill(0)
    }

    private fun localShareCode(publicIdentityKey: ByteArray, publicSigningKey: ByteArray): String {
        return KeyFingerprint.shareCode(
            KeyFingerprint.compute(
                publicIdentityKey,
                publicSigningKey,
                keyManager.getPqcEncapsulationKey() ?: ByteArray(0),
                keyManager.getMlDsaPublicKey() ?: ByteArray(0),
            )
        )
    }

    override suspend fun fetchKeyBundle(userId: String): Contact? {
        val bundle = apiService.fetchKeyBundle(userId) ?: return null
        val pubIdKey = try {
            Base64.decode(bundle.publicIdentityKey, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Malformed Base64 in identity key bundle for ${userId.take(8)}", e)
        }
        val pubSignKey = try {
            Base64.decode(bundle.publicSigningKey, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Malformed Base64 in signing key bundle for ${userId.take(8)}", e)
        }
        val pqcSignKey = if (bundle.pqcSigningKey.isNotEmpty()) {
            try {
                Base64.decode(bundle.pqcSigningKey, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                ByteArray(0)
            }
        } else ByteArray(0)
        return Contact(
            userId = bundle.userId,
            displayName = bundle.userId.take(8),
            publicIdentityKey = pubIdKey,
            publicSigningKey = pubSignKey,
            pqcSigningKey = pqcSignKey,
        )
    }

    override fun isPqcSession(peerId: String): Boolean = keyManager.isPeerPqcEstablished(peerId)

    override fun observePqcSession(peerId: String): Flow<Boolean> {
        return contactDao.observeByUserId(peerId).map { it?.pqcEstablished == true }
    }

    override fun observeContact(userId: String): Flow<Contact?> =
        contactDao.observeByUserId(userId).map { it?.toContact() }

    override suspend fun renameContact(userId: String, nickname: String?) =
        contactNames.setNickname(userId, nickname)

    override suspend fun applyProfileName(userId: String, name: String) =
        contactNames.setProfileName(userId, name)

    override suspend fun getMyName(): String? = keyManager.getDisplayName()

    override suspend fun setMyName(name: String?) {
        keyManager.storeDisplayName(DisplayNames.sanitize(name))
    }

    private fun ContactEntity.toContact() = Contact(
        userId = userId,
        displayName = displayName,
        publicIdentityKey = publicIdentityKey,
        publicSigningKey = publicSigningKey,
        pqcSigningKey = pqcSigningKey,
        verified = verified,
        nickname = nickname,
        profileName = profileName,
    )
}
