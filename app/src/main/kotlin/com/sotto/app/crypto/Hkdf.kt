package com.sotto.app.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 extract-and-expand (RFC 5869).
 */
internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")

    // Extract
    mac.init(SecretKeySpec(salt, "HmacSHA256"))
    val prk = mac.doFinal(ikm)

    // Expand
    val hashLen = 32
    val n = (length + hashLen - 1) / hashLen
    val output = ByteArray(n * hashLen)
    var t = ByteArray(0)
    for (i in 1..n) {
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(t)
        mac.update(info)
        mac.update(i.toByte())
        val prev = t
        t = mac.doFinal()
        if (prev.isNotEmpty()) prev.fill(0)
        System.arraycopy(t, 0, output, (i - 1) * hashLen, hashLen)
    }
    t.fill(0)
    prk.fill(0)
    val result = output.copyOfRange(0, length)
    output.fill(0)
    return result
}
