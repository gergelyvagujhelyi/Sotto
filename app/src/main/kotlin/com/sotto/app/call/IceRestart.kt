package com.sotto.app.call

import com.sotto.app.data.remote.dto.IceServerDto
import kotlinx.serialization.Serializable

// ICE restarts: when a call's connection breaks for good (both phones moved, candidates lost
// to a dead socket, the relay restarted, its TURN credentials expired), the phones gather new
// candidates under new ICE credentials and exchange them with call_ice_restart signals.
// Pure, so it can be unit tested on the JVM.

/**
 * Bit in [SdpPayload.caps]: this side handles call_ice_restart. Peers without it (0.5.7 and
 * older) never get restart signals.
 */
internal const val CAPS_ICE_RESTART = 1

internal fun supportsIceRestart(caps: Int): Boolean = caps and CAPS_ICE_RESTART != 0

/**
 * The encrypted call_ice_restart payload: [kind] is [OFFER] (from the caller), [ANSWER] (from
 * the callee) or [REQUEST] (the callee asks the caller to restart). [gen] numbers the call's
 * restarts.
 */
@Serializable
internal data class IceRestartPayload(val kind: String, val gen: Int, val sdp: String = "") {
    companion object {
        const val OFFER = "offer"
        const val ANSWER = "answer"
        const val REQUEST = "request"
    }
}

/**
 * Applies the peer's ICE candidates in the right generation. Each restart starts a generation
 * with new ICE credentials, and its candidates can arrive before its description: newer ones
 * wait for it, older ones are dropped. The first exchange is generation 0, which is also what
 * older apps' candidates (without "gen") count as.
 */
internal class IceCandidateGate(private val maxQueued: Int = 100) {
    /** The generation of the applied remote description; -1 before the first. */
    @Volatile
    var appliedGen: Int = -1
        private set
    private val queued = ArrayList<IceCandidateDto>()

    /** True to apply [candidate] now; otherwise it waits for its generation or is dropped. */
    @Synchronized
    fun offer(candidate: IceCandidateDto): Boolean {
        if (appliedGen >= 0 && candidate.gen == appliedGen) return true
        if (candidate.gen < appliedGen) return false
        if (queued.size < maxQueued) queued.add(candidate)
        return false
    }

    /** The remote description of [gen] is applied: its waiting candidates, to apply now. */
    @Synchronized
    fun onRemoteApplied(gen: Int): List<IceCandidateDto> {
        if (gen < appliedGen) return emptyList()
        appliedGen = gen
        val ready = queued.filter { it.gen == gen }
        queued.removeAll { it.gen <= gen }
        return ready
    }
}

/**
 * One call's ICE restarts. Only the caller restarts, so the two phones never offer at once;
 * the callee can ask it to. Generations only grow, which also turns away replayed restart
 * signals. Times are passed in, so this is testable without a clock.
 */
internal class RestartNegotiator(private val isCaller: Boolean) {
    /** Caller: the last generation offered. Callee: the last one applied. */
    private var gen = 0
    private var pending = false
    private var lastStartAt: Long? = null
    private var nextRetransmitAt = Long.MAX_VALUE
    private var retransmits = 0

    val currentGen: Int @Synchronized get() = gen

    /** Caller: an offer is out and its answer hasn't come. */
    val isPending: Boolean @Synchronized get() = pending

    /** Caller: the unanswered offer will still be sent again. */
    val isRetransmitting: Boolean @Synchronized get() = pending && retransmits < MAX_RETRANSMITS

    /** Caller: start a restart, unless one started less than [MIN_INTERVAL_MS] ago. Returns its generation. */
    @Synchronized
    fun beginRestart(now: Long): Int? {
        check(isCaller) { "Only the caller restarts" }
        lastStartAt?.let { if (now - it < MIN_INTERVAL_MS) return null }
        gen += 1
        pending = true
        lastStartAt = now
        retransmits = 0
        nextRetransmitAt = now + backoff(0)
        return gen
    }

    /** Caller: an answer arrived. True if it answers the pending offer. */
    @Synchronized
    fun onAnswer(answerGen: Int): Boolean {
        if (!isCaller || !pending || answerGen != gen) return false
        pending = false
        nextRetransmitAt = Long.MAX_VALUE
        return true
    }

    /**
     * Caller: time to send the unanswered offer again (it may have gone into a dead socket),
     * with growing gaps, at most [MAX_RETRANSMITS] times.
     */
    @Synchronized
    fun retransmitDue(now: Long): Boolean {
        if (!pending || retransmits >= MAX_RETRANSMITS || now < nextRetransmitAt) return false
        retransmits += 1
        nextRetransmitAt = now + backoff(retransmits)
        return true
    }

    /** Callee: what to do with an offer of [offerGen]. */
    @Synchronized
    fun onOffer(offerGen: Int): OfferAction {
        check(!isCaller) { "Only the callee answers restarts" }
        return when {
            offerGen > gen -> OfferAction.APPLY
            offerGen == gen && gen > 0 -> OfferAction.RESEND_ANSWER
            else -> OfferAction.IGNORE
        }
    }

    /** Callee: the offer of [offerGen] is applied and answered. */
    @Synchronized
    fun offerApplied(offerGen: Int) {
        if (offerGen > gen) gen = offerGen
    }

    enum class OfferAction {
        /** A new restart: apply it and answer. */
        APPLY,
        /** The current restart again (our answer got lost): send the answer again. */
        RESEND_ANSWER,
        /** An older one: ignore it. */
        IGNORE,
    }

    companion object {
        const val MIN_INTERVAL_MS = 1_000L
        const val MAX_RETRANSMITS = 10
        private val BACKOFF_MS = longArrayOf(1_500, 3_000, 5_000, 10_000)

        fun backoff(attempt: Int): Long = BACKOFF_MS[attempt.coerceIn(0, BACKOFF_MS.size - 1)]
    }
}

/**
 * TURN usernames are "<expiry, unix seconds>:<user id>": the earliest expiry among the TURN
 * servers, null when there is none to read.
 */
internal fun turnCredentialExpiry(servers: List<IceServerDto>): Long? =
    servers.filter { isTurnUrl(it.urls) }
        .mapNotNull { it.username?.substringBefore(':')?.toLongOrNull() }
        .minOrNull()

/**
 * How long before the TURN credentials expire a call renews them: 10 minutes for the
 * relay's usual hour, proportionally sooner for shorter lifetimes (a test lifetime of a
 * minute renews 10 s before), so a short lifetime never makes the call renew in a loop.
 */
internal fun turnRenewLeadSec(lifetimeSec: Long): Long = minOf(10 * 60L, lifetimeSec / 6)

/**
 * A restart fetches new TURN credentials when fewer than this remain: 15 minutes for an
 * hour, a quarter of a shorter lifetime. Always more than [turnRenewLeadSec], so the
 * restart made to renew them does renew them.
 */
internal fun turnRenewMarginSec(lifetimeSec: Long): Long = minOf(15 * 60L, lifetimeSec / 4)

/** True when fewer than [marginSec] remain on the TURN credentials (false when there are none). */
internal fun turnCredentialExpiresSoon(servers: List<IceServerDto>, nowSec: Long, marginSec: Long): Boolean {
    val expiry = turnCredentialExpiry(servers) ?: return false
    return expiry - nowSec < marginSec
}
