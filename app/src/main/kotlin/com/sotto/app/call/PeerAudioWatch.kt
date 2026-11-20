package com.sotto.app.call

/** After this long with no audio from the other phone, the call says it can't reach them. */
internal const val PEER_UNREACHABLE_MS = 10_000L

/** After this long with no audio from the other phone, the call ends. */
internal const val PEER_GONE_MS = 60_000L

/** What a call does about the other phone, from how long nothing has arrived from it. */
internal enum class PeerVerdict { PRESENT, UNREACHABLE, GONE }

internal fun peerVerdict(silentForMs: Long): PeerVerdict = when {
    silentForMs >= PEER_GONE_MS -> PeerVerdict.GONE
    silentForMs >= PEER_UNREACHABLE_MS -> PeerVerdict.UNREACHABLE
    else -> PeerVerdict.PRESENT
}

/**
 * When audio last arrived from the other phone, from readings of the received-audio counter.
 *
 * The other phone sends audio for as long as it's in the call, silence included: muting sends
 * silence, and Sotto's calls don't use discontinuous transmission. So when the counter stops,
 * the other phone has stopped too, whatever ICE says.
 */
internal class PeerAudioWatch(startedAt: Long) {
    private var lastBytes = -1L
    private var lastArrivalAt = startedAt

    /** Record [receivedBytes] read at [now]; returns how long nothing has arrived. */
    fun silentFor(receivedBytes: Long, now: Long): Long {
        if (receivedBytes > lastBytes) {
            lastBytes = receivedBytes
            lastArrivalAt = now
        }
        return now - lastArrivalAt
    }
}
