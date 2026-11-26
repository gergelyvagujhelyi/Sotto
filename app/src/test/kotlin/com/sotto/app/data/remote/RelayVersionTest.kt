package com.sotto.app.data.remote

import com.sotto.app.connection.RelayConfig
import com.sotto.app.crypto.KeyManager
import com.sotto.app.update.AppUpdates
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** The relay's version reaches AppUpdates from every answer: HTTP headers and socket messages. */
class RelayVersionTest {
    private lateinit var server: ServerSocket
    private lateinit var api: KtorApiService
    private val appUpdates = mockk<AppUpdates>(relaxed = true)
    @Volatile private var headers = "Sotto-Relay-Version: 0.7.3\r\nSotto-App-Ready: true\r\n"

    @Before
    fun setUp() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use { s ->
                    val input = s.getInputStream().bufferedReader()
                    while (true) { if ((input.readLine() ?: break).isEmpty()) break }
                    val body = """{"status":"ok","version":"0.7.3","service":"sotto-relay"}"""
                    s.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n$headers" +
                            "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray()
                    )
                }
            }
        }
        val relay = mockk<RelayConfig> { every { baseUrl } returns "http://127.0.0.1:${server.localPort}" }
        api = KtorApiService(mockk<KeyManager>(relaxed = true), mockk(relaxed = true), relay, appUpdates)
    }

    @After
    fun tearDown() {
        api.close()
        server.close()
    }

    @Test
    fun `an answer from our relay reports its version`() = runBlocking {
        runCatching { api.relayHealth("http://127.0.0.1:${server.localPort}") } // only the headers matter here
        verify { appUpdates.report("0.7.3", true) }
    }

    @Test
    fun `an answer saying the app isn't out reports that`() = runBlocking {
        headers = "Sotto-Relay-Version: 0.7.3\r\nSotto-App-Ready: false\r\n"
        runCatching { api.relayHealth("http://127.0.0.1:${server.localPort}") } // only the headers matter here
        verify { appUpdates.report("0.7.3", false) }
    }

    @Test
    fun `an answer from another relay - one being checked during setup - is ignored`() = runBlocking {
        runCatching { api.relayHealth("http://localhost:${server.localPort}") } // only the headers matter here // same server, not our relay's address
        verify(exactly = 0) { appUpdates.report(any(), any()) }
    }

    @Test
    fun `an older relay that says nothing reports nothing`() = runBlocking {
        headers = ""
        runCatching { api.relayHealth("http://127.0.0.1:${server.localPort}") } // only the headers matter here
        verify(exactly = 0) { appUpdates.report(any(), any()) }
    }

    private fun msg(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `the socket's sign-in and its app_release message say it too`() {
        assertEquals("0.7.3" to false, relayReport(msg("""{"type":"auth_ok","relay_version":"0.7.3","app_ready":false}""")))
        assertEquals("0.7.3" to true, relayReport(msg("""{"type":"auth_ok","relay_version":"0.7.3","app_ready":true}""")))
        assertEquals("0.7.3" to true, relayReport(msg("""{"type":"app_release","version":"0.7.3"}""")))
        assertNull(relayReport(msg("""{"type":"auth_ok"}"""))) // a relay before 0.7.3
        assertNull(relayReport(msg("""{"type":"new_message"}""")))
    }
}
