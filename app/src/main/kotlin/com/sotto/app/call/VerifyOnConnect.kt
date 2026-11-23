package com.sotto.app.call

/**
 * "Verify by phone" from the first-message warning: the chat starts a call and asks for the
 * code check to open by itself once that call first connects (CallManager reads it then).
 * Process-wide and one-shot. Armed only when the call is really placed (after the microphone
 * permission), and it lapses after [TTL_MS], or when a call ends, so it can't open the check
 * in some unrelated call later.
 */
object VerifyOnConnect {
    private const val TTL_MS = 2 * 60_000L

    @Volatile
    private var armed: Pair<String, Long>? = null

    fun arm(peerId: String, nowMs: Long = System.currentTimeMillis()) {
        armed = peerId to nowMs
    }

    fun clear() {
        armed = null
    }

    /** True once, for a call to [peerId] that connects within [TTL_MS] of arming. */
    fun take(peerId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val (peer, at) = armed ?: return false
        armed = null
        return peer == peerId && nowMs - at <= TTL_MS
    }
}
