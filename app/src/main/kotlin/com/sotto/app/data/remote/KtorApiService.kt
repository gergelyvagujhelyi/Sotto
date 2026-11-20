package com.sotto.app.data.remote

import com.sotto.app.BuildConfig
import com.sotto.app.crypto.KeyManager
import com.sotto.app.crypto.PqcProvider
import com.sotto.app.data.remote.dto.AckRequest
import com.sotto.app.data.remote.dto.BootstrapRequest
import com.sotto.app.data.remote.dto.BootstrapResponse
import com.sotto.app.data.remote.dto.CallSignalRequest
import com.sotto.app.data.remote.dto.CallSignalResponse
import com.sotto.app.data.remote.dto.IceServersResponse
import com.sotto.app.data.remote.dto.InviteDto
import com.sotto.app.data.remote.dto.KeyBundleDto
import com.sotto.app.data.remote.dto.KeyRotationRequest
import com.sotto.app.data.remote.dto.KeyRotationResponse
import com.sotto.app.data.remote.dto.PendingMessageDto
import com.sotto.app.data.remote.dto.PushTokenRequest
import com.sotto.app.data.remote.dto.RelayHealthDto
import com.sotto.app.data.remote.dto.ResolveShareCodeResponse
import com.sotto.app.data.remote.dto.SendMessageRequest
import com.sotto.app.data.remote.dto.SendMessageResponse
import io.ktor.client.HttpClient
import android.util.Base64
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Production API client that communicates with the Python FastAPI relay server.
 *
 * Authenticated requests include an Authorization header with a signed token:
 * ``Bearer <user_id>.<timestamp_ms>.<signature_b64>``
 *
 * The server verifies the Ed25519 signature against the public signing key
 * stored at bootstrap, proving the caller holds the private key.
 */
@OptIn(ExperimentalSerializationApi::class)
@Singleton
class KtorApiService @Inject constructor(
    private val keyManager: KeyManager,
    private val pqcProvider: PqcProvider,
    private val relayConfig: com.sotto.app.connection.RelayConfig,
) : ApiService, java.io.Closeable {

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                namingStrategy = JsonNamingStrategy.SnakeCase
            })
        }
        install(Logging) {
            // Use INFO (method + URL only) instead of HEADERS to avoid
            // leaking Authorization tokens to logcat in debug builds.
            level = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.NONE
        }
        // Runs for every request, so the relay chosen at setup applies at once.
        defaultRequest {
            relayConfig.baseUrl?.let { url(it) }
            contentType(ContentType.Application.Json)
        }
    }

    override fun close() {
        client.close()
    }

    private fun userId(): String =
        keyManager.getUserId() ?: throw IllegalStateException("No identity — bootstrap first")

    private fun authToken(): String = keyManager.generateAuthToken(pqcProvider)

    override suspend fun bootstrapIdentity(request: BootstrapRequest): BootstrapResponse {
        val response: HttpResponse = client.post("/v1/identity/bootstrap") {
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            // The relay's reason (e.g. "invite_used") is what setup shows the user.
            throw ApiHttpException(response.status.value, errorDetail(response) ?: "Bootstrap failed: ${response.status}")
        }
        return response.body()
    }

    override suspend fun createInvite(): InviteDto {
        val response: HttpResponse = client.post("/v1/invites") {
            header("Authorization", "Bearer ${authToken()}")
        }
        if (!response.status.isSuccess()) {
            throw ApiHttpException(response.status.value, errorDetail(response) ?: "Create invite failed: ${response.status}")
        }
        return response.body()
    }

    override suspend fun relayHealth(baseUrl: String): RelayHealthDto {
        val response: HttpResponse = client.get("$baseUrl/health")
        if (!response.status.isSuccess()) {
            throw ApiHttpException(response.status.value, "Relay check failed: ${response.status}")
        }
        return response.body()
    }

    /** The relay's `{"detail": "..."}` reason, when it gave one as a plain string. */
    private suspend fun errorDetail(response: HttpResponse): String? = runCatching {
        Json.parseToJsonElement(response.bodyAsText()).jsonObject["detail"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    override suspend fun fetchKeyBundle(userId: String): KeyBundleDto? {
        // No auth header — this endpoint is unauthenticated (IP rate-limited only)
        val response: HttpResponse = client.get("/v1/identity/$userId/keys")
        if (response.status.value == 404) return null
        check(response.status.isSuccess()) { "Fetch key bundle failed: ${response.status}" }
        return response.body()
    }

    override suspend fun resolveShareCode(shareCode: String): ResolveShareCodeResponse? {
        // No auth header — this endpoint is unauthenticated (IP rate-limited only)
        val response: HttpResponse = client.get("/v1/identity/resolve/$shareCode")
        if (response.status.value == 404) return null
        check(response.status.isSuccess()) { "Resolve share code failed: ${response.status}" }
        return response.body()
    }

    override suspend fun sendMessage(request: SendMessageRequest): SendMessageResponse {
        val response: HttpResponse = client.post("/v1/messages/send") {
            header("Authorization", "Bearer ${authToken()}")
            setBody(request)
        }
        // The per-minute send limit (not the per-recipient queue cap, also a 429).
        if (response.status.value == 429 && response.bodyAsText().contains("Rate limit exceeded")) {
            throw RateLimitedException()
        }
        check(response.status.isSuccess()) { "Send message failed: ${response.status}" }
        return response.body()
    }

    override suspend fun fetchPendingMessages(): List<PendingMessageDto> {
        val response: HttpResponse = client.get("/v1/messages/pending") {
            header("Authorization", "Bearer ${authToken()}")
        }
        check(response.status.isSuccess()) { "Fetch pending failed: ${response.status}" }
        return response.body()
    }

    override suspend fun acknowledgeMessages(request: AckRequest) {
        val response: HttpResponse = client.post("/v1/messages/ack") {
            header("Authorization", "Bearer ${authToken()}")
            setBody(request)
        }
        check(response.status.isSuccess()) { "ACK failed: ${response.status}" }
    }

    override suspend fun registerPushToken(request: PushTokenRequest) {
        val response: HttpResponse = client.post("/v1/push/register") {
            header("Authorization", "Bearer ${authToken()}")
            setBody(request)
        }
        check(response.status.isSuccess()) { "Register push token failed: ${response.status}" }
    }

    override suspend fun unregisterPushToken() {
        val response: HttpResponse = client.delete("/v1/push/register") {
            header("Authorization", "Bearer ${authToken()}")
        }
        check(response.status.isSuccess()) { "Unregister push token failed: ${response.status}" }
    }

    override suspend fun sendCallSignal(request: CallSignalRequest): CallSignalResponse {
        val response: HttpResponse = client.post("/v1/calls/signal") {
            header("Authorization", "Bearer ${authToken()}")
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            throw ApiHttpException(response.status.value, "Call signal failed: ${response.status}")
        }
        return response.body()
    }

    override suspend fun getIceServers(): IceServersResponse {
        val response: HttpResponse = client.get("/v1/calls/ice-servers") {
            header("Authorization", "Bearer ${authToken()}")
        }
        check(response.status.isSuccess()) { "Get ICE servers failed: ${response.status}" }
        return response.body()
    }

    override suspend fun rotateKeys(request: KeyRotationRequest): KeyRotationResponse {
        val response: HttpResponse = client.put("/v1/identity/me/keys") {
            header("Authorization", "Bearer ${authToken()}")
            setBody(request)
        }
        check(response.status.isSuccess()) { "Key rotation failed: ${response.status}" }
        return response.body()
    }

    override suspend fun sendReceipt(request: com.sotto.app.data.remote.dto.ReceiptRequest) {
        val response: HttpResponse = client.post("/v1/receipts") {
            header("Authorization", "Bearer ${authToken()}")
            setBody(request)
        }
        check(response.status.isSuccess()) { "Send receipt failed: ${response.status}" }
    }

    override suspend fun fetchPendingReceipts(): List<com.sotto.app.data.remote.dto.ReceiptDto> {
        val response: HttpResponse = client.get("/v1/receipts/pending") {
            header("Authorization", "Bearer ${authToken()}")
        }
        if (response.status.value == 404) return emptyList() // server without receipts
        check(response.status.isSuccess()) { "Fetch receipts failed: ${response.status}" }
        return response.body()
    }

    override suspend fun ackReceipts(request: com.sotto.app.data.remote.dto.ReceiptAckRequest) {
        val response: HttpResponse = client.post("/v1/receipts/ack") {
            header("Authorization", "Bearer ${authToken()}")
            setBody(request)
        }
        check(response.status.isSuccess()) { "Ack receipts failed: ${response.status}" }
    }

    override suspend fun fetchDeliveryReceipts(): List<com.sotto.app.data.remote.dto.DeliveryReceiptDto> {
        val response: HttpResponse = client.get("/v1/receipts/delivered") {
            header("Authorization", "Bearer ${authToken()}")
        }
        if (response.status.value == 404) return emptyList() // relay older than 0.5.10
        check(response.status.isSuccess()) { "Fetch delivery receipts failed: ${response.status}" }
        return response.body()
    }

    override suspend fun ackDeliveryReceipts(request: com.sotto.app.data.remote.dto.ReceiptAckRequest) {
        val response: HttpResponse = client.post("/v1/receipts/delivered/ack") {
            header("Authorization", "Bearer ${authToken()}")
            setBody(request)
        }
        check(response.status.isSuccess()) { "Ack delivery receipts failed: ${response.status}" }
    }

    override suspend fun deleteIdentity() {
        val response: HttpResponse = client.delete("/v1/identity/me") {
            header("Authorization", "Bearer ${authToken()}")
        }
        // 404 means the identity was already deleted (previous call succeeded
        // but local wipe failed) — treat as success so retryServerDeletion works.
        if (response.status.value == 404) return
        check(response.status.isSuccess()) { "Identity deletion failed: ${response.status}" }
    }
}

/** The relay refused the request because of its rate limit (HTTP 429); retry later. */
class RateLimitedException : java.io.IOException("Rate limited by the relay")
