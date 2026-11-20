package com.sotto.app.call

import com.sotto.app.domain.model.CallPath

// Who may see whose IP addresses in a call, and how a call's path is told apart. Pure, so it
// can be unit tested on the JVM.
//
// A direct call needs both phones' consent: each user has "Direct calls" on (the default;
// either can turn it off), and each phone trusts the other. One-sided consent would gain
// nothing (the audio still goes through the relay of the side that didn't agree) while
// showing the consenting side's addresses.

/**
 * May this contact see our IP addresses in a direct call? Only a contact from before this
 * call that the user added (verified) or accepted (approved conversation): never someone
 * calling out of the blue.
 */
internal fun isTrustedPeer(existedBeforeCall: Boolean, verified: Boolean, conversationApproved: Boolean): Boolean =
    existedBeforeCall && (verified || conversationApproved)

/** The caller proposes a direct call when its own setting is on and it trusts the callee. */
internal fun offerDirect(settingOn: Boolean, trusted: Boolean): Boolean = settingOn && trusted

/** The callee agrees only with its own setting on, trusting the caller, and when proposed. */
internal fun answerDirect(settingOn: Boolean, trusted: Boolean, offered: Boolean): Boolean =
    settingOn && trusted && offered

internal enum class Transport {
    /** Relay candidates only: the peer learns only the relay's address. */
    RELAY,
    /** All candidates: the peer learns this phone's addresses. */
    ALL,
    /** No way to call without showing addresses nobody agreed to show. */
    NO_RELAY,
}

/**
 * How a call's connection starts. [direct]: for the caller, that it proposes a direct call;
 * for the callee, that it agreed to one.
 * - A callee that agreed shows its addresses at once.
 * - Everyone else starts relay-only. The caller shows its addresses only once the answer says
 *   the callee agreed; without a relay it then starts with no candidates at all.
 * - Without a relay and without that, a call can't connect: release builds refuse it, debug
 *   builds (development without coturn) go direct.
 */
internal fun initialTransport(hasTurn: Boolean, isCaller: Boolean, direct: Boolean, debug: Boolean): Transport = when {
    !isCaller && direct -> Transport.ALL
    hasTurn -> Transport.RELAY
    debug -> Transport.ALL
    isCaller && direct -> Transport.RELAY
    else -> Transport.NO_RELAY
}

/** The candidate's address: the fifth field of "candidate:<foundation> <component> <protocol> <priority> <address> …". */
internal fun candidateAddress(sdp: String): String? {
    val line = if (sdp.contains("candidate:")) sdp.substringAfter("candidate:") else sdp
    return line.trim().split(' ').getOrNull(4)?.ifEmpty { null }
}

/**
 * Does the selected candidate pair go through the relay? Yes if either side is a relay
 * candidate, or the remote address is the relay itself (a peer-reflexive candidate learned
 * from checks that came through it). Null when a candidate can't be read.
 */
internal fun classifyCallPath(localSdp: String, remoteSdp: String, turnHosts: Set<String>): CallPath? {
    val local = candidateType(localSdp) ?: return null
    val remote = candidateType(remoteSdp) ?: return null
    return if (local == "relay" || remote == "relay" || candidateAddress(remoteSdp) in turnHosts) {
        CallPath.RELAYED
    } else {
        CallPath.DIRECT
    }
}
