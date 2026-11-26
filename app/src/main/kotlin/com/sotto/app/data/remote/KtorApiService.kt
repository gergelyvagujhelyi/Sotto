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
import com.sotto.app.update.AppUpdates
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.parameter
import android.util.Base64
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.api.createClientPlugin
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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
    private val appUpdates: com.sotto.app.update.AppUpdates,
    private val reachability: com.sotto.app.connection.RelayReachability,
) : ApiService, java.io.Closeable {

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        // Per request where a call needs longer than the engine's 10 s (sendMessage).
        install(io.ktor.client.plugins.HttpTimeout)
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                namingStrategy = JsonNamingStrategy.SnakeCase
            })
        }
        // A blip (the relay restarting, a dropped mobile connection) shouldn't fail a
        // send: requests that are safe to repeat are tried up to twice more.
        install(HttpRequestRetry) {
            retryIf(MAX_RETRIES) { request, response ->
                response.status.value in RETRY_STATUSES && safeToRepeat(request.method, request.url.encodedPath)
            }
            // Not after a timeout: the relay may be slow, and three waits would hold a send for 30 s.
            retryOnExceptionIf(MAX_RETRIES) { request, cause ->
                cause is java.io.IOException && cause !is java.net.SocketTimeoutException &&
                    safeToRepeat(request.method, request.url.build().encodedPath)
            }
            exponentialDelay(base = 2.0, baseDelayMs = 500, maxDelayMs = 2_000, randomizationMs = 250)
            // A retry needs a new token: the relay refuses one it has already seen.
            modifyRequest { request ->
                if (request.headers.contains(HttpHeaders.Authorization)) {
                    val staged = request.attributes.getOrNull(STAGED_TOKEN) == true
                    request.headers[HttpHeaders.Authorization] = "Bearer ${keyManager.generateAuthToken(pqcProvider, staged)}"
                }
            }
        }
        // Every answer from our relay names its version and whether that app is out.
        // Only our relay's: a relay checked during setup isn't the one we use.
        install(createClientPlugin("RelayVersion") {
            onResponse { response ->
                val version = response.headers[AppUpdates.RELAY_VERSION_HEADER] ?: return@onResponse
                val relay = relayConfig.baseUrl?.trimEnd('/') ?: return@onResponse
                if (!response.call.request.url.toString().startsWith("$relay/")) return@onResponse
                if (response.status.isSuccess()) reachability.answered()
                appUpdates.report(version, response.headers[AppUpdates.APP_READY_HEADER] == "true")
            }
        })
        install(Logging) {
            // Use INFO (method + URL only) instead of HEADERS to avoid
            // leaking Authorization tokens to logcat in debug builds.
            level = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.NONE
        }
        // Runs for every request, so the relay chosen at setup applies at once.
        defaultRequest {
            relayConfig.baseUrl?.let { url(it) }
            contentType(ContentType.Application.Json)
            // The relay keeps each account's latest app version and logs when it changes,
            // so its owner can see an update spreading (server/app/auth.py).
            header(AppUpdates.APP_VERSION_HEADER, BuildConfig.VERSION_NAME)
        }
    }

    override fun close() {
        client.close()
    }

    private companion object {
        /** Marks a request signed with staged keys, so a retry signs it the same way. */
        val STAGED_TOKEN = io.ktor.util.AttributeKey<Boolean>("SottoStagedToken")
        const val MAX_RETRIES = 2
        /** Relay errors that pass: a crash, or a restart behind the proxy. Not 507 (queue full). */
        val RETRY_STATUSES = setOf(500, 502, 503, 504)
        /**
         * Requests that do no harm when the relay gets them twice: reads, acks, the push
         * token, and messages (the relay keeps one copy per client message id, and a
         * control message sent twice repeats its timestamp, which the receiver refuses).
         * Not sign-up (it uses up an invite), invites, key rotation, deleting the
         * account, receipts or call signals.
         */
        /** How long a message send or fetch may go without progress before it counts as failed. */
        const val SEND_SOCKET_TIMEOUT_MS = 30_000L

        fun safeToRepeat(method: HttpMethod, path: String): Boolean =
            method == HttpMethod.Get || REPEATABLE_PATHS.any { path.endsWith(it) }

        val REPEATABLE_PATHS = listOf(
            "/v1/messages/send",
            "/v1/messages/ack",
            "/v1/receipts/ack",
            "/v1/receipts/delivered/ack",
            "/v1/push/register",
        )
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

    override suspend fun fetchKeyBundle(userId: String, signWithStagedKeys: Boolean): KeyBundleDto? {
        // Signed in (since 0.7.11), so only members can look keys up; the relay requires it
        // once KEY_LOOKUP_AUTH is on.
        val response: HttpResponse = client.get("/v1/identity/$userId/keys") {
            signInIfMember(staged = signWithStagedKeys)
        }
        if (response.status.value == 404) return null
        if (!response.status.isSuccess()) throw ApiHttpException(response.status.value, "Fetch key bundle failed: ${response.status}")
        return response.body()
    }

    override suspend fun resolveShareCode(shareCode: String): ResolveShareCodeResponse? {
        val response: HttpResponse = client.get("/v1/identity/resolve/$shareCode") { signInIfMember() }
        if (response.status.value == 404) return null
        if (!response.status.isSuccess()) throw ApiHttpException(response.status.value, "Resolve share code failed: ${response.status}")
        return response.body()
    }

    /** Signs a key lookup in, once this phone has an account (before it, there's nobody to be). */
    private fun io.ktor.client.request.HttpRequestBuilder.signInIfMember(staged: Boolean = false) {
        if (keyManager.getUserId() == null) return
        if (staged) attributes.put(STAGED_TOKEN, true)
        header(HttpHeaders.Authorization, "Bearer ${keyManager.generateAuthToken(pqcProvider, staged)}")
    }

    override suspend fun sendMessage(request: SendMessageRequest): SendMessageResponse {
        val response: HttpResponse = client.post("/v1/messages/send") {
            header("Authorization", "Bearer ${authToken()}")
            setBody(request)
            // A send on a poor connection can stall for seconds and still arrive: giving up at
            // 10 s marked such messages "not delivered" while the other side had them already.
            timeout { socketTimeoutMillis = SEND_SOCKET_TIMEOUT_MS }
        }
        // The per-minute send limit (not the per-recipient queue cap, also a 429).
        if (response.status.value == 429 && response.bodyAsText().contains("Rate limit exceeded")) {
            throw RateLimitedException()
        }
        check(response.status.isSuccess()) { "Send message failed: ${response.status}" }
        return response.body()
    }

    @Volatile private var lastFetchRelayTime: Long? = null

    override fun relayTimeOfLastFetch(): Long? = lastFetchRelayTime

    override suspend fun fetchPendingMessages(): List<PendingMessageDto> {
        val response: HttpResponse = client.get("/v1/messages/pending") {
            header("Authorization", "Bearer ${authToken()}")
            // Small pages: each message carries kilobytes of signatures, and a phone that was
            // away pulled hundreds of KB at once, which a poor line couldn't finish in time.
            parameter("limit", ApiService.PENDING_PAGE_SIZE)
            timeout { socketTimeoutMillis = SEND_SOCKET_TIMEOUT_MS }
        }
        check(response.status.isSuccess()) { "Fetch pending failed: ${response.status}" }
        // The relay's clock, to tell how long the messages waited there (LateDelivery).
        lastFetchRelayTime = response.headers[HttpHeaders.Date]?.let { date ->
            runCatching {
                java.time.ZonedDateTime.parse(date, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull()
        }
        return response.body()
    }

    override suspend fun acknowledgeMessages(request: AckRequest) {
        val response: HttpResponse = client.post("/v1/messages/ack") {
            header("Authorization", "Bearer ${authToken()}")
            setBody(request)
        }
        check(response.status.isSuccess()) { "ACK failed: ${response.status}" }
    }

    override suspend fun reserveAttachment(
        request: com.sotto.app.data.remote.dto.AttachmentReserveRequest,
    ): com.sotto.app.data.remote.dto.AttachmentReserveResponse {
        val response: HttpResponse = client.post("/v1/attachments") {
            header("Authorization", "Bearer ${authToken()}")
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            throw ApiHttpException(response.status.value, errorDetail(response) ?: "Attachment refused: ${response.status}")
        }
        return response.body()
    }

    override suspend fun completeAttachment(id: String, etags: List<String>) {
        val response: HttpResponse = client.post("/v1/attachments/$id/complete") {
            header("Authorization", "Bearer ${authToken()}")
            setBody(com.sotto.app.data.remote.dto.AttachmentCompleteRequest(etags))
        }
        if (!response.status.isSuccess()) {
            throw ApiHttpException(response.status.value, errorDetail(response) ?: "Attachment not confirmed: ${response.status}")
        }
    }

    override suspend fun attachmentParts(id: String): com.sotto.app.data.remote.dto.AttachmentPartsResponse? {
        val response: HttpResponse = client.post("/v1/attachments/$id/parts") {
            header("Authorization", "Bearer ${authToken()}")
        }
        if (response.status.value == 404) return null
        if (!response.status.isSuccess()) {
            throw ApiHttpException(response.status.value, errorDetail(response) ?: "Parts unavailable: ${response.status}")
        }
        return response.body()
    }

    override suspend fun attachmentDownload(id: String): com.sotto.app.data.remote.dto.AttachmentDownloadResponse? {
        val response: HttpResponse = client.get("/v1/attachments/$id") {
            header("Authorization", "Bearer ${authToken()}")
        }
        if (response.status.value == 404) return null
        if (!response.status.isSuccess()) {
            throw ApiHttpException(response.status.value, errorDetail(response) ?: "Attachment unavailable: ${response.status}")
        }
        return response.body()
    }

    override suspend fun deleteAttachment(id: String) {
        val response: HttpResponse = client.delete("/v1/attachments/$id") {
            header("Authorization", "Bearer ${authToken()}")
        }
        if (!response.status.isSuccess() && response.status.value != 404) {
            throw ApiHttpException(response.status.value, "Attachment not deleted: ${response.status}")
        }
    }

    override suspend fun registerPushToken(request: PushTokenRequest) {
        val response: HttpResponse = client.post("/v1/push/register") {
            header("Authorization", "Bearer ${authToken()}")
            setBody(request)
        }
        check(response.status.isSuccess()) { "Register push token failed: ${response.status}" }
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
