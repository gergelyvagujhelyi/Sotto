package com.sotto.app.call

import com.sotto.app.data.remote.dto.IceServerDto

// Pure decisions about how a call connects, kept free of Android and WebRTC classes so
// they can be unit tested on the JVM.

/** The host of a stun:/turn: URL: "turn:203.0.113.7:3478?transport=udp" -> "203.0.113.7". */
internal fun iceUrlHost(url: String): String? {
    val rest = url.substringAfter(':', "").substringBefore('?')
    if (rest.isEmpty()) return null
    if (rest.startsWith("[")) return rest.substringAfter('[').substringBefore(']').ifEmpty { null }
    return rest.substringBeforeLast(':', rest).ifEmpty { null }
}

internal fun isTurnUrl(url: String): Boolean = url.startsWith("turn:") || url.startsWith("turns:")

/** Hosts of the TURN servers: our own relay. */
internal fun turnHosts(servers: List<IceServerDto>): Set<String> =
    servers.filter { isTurnUrl(it.urls) }.mapNotNull { iceUrlHost(it.urls) }.toSet()

/**
 * The ICE servers a call may use. Relay-only: TURN only, no STUN at all, as a STUN server
 * learns the phone's IP address and when it calls. Direct: TURN plus STUN, but only STUN on
 * our own relay (a TURN host), never a third party's, whatever the server list says.
 */
internal fun selectIceServers(servers: List<IceServerDto>, relayOnly: Boolean): List<IceServerDto> {
    val relays = turnHosts(servers)
    return servers.filter { server ->
        when {
            isTurnUrl(server.urls) -> true
            relayOnly -> false
            server.urls.startsWith("stun:") -> iceUrlHost(server.urls) in relays
            else -> false
        }
    }
}

internal enum class NetworkEvent {
    NONE,
    /** Another network than before (Wi-Fi to mobile, say). */
    SWITCHED,
    /** No network at all any more (airplane mode, out of coverage). */
    LOST,
    /** The same network back after it was lost. */
    RESTORED,
}

/** Tells switches, losses and returns of the default network apart from the first network seen. */
internal class NetworkChangeTracker {
    private var last: Long? = null
    private var up = false

    fun update(handle: Long?): NetworkEvent {
        if (handle == null) {
            val event = if (up) NetworkEvent.LOST else NetworkEvent.NONE
            up = false
            return event
        }
        val previous = last
        val wasUp = up
        last = handle
        up = true
        return when {
            previous == null -> NetworkEvent.NONE
            previous != handle -> NetworkEvent.SWITCHED
            !wasUp -> NetworkEvent.RESTORED
            else -> NetworkEvent.NONE
        }
    }
}

/** "candidate:1 1 udp 2122260223 192.0.2.4 51000 typ host …" -> "host". */
internal fun candidateType(sdp: String): String? = Regex("""\btyp (\w+)""").find(sdp)?.groupValues?.get(1)

/** "IPv4", "IPv6" or "mDNS" (a .local name) for a candidate line, for the log; never the address itself. */
internal fun candidateFamily(sdp: String): String? {
    val address = candidateAddress(sdp) ?: return null
    return when {
        address.endsWith(".local") -> "mDNS"
        address.contains(':') -> "IPv6"
        address.count { it == '.' } == 3 -> "IPv4"
        else -> null
    }
}

/** One candidate pair ICE formed, from the connection's stats. */
internal data class CandidatePairState(
    val localType: String,
    val remoteType: String,
    /** Only to tell the relay's own address apart; never logged. */
    val remoteAddress: String?,
    /** "frozen", "waiting", "in-progress", "succeeded" or "failed". */
    val state: String,
    /** ICE connectivity checks (STUN binding requests) sent, and answers to them received. */
    val requestsSent: Long = 0,
    val responsesReceived: Long = 0,
    /** The other phone's checks that arrived, and our answers to them. */
    val requestsReceived: Long = 0,
    val responsesSent: Long = 0,
    /** Media and data packets sent and received over the pair. */
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
)

/** The pair goes through the relay: it isn't a direct path between the phones. */
private fun CandidatePairState.isRelayed(turnHosts: Set<String>): Boolean =
    localType == "relay" || remoteType == "relay" || remoteAddress in turnHosts

/**
 * For the log, what each direct pair's checks and packets did, as in "host->host in-progress:
 * checks 12 sent, 3 answered; 9 received, 9 answered; packets 230 out, 180 in". It tells which
 * side stopped hearing the other when a direct path is lost. Counts only, never addresses.
 */
internal fun describeDirectPairs(pairs: List<CandidatePairState>, turnHosts: Set<String>): String =
    pairs.filterNot { it.isRelayed(turnHosts) }
        .joinToString("; ") {
            "${it.localType}->${it.remoteType} ${it.state}: checks ${it.requestsSent} sent, " +
                "${it.responsesReceived} answered; ${it.requestsReceived} received, ${it.responsesSent} answered; " +
                "packets ${it.packetsSent} out, ${it.packetsReceived} in"
        }
        .ifEmpty { "no direct pairs" }

/**
 * For the log, how the direct and the relayed pairs fared, as in "direct pairs: 4 failed;
 * relay pairs: 1 failed, 1 succeeded". Counts only, never addresses.
 */
internal fun summarizeCandidatePairs(pairs: List<CandidatePairState>, turnHosts: Set<String>): String {
    val (relayed, direct) = pairs.partition { it.isRelayed(turnHosts) }
    fun counts(list: List<CandidatePairState>): String =
        list.groupingBy { it.state }.eachCount().toSortedMap().entries
            .joinToString(", ") { "${it.value} ${it.key}" }
            .ifEmpty { "none" }
    return "direct pairs: ${counts(direct)}; relay pairs: ${counts(relayed)}"
}
