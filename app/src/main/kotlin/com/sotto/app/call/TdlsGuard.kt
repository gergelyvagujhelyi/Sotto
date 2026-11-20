package com.sotto.app.call

import java.net.InetAddress

/**
 * Keeps Wi-Fi's direct links between devices (TDLS) off between this phone and the other one,
 * while a call goes straight between them on the same Wi-Fi.
 *
 * A phone may set up TDLS with the other phone a few seconds into a direct call on the same
 * network. If the other phone's Wi-Fi mishandles it, the local path dies both ways and the call
 * falls back to the relay. That happened on 2026-11-20 between the owner's phone and a LineageOS
 * Note 9, whose Wi-Fi logged "No matching peer found for TPK M3". With TDLS off for that address,
 * the audio keeps going through the access point, which works. It's turned back on when the call
 * ends.
 *
 * [setTdls] is WifiManager.setTdlsEnabled, a call into the system: run this off the main thread.
 */
internal class TdlsGuard(private val setTdls: (InetAddress, Boolean) -> Unit) {
    private val turnedOff = mutableSetOf<String>()

    /**
     * Turn TDLS off for [address] if it belongs to a device on the local network. True when it was
     * turned off now; false if it isn't a local address, was already off, or the system refused.
     */
    @Synchronized
    fun keepOff(address: String): Boolean {
        if (address in turnedOff || !isLocalNetworkAddress(address)) return false
        val inet = runCatching { InetAddress.getByName(address) }.getOrNull() ?: return false
        if (runCatching { setTdls(inet, false) }.isFailure) return false
        turnedOff += address
        return true
    }

    /** At the end of a call: TDLS back on for every address it was turned off for. */
    @Synchronized
    fun restoreAll() {
        for (address in turnedOff) {
            runCatching { setTdls(InetAddress.getByName(address), true) }
        }
        turnedOff.clear()
    }
}

/**
 * A private IPv4 address (10/8, 172.16/12, 192.168/16): a device on the same local network. Only
 * these can be TDLS peers here; a literal is checked, never looked up.
 */
internal fun isLocalNetworkAddress(address: String): Boolean {
    val parts = address.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { part -> part.toIntOrNull()?.takeIf { it in 0..255 && part.length in 1..3 } ?: return false }
    return when {
        octets[0] == 10 -> true
        octets[0] == 172 && octets[1] in 16..31 -> true
        octets[0] == 192 && octets[1] == 168 -> true
        else -> false
    }
}
