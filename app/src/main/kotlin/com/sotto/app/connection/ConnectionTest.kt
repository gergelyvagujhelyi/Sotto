package com.sotto.app.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Settings > Diagnostics > Test the connection. A line that loses packets shows as failed or very
 * uneven tries, which the phone's Wi-Fi status never shows; comparing with other servers tells a
 * relay problem from the phone's own: the relay (TCP, and full requests to its /health), the
 * relay's name lookup, Google over IPv4 and over IPv6 (which one this network carries), and
 * GitHub (IPv4 only). Google and GitHub only see a connection opened and closed. Runs only when
 * the user taps it, and stops when the screen goes (cancellation is checked between tries).
 */
object ConnectionTest {
    /** Connections to one place: [label] (a string resource), where, how many tries. */
    data class Target(val label: Int, val host: String, val port: Int, val tries: Int, val ipv4Only: Boolean = false)

    const val RELAY_TCP_TRIES = 20
    const val HTTP_TRIES = 5
    private const val OTHER_TRIES = 5
    private const val TCP_TIMEOUT_MS = 2_000
    private const val HTTP_TIMEOUT_MS = 5_000

    /** The other places tried, for comparison. */
    val OTHERS = listOf(
        Target(com.sotto.app.R.string.diag_test_google_v4, "8.8.8.8", 443, OTHER_TRIES),
        Target(com.sotto.app.R.string.diag_test_google_v6, "2001:4860:4860::8888", 443, OTHER_TRIES),
        Target(com.sotto.app.R.string.diag_test_github, "github.com", 443, OTHER_TRIES, ipv4Only = true),
    )

    /** How many tries in all, for the progress. */
    val TOTAL_TRIES = 1 + RELAY_TCP_TRIES + HTTP_TRIES + OTHERS.sumOf { it.tries }

    /** One kind of try: how many of [total] worked, and how long the ones that worked took. */
    data class Outcome(val total: Int, val millis: List<Long>) {
        val ok get() = millis.size
        /** The middle time; for an even count the mean of the two middle ones. */
        val median: Long? get() = millis.sorted().let {
            when {
                it.isEmpty() -> null
                it.size % 2 == 1 -> it[it.size / 2]
                else -> (it[it.size / 2 - 1] + it[it.size / 2]) / 2
            }
        }
        val slowest: Long? get() = millis.maxOrNull()
    }

    /** [lookupMillis]: how long the relay's name took to look up, null if it failed. */
    data class Result(
        val lookupMillis: Long?,
        val relayTcp: Outcome,
        val relayHttp: Outcome,
        val others: List<Pair<Target, Outcome>>,
    )

    /** Runs the test against [baseUrl] (the relay's), telling [onProgress] tries done of all. */
    suspend fun run(baseUrl: String, onProgress: suspend (done: Int, total: Int) -> Unit): Result = withContext(Dispatchers.IO) {
        var done = 0
        suspend fun step() {
            onProgress(++done, TOTAL_TRIES)
            coroutineContext.ensureActive()
        }
        val url = URL(baseUrl.trimEnd('/'))
        val port = if (url.port != -1) url.port else url.defaultPort

        // Looked up once: the TCP tries below then measure only connecting, not the lookup (the
        // HTTPS tries go by name, as Sotto does; Android keeps the answer for them).
        // Every address, in Android's order: a try connects to the first that answers, as Sotto's
        // own connections do (an IPv6 address on a network with broken IPv6 isn't a failure).
        var relayAddresses: List<InetAddress> = emptyList()
        val lookup = timed { relayAddresses = InetAddress.getAllByName(url.host).toList() }
        step()
        val relayTcp = connections(relayAddresses, port, RELAY_TCP_TRIES, ::step)
        val http = ArrayList<Long>()
        repeat(HTTP_TRIES) {
            timed {
                val connection = URL("${url.protocol}://${url.authority}/health").openConnection() as HttpURLConnection
                connection.connectTimeout = HTTP_TIMEOUT_MS
                connection.readTimeout = HTTP_TIMEOUT_MS
                connection.useCaches = false
                // A new connection each time, so each try connects and shakes hands again (Android
                // may resume the TLS session, a shorter handshake), which is what fails on a lossy
                // line; a reused connection would hide it.
                connection.setRequestProperty("Connection", "close")
                try {
                    check(connection.responseCode == 200)
                    connection.inputStream.use { it.readBytes() }
                } finally {
                    connection.disconnect()
                }
            }?.let { http += it }
            step()
        }
        val others = OTHERS.map { target ->
            // GitHub over IPv4 only (its label says so); Google's are addresses already.
            val addresses = try {
                InetAddress.getAllByName(target.host).toList().let { all ->
                    if (target.ipv4Only) all.filterIsInstance<java.net.Inet4Address>() else all
                }
            } catch (_: Exception) {
                emptyList()
            }
            target to connections(addresses, target.port, target.tries, ::step)
        }
        Result(lookup, relayTcp, Outcome(HTTP_TRIES, http), others)
    }

    /** [tries] connections, each to the first of [addresses] that answers (none without any). */
    private suspend fun connections(addresses: List<InetAddress>, port: Int, tries: Int, step: suspend () -> Unit): Outcome {
        val times = ArrayList<Long>()
        repeat(tries) {
            if (addresses.isNotEmpty()) {
                timed {
                    var connected = false
                    for (address in addresses) {
                        connected = try {
                            Socket().use { it.connect(InetSocketAddress(address, port), TCP_TIMEOUT_MS) }
                            true
                        } catch (_: Exception) {
                            false
                        }
                        if (connected) break
                    }
                    check(connected)
                }?.let { times += it }
            }
            step()
        }
        return Outcome(tries, times)
    }

    /** How long [block] took in ms, or null if it threw. */
    private inline fun timed(block: () -> Unit): Long? {
        val start = System.nanoTime()
        return try {
            block()
            (System.nanoTime() - start) / 1_000_000
        } catch (_: Exception) {
            null
        }
    }
}
