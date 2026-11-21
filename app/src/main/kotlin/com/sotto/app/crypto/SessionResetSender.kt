package com.sotto.app.crypto

import android.util.Base64
import com.sotto.app.data.remote.ApiService
import com.sotto.app.data.remote.dto.SendMessageRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends a signed control message (e.g. `session_reset`, `account_deleted`) to a peer.
 *
 * Used by both [SettingsRepositoryImpl] (wipe-all) and
 * [MessageRepositoryImpl] (decrypt-failure recovery) to avoid
 * duplicating the signing + send logic.
 */
@Singleton
class SessionResetSender @Inject constructor(
    private val keyManager: KeyManager,
    private val pqcProvider: PqcProvider,
    private val apiService: ApiService,
) {
    /**
     * Send a session_reset control message to [recipientId].
     * Returns silently if no local identity exists.
     */
    suspend fun send(
        recipientId: String,
        ctrl: String = CTRL_SESSION_RESET,
        /** Wait out the relay's per-minute send limit (bulk notices to all contacts). */
        waitForRateLimit: Boolean = false,
    ) {
        val senderId = keyManager.getUserId() ?: return
        // NOTE: The control type is visible in the nonce field (Base64-encoded).
        // The relay server operator can detect session_reset / account_deleted
        // events. This is a known trade-off: control messages are sent when
        // sessions are broken, so they cannot be encrypted with session keys.
        // Encrypting with the recipient's identity key would add complexity
        // without meaningful privacy gain (the server already sees sender/recipient).
        // ts + random n make every control message unique; the receiver only
        // accepts a ts newer than the last one it accepted from us (replay guard).
        val ts = System.currentTimeMillis()
        val n = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val controlPayload = """{"ctrl":"$ctrl","ts":$ts,"n":"$n"}"""
        val nonceBytes = controlPayload.toByteArray(Charsets.UTF_8)
        val bodyBytes = ByteArray(0)

        val sigPayload = buildMessageSigPayload(senderId, recipientId, nonceBytes, bodyBytes)
        val signature = keyManager.sign(sigPayload)
        // A signing failure throws rather than sending without ML-DSA: the receiver
        // drops a PQC account's control message without it, so it would be lost while
        // the caller thought it was sent (and wouldn't try again).
        val pqcSigB64 = keyManager.getMlDsaPrivateKey()?.let { mlDsaPrivKey ->
            try {
                Base64.encodeToString(pqcProvider.sign(sigPayload, mlDsaPrivKey), Base64.NO_WRAP)
            } finally {
                mlDsaPrivKey.fill(0)
            }
        } ?: ""

        val request = SendMessageRequest(
            recipientId = recipientId,
            encryptedBody = Base64.encodeToString(bodyBytes, Base64.NO_WRAP),
            nonce = Base64.encodeToString(nonceBytes, Base64.NO_WRAP),
            signature = Base64.encodeToString(signature, Base64.NO_WRAP),
            pqcSignature = pqcSigB64,
        )
        // Rotation and account deletion notify every contact in a burst; past the
        // relay's per-minute send limit the rest were silently dropped, so those
        // contacts never learned of it. Wait for the window to move on instead.
        var attempt = 0
        while (true) {
            try {
                apiService.sendMessage(request)
                return
            } catch (e: com.sotto.app.data.remote.RateLimitedException) {
                if (!waitForRateLimit || ++attempt >= RATE_LIMIT_ATTEMPTS) throw e
                kotlinx.coroutines.delay(RATE_LIMIT_BACKOFF_MS)
            }
        }
    }

    companion object {
        const val CTRL_SESSION_RESET = "session_reset"
        const val CTRL_ACCOUNT_DELETED = "account_deleted"
        private const val RATE_LIMIT_ATTEMPTS = 5
        private const val RATE_LIMIT_BACKOFF_MS = 15_000L
    }
}
