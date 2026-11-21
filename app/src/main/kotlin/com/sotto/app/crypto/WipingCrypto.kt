package com.sotto.app.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.modes.GCMModeCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/*
 * HMAC-SHA256 and AES-256-GCM that leave no copy of their key behind. The JCA versions take
 * the key as a SecretKeySpec (a copy that can't be wiped), and Android's AES-GCM also keeps
 * the plaintext it encrypts in a buffer it doesn't wipe. These work on our byte arrays,
 * through buffers that are wiped before returning, with byte-for-byte the same results
 * (WipingCryptoTest). What stays is AES's internal key schedule, until garbage collection.
 */

private const val SHA256_BLOCK = 64
private const val SHA256_SIZE = 32
private const val GCM_TAG_BITS = 128

/** HMAC-SHA256 (RFC 2104) of the [parts] concatenated, under [key]. */
internal fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
    val digest = SHA256Digest()
    val block = ByteArray(SHA256_BLOCK)
    val inner = ByteArray(SHA256_SIZE)
    try {
        if (key.size > SHA256_BLOCK) {
            digest.update(key, 0, key.size)
            digest.doFinal(block, 0) // a long key is replaced by its hash, zero-padded
        } else {
            key.copyInto(block)
        }
        for (i in block.indices) block[i] = (block[i].toInt() xor 0x36).toByte()
        digest.update(block, 0, SHA256_BLOCK)
        for (part in parts) digest.update(part, 0, part.size)
        digest.doFinal(inner, 0)
        for (i in block.indices) block[i] = (block[i].toInt() xor 0x36 xor 0x5c).toByte()
        digest.update(block, 0, SHA256_BLOCK)
        digest.update(inner, 0, SHA256_SIZE)
        return ByteArray(SHA256_SIZE).also { digest.doFinal(it, 0) }
    } finally {
        block.fill(0)
        inner.fill(0)
        digest.reset() // clears its message buffers
    }
}

/** AES-GCM encryption: ciphertext followed by the 16-byte tag. */
internal fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
    val cipher = gcm(true, key, nonce, aad)
    val out = ByteArray(cipher.getOutputSize(plaintext.size))
    try {
        val n = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, n)
        return out
    } finally {
        cipher.reset()
    }
}

/** AES-GCM decryption of ciphertext + tag; throws if it doesn't authenticate. */
internal fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
    val cipher = gcm(false, key, nonce, aad)
    val out = ByteArray(cipher.getOutputSize(sealed.size))
    try {
        val n = cipher.processBytes(sealed, 0, sealed.size, out, 0)
        cipher.doFinal(out, n)
        return out
    } catch (e: Exception) {
        out.fill(0) // GCM hands out plaintext before it checks the tag
        throw SecurityException("AES-GCM authentication failed", e)
    } finally {
        cipher.reset()
    }
}

private fun gcm(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray): GCMModeCipher {
    val keyParameter = KeyParameter(key)
    val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
    try {
        cipher.init(encrypt, AEADParameters(keyParameter, GCM_TAG_BITS, nonce, aad))
    } finally {
        keyParameter.key.fill(0) // KeyParameter keeps its own copy; getKey() is that copy
    }
    return cipher
}
