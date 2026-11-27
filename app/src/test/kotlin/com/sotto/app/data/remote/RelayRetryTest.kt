package com.sotto.app.data.remote

import com.sotto.app.connection.RelayConfig
import com.sotto.app.crypto.KeyManager
import com.sotto.app.data.remote.dto.BootstrapRequest
import com.sotto.app.data.remote.dto.SendMessageRequest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/** KtorApiService against a local relay stand-in that answers from a script. */
class RelayRetryTest {
    /** What the stand-in does with each request in turn: an HTTP status, or [DROP] to hang up. */
    private val script = ConcurrentLinkedQueue<Int>()
    private val auth = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var server: ServerSocket
    private lateinit var api: KtorApiService
    private var tokens = 0

    @Before
    fun setUp() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use { s ->
                    val input = s.getInputStream().bufferedReader()
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        if (line.startsWith("Authorization:", true)) auth += line.substringAfter(':').trim()
                    }
                    repeat(length) { input.read() }
                    val status = script.poll() ?: 200
                    if (status == DROP) return@use // hang up without an answer
                    val body = when (status) {
                        200 -> """{"message_id":"r1","timestamp":5,"user_id":"me","share_code":"x"}"""
                        429 -> """{"detail":"Rate limit exceeded"}"""
                        else -> """{"detail":"down"}"""
                    }
                    s.getOutputStream().write(
                        ("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n" +
                            "Connection: close\r\n\r\n$body").toByteArray()
                    )
                }
            }
        }
        val keyManager = mockk<KeyManager>(relaxed = true) {
            every { generateAuthToken(any()) } answers { "token-${++tokens}" }
        }
        val relay = mockk<RelayConfig> { every { baseUrl } returns "http://127.0.0.1:${server.localPort}" }
        api = KtorApiService(keyManager, mockk(relaxed = true), relay, mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        api.close()
        server.close()
    }

    private val message = SendMessageRequest(recipientId = "peer", encryptedBody = "AA==", nonce = "AA==", clientMessageId = "m1")

    @Test
    fun `a message the relay answers 503 to is sent again and goes through`() = runBlocking {
        script += 503
        assertEquals(5L, api.sendMessage(message).timestamp)
        assertEquals(2, auth.size)
        assertNotEquals("each attempt has a fresh token", auth[0], auth[1])
    }

    @Test
    fun `a message whose connection drops is sent again and goes through`() = runBlocking {
        script += DROP
        assertEquals(5L, api.sendMessage(message).timestamp)
        assertEquals(2, auth.size)
    }

    @Test
    fun `a relay that stays down fails the send after a few tries`() = runBlocking {
        repeat(10) { script += 503 }
        val result = runCatching { api.sendMessage(message) }
        assertTrue(result.isFailure)
        assertEquals(3, auth.size)
    }

    @Test
    fun `fetching pending messages is retried`() = runBlocking {
        script += 502
        script += 200
        runCatching { api.fetchPendingMessages() } // body isn't a list here; only the attempts count
        assertEquals(2, auth.size)
    }

    @Test
    fun `the rate limit is not retried`() = runBlocking {
        script += 429
        val result = runCatching { api.sendMessage(message) }
        assertTrue(result.exceptionOrNull() is RateLimitedException)
        assertEquals(1, auth.size)
    }

    @Test
    fun `sign-up is never sent twice - it uses up an invite`() = runBlocking {
        script += 503
        val result = runCatching {
            api.bootstrapIdentity(BootstrapRequest(publicSigningKey = "a", publicIdentityKey = "b", pqcEncapsulationKey = "", inviteCode = "i"))
        }
        assertTrue(result.isFailure)
        // Sign-up sends no token: count the requests the stand-in answered instead.
        assertEquals(0, script.size)
        assertEquals(0, auth.size)
    }

    private companion object {
        const val DROP = -1
    }
}
