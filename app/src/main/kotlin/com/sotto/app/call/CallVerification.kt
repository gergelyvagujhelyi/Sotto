package com.sotto.app.call

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom

/** One step of a call verification, sent end-to-end encrypted as a `call_verify` call signal. */
@Serializable
data class CallVerifyPayload(val kind: String, val value: String) {
    companion object {
        const val COMMIT = "commit"
        const val REVEAL = "reveal"
    }
}

/**
 * Verifying a contact by call: both phones show a six-digit code and the two people read it to
 * each other (ADR-31). It's weaker than meeting, so a contact checked this way is only "verified
 * by call", never "verified in person".
 *
 * The code covers both phones' key fingerprints as each phone sees them. If someone in the
 * middle (the relay, say) gave each phone other keys, each phone sees another pair of
 * fingerprints and the codes differ, even if every message of this exchange is passed through.
 *
 * Six digits are only about 20 bits, so the random values that go into the code are committed
 * before they're revealed (as ZRTP does): a phone sends the hash of its value, and reveals the
 * value only once it has the other phone's hash. Someone in the middle has to commit to each
 * side before it learns that side's value, so it can't search for keys or values that make the
 * two codes match: it gets one guess, about one in a million.
 *
 * Not thread-safe: CallManager calls it under its own lock.
 */
class CallVerification(
    val callId: String,
    private val isCaller: Boolean,
    private val myFingerprint: ByteArray,
    private val peerFingerprint: ByteArray,
    random: SecureRandom = SecureRandom(),
) {
    enum class Phase {
        /** Nothing yet. */
        IDLE,
        /** This phone asked; waiting for the other phone to ask too. */
        WAITING,
        /** The other phone asked; this one hasn't yet. */
        ASKED,
        /** Both asked; the values are on their way. */
        EXCHANGING,
        /** The code is ready to read out. */
        CODE,
        /** The other phone revealed a value that doesn't match what it committed to. */
        BROKEN,
    }

    private val myNonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
    private var started = false
    private var revealed = false
    private var peerCommit: ByteArray? = null
    private var peerNonce: ByteArray? = null

    var phase: Phase = Phase.IDLE
        private set

    /** The six digits, as "123 456", once [phase] is [Phase.CODE]. */
    var code: String? = null
        private set

    /** The user asked to compare a code: what to send. */
    fun start(): List<CallVerifyPayload> {
        if (started || phase == Phase.BROKEN) return emptyList()
        started = true
        val out = mutableListOf(CallVerifyPayload(CallVerifyPayload.COMMIT, hex(sha256(myNonce))))
        out += revealIfReady()
        updatePhase()
        return out
    }

    /** A step from the other phone: what to send back. */
    fun onPeer(payload: CallVerifyPayload): List<CallVerifyPayload> {
        if (phase == Phase.BROKEN) return emptyList()
        val value = unhex(payload.value) ?: return emptyList()
        val out = mutableListOf<CallVerifyPayload>()
        when (payload.kind) {
            CallVerifyPayload.COMMIT -> {
                // Only the first commitment counts: a second one could be chosen after our reveal.
                if (peerCommit == null && value.size == HASH_BYTES) {
                    peerCommit = value
                    out += revealIfReady()
                }
            }
            CallVerifyPayload.REVEAL -> {
                val commit = peerCommit
                if (peerNonce == null && commit != null && value.size == NONCE_BYTES) {
                    if (!MessageDigest.isEqual(sha256(value), commit)) {
                        phase = Phase.BROKEN
                        return emptyList()
                    }
                    peerNonce = value
                }
            }
        }
        updatePhase()
        return out
    }

    /** Ours goes out only once we've committed and hold theirs, never earlier. */
    private fun revealIfReady(): List<CallVerifyPayload> {
        if (!started || revealed || peerCommit == null) return emptyList()
        revealed = true
        return listOf(CallVerifyPayload(CallVerifyPayload.REVEAL, hex(myNonce)))
    }

    private fun updatePhase() {
        val theirs = peerNonce
        phase = when {
            started && revealed && theirs != null -> {
                code = computeCode(theirs)
                Phase.CODE
            }
            started && peerCommit != null -> Phase.EXCHANGING
            started -> Phase.WAITING
            peerCommit != null -> Phase.ASKED
            else -> Phase.IDLE
        }
    }

    private fun computeCode(theirNonce: ByteArray): String {
        // The caller's parts first, so both phones hash the same thing.
        val (callerFp, calleeFp) = if (isCaller) myFingerprint to peerFingerprint else peerFingerprint to myFingerprint
        val (callerNonce, calleeNonce) = if (isCaller) myNonce to theirNonce else theirNonce to myNonce
        val digest = sha256(
            DOMAIN + lp(callId.toByteArray(Charsets.UTF_8)) + lp(callerFp) + lp(calleeFp) + lp(callerNonce) + lp(calleeNonce),
        )
        val number = ((digest[0].toLong() and 0xFF) shl 24 or ((digest[1].toLong() and 0xFF) shl 16) or
            ((digest[2].toLong() and 0xFF) shl 8) or (digest[3].toLong() and 0xFF)) % 1_000_000
        val digits = number.toString().padStart(6, '0')
        return digits.substring(0, 3) + " " + digits.substring(3)
    }

    /** The fingerprint this phone used for the other person, to mark them verified with. */
    val peerFingerprintHex: String get() = hex(peerFingerprint)

    companion object {
        private val DOMAIN = "Sotto-call-verify-v1".toByteArray(Charsets.UTF_8)
        private const val NONCE_BYTES = 32
        private const val HASH_BYTES = 32

        private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        private fun lp(data: ByteArray): ByteArray {
            val n = data.size
            return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + data
        }

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        private fun unhex(text: String): ByteArray? {
            if (text.length % 2 != 0 || text.length > 128 || !text.all { it in '0'..'9' || it in 'a'..'f' }) return null
            return ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
    }
}
