package com.sotto.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class BootstrapRequest(
    val publicSigningKey: String,
    val publicIdentityKey: String,
    val pqcEncapsulationKey: String = "",
    val pqcSigningKey: String = "",
    val fcmToken: String? = null,
    /** A member's one-time invite; an invite-only relay wants one once it has accounts. */
    val inviteCode: String = "",
)

@Serializable
data class InviteDto(
    /** Like "K7QX-M2PA-9TRB". */
    val code: String,
    val expiresAt: String,
)

@Serializable
data class AttachmentReserveRequest(val recipientId: String, val size: Long)

@Serializable
data class AttachmentReserveResponse(val id: String, val uploadUrl: String, val expiresIn: Int = 0)

@Serializable
data class AttachmentDownloadResponse(val downloadUrl: String, val size: Long, val expiresIn: Int = 0)

@Serializable
data class RelayHealthDto(
    val status: String = "",
    val version: String = "",
    val service: String = "",
    /** A new account here needs an invite. */
    val inviteRequired: Boolean = false,
) {
    /**
     * It says it's a Sotto relay (or a ChatControll one, as relays called themselves from
     * 0.5.17 until 0.6.0), or, for relays before 0.5.17, looks like one.
     */
    val isSottoRelay: Boolean
        get() = service in RELAY_SERVICES || (status == "ok" && Regex("[0-9]+[.][0-9]+[.][0-9]+").matches(version))

    private companion object {
        val RELAY_SERVICES = setOf("sotto-relay", "chatcontroll-relay")
    }
}

@Serializable
data class BootstrapResponse(
    val userId: String,
    val shareCode: String,
)

@Serializable
data class KeyBundleDto(
    val userId: String,
    val publicSigningKey: String,
    val publicIdentityKey: String,
    val pqcEncapsulationKey: String,
    val pqcSigningKey: String = "",
    /** Hex fingerprint of the key set this bundle replaced (empty if never rotated). */
    val rotationPrevFingerprint: String = "",
    /** Ed25519 signature by the PREVIOUS signing key over the rotation payload (base64). */
    val rotationSignature: String = "",
    /** ML-DSA-65 signature by the PREVIOUS PQC signing key over the rotation payload (base64). */
    val rotationPqcSignature: String = "",
)

@Serializable
data class SendMessageRequest(
    val recipientId: String,
    val encryptedBody: String,
    val nonce: String,
    val ephemeralPublicKey: String = "",
    val signature: String = "",
    val pqcSignature: String = "",
    /** Our local message id; a resend reuses it so the relay stores the message once. */
    val clientMessageId: String = "",
)

@Serializable
data class SendMessageResponse(
    val messageId: String,
    val timestamp: Long,
)

@Serializable
data class PendingMessageDto(
    val messageId: String,
    val senderId: String,
    val encryptedBody: String,
    val nonce: String,
    val ephemeralPublicKey: String = "",
    val signature: String = "",
    val pqcSignature: String = "",
    val timestamp: Long,
)

@Serializable
data class AckRequest(
    val messageIds: List<String>,
)

@Serializable
data class PushTokenRequest(
    /** FCM registration token, sent by apps up to 0.5.4 (deprecated by Google). */
    val token: String? = null,
    /** Firebase installation ID, sent from 0.5.5. The relay takes exactly one of the two. */
    val fid: String? = null,
    val platform: String = "android",
)

@Serializable
data class ResolveShareCodeResponse(
    val userId: String,
    val publicSigningKey: String,
    val publicIdentityKey: String,
    val pqcEncapsulationKey: String,
    val pqcSigningKey: String = "",
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
)

@Serializable
data class CallSignalRequest(
    val recipientId: String,
    val signalType: String,
    val callId: String,
    val encryptedPayload: String,
    val signature: String = "",
    val pqcSignature: String = "",
    val kemCiphertext: String = "",
)

@Serializable
data class CallSignalResponse(
    val delivered: Boolean,
)

@Serializable
data class CallSignalDto(
    val senderId: String,
    val signalType: String,
    val callId: String,
    val encryptedPayload: String,
    val signature: String = "",
    val pqcSignature: String = "",
    val kemCiphertext: String = "",
)

@Serializable
data class IceServerDto(
    val urls: String,
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class IceServersResponse(
    val iceServers: List<IceServerDto>,
)

@Serializable
data class KeyRotationRequest(
    val publicSigningKey: String,
    val publicIdentityKey: String,
    val pqcEncapsulationKey: String? = null,
    val pqcSigningKey: String? = null,
    val newKeyProof: String,
    val pqcKeyProof: String = "",
    /** Endorsement of the new keys by the CURRENT (old) keys; see KeyFingerprint.rotationPayload. */
    val rotationPrevFingerprint: String = "",
    val rotationSignature: String = "",
    val rotationPqcSignature: String = "",
)

@Serializable
data class KeyRotationResponse(
    val status: String,
    val shareCode: String,
)

/** "I have read these messages" (relay message ids), signed by the reader. */
@Serializable
data class ReceiptRequest(
    val recipientId: String,
    val messageIds: List<String>,
    val signature: String,
)

/** Messages of ours that reached [senderId]'s phone: the relay's word (unsigned). */
@Serializable
data class DeliveryReceiptDto(
    val id: Long,
    val senderId: String,
    val messageIds: List<String>,
    val timestamp: Long = 0,
)

@Serializable
data class ReceiptDto(
    val id: Long,
    val senderId: String,
    val messageIds: List<String>,
    val signature: String,
    val timestamp: Long = 0,
)

@Serializable
data class ReceiptAckRequest(val ids: List<Long>)

