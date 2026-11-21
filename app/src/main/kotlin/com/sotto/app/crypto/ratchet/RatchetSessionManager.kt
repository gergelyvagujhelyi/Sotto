package com.sotto.app.crypto.ratchet

import android.util.Log
import com.sotto.app.BuildConfig
import com.sotto.app.crypto.ClassicalKeyAgreement
import com.sotto.app.crypto.KeyManager
import com.sotto.app.crypto.PqcProvider
import com.sotto.app.crypto.hkdfSha256
import com.sotto.app.crypto.lengthPrefixed
import com.sotto.app.domain.repository.CryptoEngine
import com.sotto.app.domain.repository.DuplicateMessageException
import com.sotto.app.domain.repository.EncryptedEnvelope
import com.sotto.app.domain.repository.KeyPair
import com.sotto.app.domain.repository.PeerKeys
import com.sotto.app.domain.repository.SessionInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CryptoEngine implementation backed by the Double Ratchet protocol.
 *
 * Session establishment (initiator = whoever sends first, "Alice"):
 * 1. SK = HKDF( X25519(IK_A, IK_B) || ML-KEM shared secret ), bound to both identity keys.
 * 2. Alice runs [DoubleRatchet.initAlice] with a FRESH ephemeral ratchet key against
 *    Bob's identity key; Bob runs [DoubleRatchet.initBob] with his identity key pair.
 * 3. Every Alice message carries [SessionInit] (session tag + KEM ciphertext) until
 *    Bob replies, so a lost first message does not break the session.
 * 4. From the first reply on, every turn of the conversation performs a DH ratchet
 *    step with new ephemeral keys (forward secrecy + break-in recovery).
 *
 * Per peer we keep one *current* session (used for sending) plus a few *previous*
 * sessions that are only used to decrypt in-flight messages. If both peers start a
 * session at the same time, the one created by the peer with the lower identity key
 * wins; both sides converge on it and can still read messages from the other one.
 *
 * Decryption is transactional: it runs on a copy of the session state and the copy
 * is committed only if the message authenticates. Garbage, replays and duplicates
 * never destroy a session.
 */
@Singleton
class RatchetSessionManager @Inject constructor(
    private val classicalKeyAgreement: ClassicalKeyAgreement,
    private val pqcProvider: PqcProvider,
    private val keyManager: KeyManager,
) : CryptoEngine {

    private val ratchet = DoubleRatchet(classicalKeyAgreement)
    private val records = mutableMapOf<String, SessionRecord>()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val secureRandom = SecureRandom()

    override suspend fun generateIdentity(): KeyPair {
        val signing = classicalKeyAgreement.generateSigningKeyPair()
        val identity = classicalKeyAgreement.generateKeyAgreementKeyPair()
        return KeyPair(
            publicSigningKey = signing.first,
            privateSigningKey = signing.second,
            publicIdentityKey = identity.first,
            privateIdentityKey = identity.second,
        )
    }

    override suspend fun ensureSession(peer: PeerKeys): SessionInfo = mutex.withLock {
        val record = recordFor(peer)
        val state = sendableSession(peer, record)
        SessionInfo(state.pqcEstablished)
    }

    override suspend fun encrypt(peer: PeerKeys, plaintext: ByteArray): EncryptedEnvelope = mutex.withLock {
        val record = recordFor(peer)
        val state = sendableSession(peer, record)

        val init = if (state.isInitiator && !state.confirmed) {
            SessionInit(
                sessionTag = state.sessionTag,
                kemCiphertext = state.pendingKemCiphertext?.let { Base64.getEncoder().encodeToString(it) },
            )
        } else null

        val (header, ciphertext) = ratchet.encrypt(state, plaintext, init)

        try {
            persist(peer.userId, record)
        } catch (e: Exception) {
            // The chain advanced in memory but could not be saved. Never fall back
            // to an older persisted state (that would reuse message keys): drop all
            // sessions so the next send starts a fresh one, but keep the seen
            // session tags, or replayed old session inits would be accepted again.
            record.all().forEach { it.wipe() }
            record.current = null
            record.previous.clear()
            try {
                persist(peer.userId, record)
            } catch (_: Exception) {
                keyManager.removeRatchetState(storageKey(peer.userId))
            }
            throw e
        }

        EncryptedEnvelope(
            ciphertext = ciphertext,
            nonce = ratchet.headerBytes(header),
        )
    }

    override suspend fun decrypt(
        peer: PeerKeys,
        envelope: EncryptedEnvelope,
        requirePqc: Boolean,
    ): ByteArray = mutex.withLock {
        val headerBytes = envelope.nonce
        val header = try {
            json.decodeFromString<RatchetHeader>(String(headerBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw SecurityException("Malformed ratchet header", e)
        }
        validateHeader(header)

        val record = recordFor(peer)
        val init = header.init

        if (init != null) {
            val known = record.findByTag(init.sessionTag)
            if (known != null) {
                return@withLock decryptInto(peer.userId, record, known, header, envelope.ciphertext, headerBytes)
            }
            if (init.sessionTag in record.seenTags) {
                throw DuplicateMessageException("Replayed init for an expired session")
            }
            if (requirePqc && init.kemCiphertext == null) {
                throw SecurityException("Peer is post-quantum capable but started a classical-only session")
            }

            val candidate = createResponderSession(peer, init)
            val plaintext = try {
                ratchet.decrypt(candidate, header, envelope.ciphertext, headerBytes)
            } catch (e: Exception) {
                candidate.wipe()
                throw e
            }

            // Authenticated: adopt the new session.
            record.rememberTag(init.sessionTag)
            val current = record.current
            val weWinTie = current != null && current.isInitiator && !current.confirmed &&
                localWinsTie(peer)
            if (weWinTie) {
                // Simultaneous initiation and our session wins: keep sending on ours,
                // keep theirs only to read what they already sent.
                record.addPrevious(candidate)
            } else {
                record.archiveCurrent()
                record.current = candidate
            }
            persist(peer.userId, record)
            return@withLock plaintext
        }

        // Regular message: try current session, then previous ones.
        var duplicate: DuplicateMessageException? = null
        // Why each session failed (they can differ: wrong chain in one, a bad tag in another).
        val errors = mutableListOf<Exception>()
        for (state in record.all()) {
            try {
                return@withLock decryptInto(peer.userId, record, state, header, envelope.ciphertext, headerBytes)
            } catch (e: DuplicateMessageException) {
                duplicate = e
            } catch (e: Exception) {
                errors += e
            }
        }
        if (duplicate == null) {
            val senderKeyHex = Base64.getDecoder().decode(header.publicKey).toHex()
            if (senderKeyHex in record.retiredRemoteKeys) {
                // A chain of a session we dropped after newer ones replaced it: a late
                // duplicate or a relay replaying old traffic, not a peer out of sync.
                // Must not end up as a tombstone + session reset.
                throw DuplicateMessageException("Message from a retired session")
            }
        }
        throw duplicate ?: SecurityException("No session could decrypt the message", errors.lastOrNull())
            .apply { errors.dropLast(1).forEach(::addSuppressed) }
    }

    override suspend fun resetSession(peerUserId: String) = mutex.withLock {
        val record = loadRecord(peerUserId) ?: return@withLock
        record.archiveCurrent()
        persist(peerUserId, record)
    }

    override suspend fun deleteSession(peerUserId: String) = mutex.withLock {
        val record = loadRecord(peerUserId) ?: return@withLock
        // Drop all key material but keep the seen session tags: otherwise an old
        // session-init message replayed later would be accepted as a new session.
        record.all().forEach { it.wipe() }
        record.current = null
        record.previous.clear()
        persist(peerUserId, record)
    }

    override suspend fun clearAllSessions() = mutex.withLock {
        records.values.forEach { r -> r.all().forEach { it.wipe() } }
        records.clear()
        keyManager.removeAllRatchetStates()
    }

    override suspend fun sessionInfo(peerUserId: String): SessionInfo? = mutex.withLock {
        loadRecord(peerUserId)?.current?.let { SessionInfo(it.pqcEstablished) }
    }

    override suspend fun hasSendableSession(peer: PeerKeys): Boolean = mutex.withLock {
        val record = loadRecord(peer.userId) ?: return@withLock false
        record.remoteIdentityKey.contentEquals(peer.publicIdentityKey) &&
            record.current?.sendingChainKey != null
    }

    override suspend fun sign(data: ByteArray): ByteArray = keyManager.sign(data)

    override suspend fun verify(
        data: ByteArray,
        signature: ByteArray,
        publicSigningKey: ByteArray,
    ): Boolean = classicalKeyAgreement.verify(data, signature, publicSigningKey)

    // ── Session construction ─────────────────────────────────────────

    /** Current session if it can send, otherwise a newly created initiator session. */
    private fun sendableSession(peer: PeerKeys, record: SessionRecord): RatchetState {
        record.current?.takeIf { it.sendingChainKey != null }?.let { return it }
        val state = createInitiatorSession(peer)
        record.archiveCurrent()
        record.current = state
        record.rememberTag(state.sessionTag)
        persist(peer.userId, record)
        return state
    }

    private fun createInitiatorSession(peer: PeerKeys): RatchetState = keyManager.withIdentityKeyPair { local ->
        var kemSecret = ByteArray(0)
        var kemCiphertext: ByteArray? = null
        if (peer.pqcEncapsulationKey.isNotEmpty()) {
            // If the peer advertises PQC, encapsulation MUST succeed — falling back
            // to classical would be a silent downgrade.
            val enc = pqcProvider.encapsulate(peer.pqcEncapsulationKey)
            kemSecret = enc.sharedSecret
            kemCiphertext = enc.ciphertext
        }
        val pqc = kemSecret.isNotEmpty()

        val sk = deriveSessionSecret(
            localPrivateIdentityKey = local.privateIdentityKey,
            remotePublicIdentityKey = peer.publicIdentityKey,
            kemSecret = kemSecret,
            initiatorIdentityKey = local.publicIdentityKey,
            responderIdentityKey = peer.publicIdentityKey,
        )

        val state = ratchet.initAlice(sk, peer.publicIdentityKey.copyOf())
        sk.fill(0)
        state.sessionTag = newSessionTag()
        state.isInitiator = true
        state.confirmed = false
        state.pendingKemCiphertext = kemCiphertext
        state.pqcEstablished = pqc
        state
    } ?: throw IllegalStateException("No local identity")

    private fun createResponderSession(peer: PeerKeys, init: SessionInit): RatchetState = keyManager.withIdentityKeyPair { local ->
        var kemSecret = ByteArray(0)
        val kemCt = init.kemCiphertext
        if (kemCt != null) {
            val ct = try {
                Base64.getDecoder().decode(kemCt)
            } catch (e: IllegalArgumentException) {
                throw SecurityException("Invalid KEM ciphertext encoding", e)
            }
            val dk = keyManager.getPqcDecapsulationKey()
                ?: throw IllegalStateException("Received KEM ciphertext but no local decapsulation key")
            try {
                kemSecret = pqcProvider.decapsulate(ct, dk)
            } finally {
                dk.fill(0)
            }
        }
        val pqc = kemSecret.isNotEmpty()

        val sk = deriveSessionSecret(
            localPrivateIdentityKey = local.privateIdentityKey,
            remotePublicIdentityKey = peer.publicIdentityKey,
            kemSecret = kemSecret,
            initiatorIdentityKey = peer.publicIdentityKey,
            responderIdentityKey = local.publicIdentityKey,
        )

        // Bob's initial ratchet key pair is his identity key pair (like a signed
        // prekey in X3DH). It is replaced by a fresh ephemeral on the first DH step.
        // initBob keeps the array as its root key, so hand it a copy before wiping sk.
        val state = ratchet.initBob(
            sk.copyOf(),
            DhKeyPair(local.publicIdentityKey.copyOf(), local.privateIdentityKey.copyOf()),
        )
        sk.fill(0)
        state.sessionTag = init.sessionTag
        state.isInitiator = false
        state.confirmed = true
        state.pqcEstablished = pqc
        state
    } ?: throw IllegalStateException("No local identity")

    private fun deriveSessionSecret(
        localPrivateIdentityKey: ByteArray,
        remotePublicIdentityKey: ByteArray,
        kemSecret: ByteArray,
        initiatorIdentityKey: ByteArray,
        responderIdentityKey: ByteArray,
    ): ByteArray {
        val classical = classicalKeyAgreement.agree(localPrivateIdentityKey, remotePublicIdentityKey)
        val ikm = classical + kemSecret
        classical.fill(0)
        kemSecret.fill(0)
        val sk = hkdfSha256(
            ikm = ikm,
            salt = "ChatControll-v2-session".toByteArray(Charsets.UTF_8),
            info = "hybrid-key-establishment".toByteArray(Charsets.UTF_8) +
                lengthPrefixed(initiatorIdentityKey) + lengthPrefixed(responderIdentityKey),
            length = 32,
        )
        ikm.fill(0)
        return sk
    }

    /** Decrypt within [target] transactionally; commit the updated copy on success. */
    private fun decryptInto(
        peerUserId: String,
        record: SessionRecord,
        target: RatchetState,
        header: RatchetHeader,
        ciphertext: ByteArray,
        headerBytes: ByteArray,
    ): ByteArray {
        val working = target.deepCopy()
        val plaintext = try {
            ratchet.decrypt(working, header, ciphertext, headerBytes)
        } catch (e: Exception) {
            working.wipe()
            throw e
        }
        if (working.isInitiator && !working.confirmed && header.init == null) {
            // Only the responder sends non-init messages in a session we created:
            // the peer has the session, stop attaching the init data.
            working.confirmed = true
            working.pendingKemCiphertext = null
        }
        record.replace(target, working)
        target.wipe()
        persist(peerUserId, record)
        return plaintext
    }

    private fun validateHeader(header: RatchetHeader) {
        if (header.messageNumber < 0 || header.previousChainLength < 0) {
            throw SecurityException("Negative counter in ratchet header")
        }
        val pk = try {
            Base64.getDecoder().decode(header.publicKey)
        } catch (e: IllegalArgumentException) {
            throw SecurityException("Invalid base64 in ratchet header publicKey", e)
        }
        // X25519 public keys are X.509 SubjectPublicKeyInfo encoded (44 bytes) because
        // Bouncy Castle's KeyPairGenerator returns the JCA encoded form.
        if (pk.size != 44) throw SecurityException("Ratchet header publicKey must be 44 bytes, got ${pk.size}")
        header.init?.let {
            val tag = try {
                Base64.getDecoder().decode(it.sessionTag)
            } catch (e: IllegalArgumentException) {
                throw SecurityException("Invalid session tag", e)
            }
            if (tag.size != SESSION_TAG_BYTES) throw SecurityException("Invalid session tag length")
        }
    }

    private fun localWinsTie(peer: PeerKeys): Boolean =
        keyManager.withIdentityKeyPair { it.publicIdentityKey.toHex() < peer.publicIdentityKey.toHex() } ?: false

    private fun newSessionTag(): String {
        val tag = ByteArray(SESSION_TAG_BYTES).also { secureRandom.nextBytes(it) }
        return Base64.getEncoder().encodeToString(tag)
    }

    // ── Session records & persistence ────────────────────────────────

    /** Load (or create) the record for a peer; discard it if the peer's identity key changed. */
    private fun recordFor(peer: PeerKeys): SessionRecord {
        val existing = loadRecord(peer.userId)
        if (existing != null && existing.remoteIdentityKey.contentEquals(peer.publicIdentityKey)) {
            return existing
        }
        existing?.all()?.forEach { it.wipe() }
        val fresh = SessionRecord(
            remoteIdentityKey = peer.publicIdentityKey.copyOf(),
            seenTags = existing?.seenTags ?: mutableListOf(),
        )
        records[peer.userId] = fresh
        return fresh
    }

    private fun loadRecord(peerUserId: String): SessionRecord? {
        records[peerUserId]?.let { return it }
        val serialized = keyManager.loadRatchetState(storageKey(peerUserId)) ?: return null
        val record = try {
            json.decodeFromString<SerializableRecord>(serialized).toRecord()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("RatchetSession", "Failed to load persisted session record")
            keyManager.removeRatchetState(storageKey(peerUserId))
            return null
        }
        records[peerUserId] = record
        return record
    }

    private fun persist(peerUserId: String, record: SessionRecord) {
        val ok = keyManager.saveRatchetState(storageKey(peerUserId), json.encodeToString(record.toSerializable()))
        if (!ok) throw IllegalStateException("Failed to persist session state")
    }

    private fun storageKey(peerUserId: String) = "v2_$peerUserId"

    private class SessionRecord(
        val remoteIdentityKey: ByteArray,
        var current: RatchetState? = null,
        /** Receive-only sessions, newest first. */
        val previous: MutableList<RatchetState> = mutableListOf(),
        /** Session tags ever seen from/for this peer, oldest first (replay protection). */
        val seenTags: MutableList<String> = mutableListOf(),
        /** Peer ratchet keys (hex) of sessions that fell out of [previous], oldest first. */
        val retiredRemoteKeys: MutableList<String> = mutableListOf(),
    ) {
        fun all(): List<RatchetState> = listOfNotNull(current) + previous

        fun findByTag(tag: String): RatchetState? = all().firstOrNull { it.sessionTag == tag }

        fun archiveCurrent() {
            current?.let { addPrevious(it) }
            current = null
        }

        fun addPrevious(state: RatchetState) {
            previous.add(0, state)
            while (previous.size > MAX_PREVIOUS_SESSIONS) retire(previous.removeAt(previous.lastIndex))
        }

        /** Wipe a session that newer ones replaced, remembering its peer ratchet keys. */
        private fun retire(state: RatchetState) {
            // An unconfirmed initiator session's remote key is the peer's identity
            // key, not one the peer ratchets with: don't record it.
            val remote = state.remoteDhPublicKey?.takeIf { !state.isInitiator || state.confirmed }
            for (key in listOfNotNull(remote?.toHex()) + state.previousRemoteKeys) {
                if (key !in retiredRemoteKeys) retiredRemoteKeys.add(key)
            }
            while (retiredRemoteKeys.size > MAX_RETIRED_REMOTE_KEYS) retiredRemoteKeys.removeAt(0)
            state.wipe()
        }

        fun replace(old: RatchetState, new: RatchetState) {
            if (current === old) {
                current = new
            } else {
                val i = previous.indexOfFirst { it === old }
                if (i >= 0) previous[i] = new
            }
        }

        fun rememberTag(tag: String) {
            if (tag in seenTags) return
            seenTags.add(tag)
            while (seenTags.size > MAX_SEEN_TAGS) seenTags.removeAt(0)
        }
    }

    private fun SessionRecord.toSerializable() = SerializableRecord(
        remoteIdentityKey = remoteIdentityKey.b64(),
        current = current?.toSerializable(),
        previous = previous.map { it.toSerializable() },
        seenTags = seenTags.toList(),
        retiredRemoteKeys = retiredRemoteKeys.toList(),
    )

    private fun SerializableRecord.toRecord() = SessionRecord(
        remoteIdentityKey = remoteIdentityKey.fromB64(),
        current = current?.toState(),
        previous = previous.map { it.toState() }.toMutableList(),
        seenTags = seenTags.toMutableList(),
        retiredRemoteKeys = retiredRemoteKeys.toMutableList(),
    )

    private fun RatchetState.toSerializable() = SerializableRatchetState(
        dhPublicKey = dhKeyPair.publicKey.b64(),
        dhPrivateKey = dhKeyPair.privateKey.b64(),
        remoteDhPublicKey = remoteDhPublicKey?.b64(),
        rootKey = rootKey.b64(),
        sendingChainKey = sendingChainKey?.key?.b64(),
        sendingChainIndex = sendingChainKey?.index ?: 0,
        receivingChainKey = receivingChainKey?.key?.b64(),
        receivingChainIndex = receivingChainKey?.index ?: 0,
        previousSendingChainLength = previousSendingChainLength,
        skippedKeys = skippedMessageKeys.map { (k, v) ->
            SkippedKeyEntry(k.first, k.second, v.b64(), skippedKeyTimestamps[k] ?: System.currentTimeMillis())
        },
        previousRemoteKeys = previousRemoteKeys.toList(),
        pendingKemCiphertext = pendingKemCiphertext?.b64(),
        pqcEstablished = pqcEstablished,
        sessionTag = sessionTag,
        isInitiator = isInitiator,
        confirmed = confirmed,
    )

    private fun SerializableRatchetState.toState() = RatchetState(
        dhKeyPair = DhKeyPair(dhPublicKey.fromB64(), dhPrivateKey.fromB64()),
        remoteDhPublicKey = remoteDhPublicKey?.fromB64(),
        rootKey = rootKey.fromB64(),
        sendingChainKey = sendingChainKey?.let { ChainKey(it.fromB64(), sendingChainIndex) },
        receivingChainKey = receivingChainKey?.let { ChainKey(it.fromB64(), receivingChainIndex) },
        previousSendingChainLength = previousSendingChainLength,
        skippedMessageKeys = skippedKeys.associate { (it.publicKeyHex to it.messageNumber) to it.key.fromB64() }
            .toMutableMap(),
        skippedKeyTimestamps = skippedKeys.associate { (it.publicKeyHex to it.messageNumber) to it.timestamp }
            .toMutableMap(),
        previousRemoteKeys = previousRemoteKeys.toMutableList(),
        pendingKemCiphertext = pendingKemCiphertext?.fromB64(),
        pqcEstablished = pqcEstablished,
        sessionTag = sessionTag,
        isInitiator = isInitiator,
        confirmed = confirmed,
    )

    companion object {
        private const val SESSION_TAG_BYTES = 16
        private const val MAX_PREVIOUS_SESSIONS = 4
        private const val MAX_SEEN_TAGS = 128
        private const val MAX_RETIRED_REMOTE_KEYS = 256
    }
}

@Serializable
private data class SerializableRecord(
    val remoteIdentityKey: String,
    val current: SerializableRatchetState? = null,
    val previous: List<SerializableRatchetState> = emptyList(),
    val seenTags: List<String> = emptyList(),
    val retiredRemoteKeys: List<String> = emptyList(),
)

@Serializable
private data class SerializableRatchetState(
    val dhPublicKey: String,
    val dhPrivateKey: String,
    val remoteDhPublicKey: String?,
    val rootKey: String,
    val sendingChainKey: String?,
    val sendingChainIndex: Int,
    val receivingChainKey: String?,
    val receivingChainIndex: Int,
    val previousSendingChainLength: Int,
    val skippedKeys: List<SkippedKeyEntry>,
    val previousRemoteKeys: List<String> = emptyList(),
    val pendingKemCiphertext: String?,
    val pqcEstablished: Boolean,
    val sessionTag: String,
    val isInitiator: Boolean,
    val confirmed: Boolean,
)

@Serializable
private data class SkippedKeyEntry(
    val publicKeyHex: String,
    val messageNumber: Int,
    val key: String,
    val timestamp: Long = System.currentTimeMillis(),
)

private fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
private fun String.fromB64(): ByteArray = Base64.getDecoder().decode(this)
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
