package com.sotto.app.data.remote

import com.sotto.app.data.remote.dto.AckRequest
import com.sotto.app.data.remote.dto.BootstrapRequest
import com.sotto.app.data.remote.dto.BootstrapResponse
import com.sotto.app.data.remote.dto.InviteDto
import com.sotto.app.data.remote.dto.KeyBundleDto
import com.sotto.app.data.remote.dto.PendingMessageDto
import com.sotto.app.data.remote.dto.PushTokenRequest
import com.sotto.app.data.remote.dto.RelayHealthDto
import com.sotto.app.data.remote.dto.ResolveShareCodeResponse
import com.sotto.app.data.remote.dto.CallSignalRequest
import com.sotto.app.data.remote.dto.CallSignalResponse
import com.sotto.app.data.remote.dto.IceServersResponse
import com.sotto.app.data.remote.dto.KeyRotationRequest
import com.sotto.app.data.remote.dto.KeyRotationResponse
import com.sotto.app.data.remote.dto.SendMessageRequest
import com.sotto.app.data.remote.dto.SendMessageResponse

/**
 * API contract for the Sotto relay server.
 *
 * The server is a minimal envelope relay — it never sees plaintext message content.
 * It stores only:
 * - userId → publicKeyBundle mapping (for key discovery)
 * - userId → Firebase installation ID (older apps: FCM token), for push wake-ups
 * - Pending encrypted envelopes (deleted after delivery acknowledgement)
 *
 * Endpoints:
 * POST   /v1/identity/bootstrap         — register a guest identity
 * GET    /v1/identity/{userId}/keys      — fetch a user's public key bundle (signed in, since 0.7.11)
 * GET    /v1/identity/resolve/{code}     — resolve a share code to a key bundle (signed in, since 0.7.11)
 * POST   /v1/messages/send               — submit an encrypted envelope for relay
 * GET    /v1/messages/pending             — fetch pending envelopes
 * POST   /v1/messages/ack                — acknowledge receipt of messages
 * POST   /v1/push/register               — register the push target (installation ID)
 * PUT    /v1/identity/me/keys            — rotate public keys
 */
interface ApiService {
    companion object {
        /** Messages per fetch page (the relay's `limit`): small enough for a poor line to finish. */
        const val PENDING_PAGE_SIZE = 20
    }

    suspend fun bootstrapIdentity(request: BootstrapRequest): BootstrapResponse

    /** A one-time invite for someone to join this relay (any member may create one). */
    suspend fun createInvite(): InviteDto

    /** Whether [baseUrl] is a Sotto relay, and whether it wants an invite. */
    suspend fun relayHealth(baseUrl: String): RelayHealthDto
    /**
     * [signWithStagedKeys]: sign in with the keys of an unconfirmed rotation, to learn after a
     * crash whether the relay took them (IdentityRepositoryImpl's recovery).
     */
    suspend fun fetchKeyBundle(userId: String, signWithStagedKeys: Boolean = false): KeyBundleDto?
    suspend fun resolveShareCode(shareCode: String): ResolveShareCodeResponse?
    suspend fun sendMessage(request: SendMessageRequest): SendMessageResponse
    suspend fun fetchPendingMessages(): List<PendingMessageDto>

    /** A page of [limit] instead of [PENDING_PAGE_SIZE] (to look past held messages). */
    suspend fun fetchPendingMessages(limit: Int): List<PendingMessageDto> = fetchPendingMessages()

    /** The relay's clock (its Date header, ms) when the last [fetchPendingMessages] was answered; null if unknown. */
    fun relayTimeOfLastFetch(): Long? = null
    suspend fun acknowledgeMessages(request: AckRequest)
    suspend fun registerPushToken(request: PushTokenRequest)
    suspend fun sendCallSignal(request: CallSignalRequest): CallSignalResponse
    suspend fun getIceServers(): IceServersResponse
    suspend fun rotateKeys(request: KeyRotationRequest): KeyRotationResponse
    suspend fun deleteIdentity()

    // Read receipts (best effort; a server without them yields nothing).
    suspend fun sendReceipt(request: com.sotto.app.data.remote.dto.ReceiptRequest) {}
    suspend fun fetchPendingReceipts(): List<com.sotto.app.data.remote.dto.ReceiptDto> = emptyList()
    suspend fun ackReceipts(request: com.sotto.app.data.remote.dto.ReceiptAckRequest) {}
    suspend fun fetchDeliveryReceipts(): List<com.sotto.app.data.remote.dto.DeliveryReceiptDto> = emptyList()
    suspend fun ackDeliveryReceipts(request: com.sotto.app.data.remote.dto.ReceiptAckRequest) {}

    // Encrypted attachments (ADR-29): the relay signs URLs for its object store.
    suspend fun reserveAttachment(request: com.sotto.app.data.remote.dto.AttachmentReserveRequest): com.sotto.app.data.remote.dto.AttachmentReserveResponse =
        throw UnsupportedOperationException("No attachments")
    suspend fun completeAttachment(id: String, etags: List<String> = emptyList()): Unit = throw UnsupportedOperationException("No attachments")
    /** Fresh part URLs to resume a multipart upload; null when the relay has no such upload (404). */
    suspend fun attachmentParts(id: String): com.sotto.app.data.remote.dto.AttachmentPartsResponse? =
        throw UnsupportedOperationException("No attachments")
    /** A download URL for an attachment sent to the user; null when it's gone (404). */
    suspend fun attachmentDownload(id: String): com.sotto.app.data.remote.dto.AttachmentDownloadResponse? =
        throw UnsupportedOperationException("No attachments")
    suspend fun deleteAttachment(id: String): Unit = throw UnsupportedOperationException("No attachments")
}
