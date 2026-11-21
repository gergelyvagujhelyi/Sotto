package com.sotto.app.crypto

/**
 * HKDF-SHA256 extract-and-expand (RFC 5869). Every intermediate is wiped ([hmacSha256]
 * leaves no copy of its key).
 */
internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val hashLen = 32
    // Extract (RFC 5869: an empty salt means hashLen zero bytes, the same HMAC key)
    val prk = hmacSha256(salt, ikm)
    val n = (length + hashLen - 1) / hashLen
    val output = ByteArray(n * hashLen)
    var t = ByteArray(0)
    try {
        // Expand
        for (i in 1..n) {
            val prev = t
            t = hmacSha256(prk, prev, info, byteArrayOf(i.toByte()))
            prev.fill(0)
            t.copyInto(output, (i - 1) * hashLen)
        }
        return output.copyOfRange(0, length)
    } finally {
        t.fill(0)
        prk.fill(0)
        output.fill(0)
    }
}
