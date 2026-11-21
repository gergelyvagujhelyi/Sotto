package com.sotto.app.crypto

import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519

/*
 * X25519 and Ed25519 computed straight on our key bytes. The JCA path (KeyFactory with a
 * PKCS8EncodedKeySpec, then a key object) copies the private key into objects we can't
 * wipe; these copy nothing. The results are exactly the JCA path's (RawKeyCryptoTest).
 * Keys are kept as BouncyCastle encodes them. A private key is PKCS#8: a fixed 16-byte
 * header and the 32-byte key, in version 2 (what BouncyCastle writes) followed by the public
 * key. A public key is X.509: a 12-byte header and the 32-byte key. A key in any other form
 * gets null here, and its caller takes the JCA path.
 */

private val X25519_PKCS8_V1 = byteArrayOfHex("302e020100300506032b656e04220420")
private val X25519_PKCS8_V2 = byteArrayOfHex("3051020101300506032b656e04220420")
private val X25519_X509_HEADER = byteArrayOfHex("302a300506032b656e032100")
private val ED25519_PKCS8_V1 = byteArrayOfHex("302e020100300506032b657004220420")
private val ED25519_PKCS8_V2 = byteArrayOfHex("3051020101300506032b657004220420")
/** In version 2, after the private key: the public key, as [1] IMPLICIT BIT STRING (RFC 5958). */
private val PKCS8_V2_PUBLIC_KEY_TAG = byteArrayOfHex("812100")
private const val PKCS8_HEADER_SIZE = 16

/** X25519 shared secret, or null for keys not in our encoding. Throws for an all-zero result. */
internal fun rawX25519Agree(privateKey: ByteArray, remotePublicKey: ByteArray): ByteArray? {
    if (!privateKey.isPkcs8(X25519_PKCS8_V1, X25519_PKCS8_V2) || !remotePublicKey.isX509(X25519_X509_HEADER)) return null
    val secret = ByteArray(X25519.POINT_SIZE)
    val nonZero = X25519.calculateAgreement(
        privateKey, PKCS8_HEADER_SIZE, remotePublicKey, X25519_X509_HEADER.size, secret, 0,
    )
    // As the JCA path: a low-order public key gives all zeros, which is no secret.
    check(nonZero) { "X25519 agreement value is all zeros" }
    return secret
}

/** Ed25519 signature over [data], or null for a key not in our encoding. */
internal fun rawEd25519Sign(privateKey: ByteArray, data: ByteArray): ByteArray? {
    if (!privateKey.isPkcs8(ED25519_PKCS8_V1, ED25519_PKCS8_V2)) return null
    val signature = ByteArray(Ed25519.SIGNATURE_SIZE)
    Ed25519.sign(privateKey, PKCS8_HEADER_SIZE, data, 0, data.size, signature, 0)
    return signature
}

private fun ByteArray.startsWith(prefix: ByteArray, at: Int = 0): Boolean =
    size >= at + prefix.size && prefix.indices.all { this[at + it] == prefix[it] }

private fun ByteArray.isPkcs8(v1: ByteArray, v2: ByteArray): Boolean = when (size) {
    PKCS8_HEADER_SIZE + 32 -> startsWith(v1)
    PKCS8_HEADER_SIZE + 32 + PKCS8_V2_PUBLIC_KEY_TAG.size + 32 ->
        startsWith(v2) && startsWith(PKCS8_V2_PUBLIC_KEY_TAG, at = PKCS8_HEADER_SIZE + 32)
    else -> false
}

private fun ByteArray.isX509(header: ByteArray): Boolean = size == header.size + 32 && startsWith(header)

private fun byteArrayOfHex(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

/**
 * A new X25519 key pair as (X.509 public key, PKCS#8 v2 private key), exactly as BouncyCastle
 * encodes them. Generated into our arrays: BouncyCastle's KeyPairGenerator leaves a key
 * object holding the private key, which can't be wiped (measured on the Note 9).
 */
internal fun rawX25519KeyPair(random: java.security.SecureRandom): Pair<ByteArray, ByteArray> {
    val private = ByteArray(X25519.SCALAR_SIZE)
    val public = ByteArray(X25519.POINT_SIZE)
    try {
        X25519.generatePrivateKey(random, private)
        X25519.generatePublicKey(private, 0, public, 0)
        return (X25519_X509_HEADER + public) to (X25519_PKCS8_V2 + private + PKCS8_V2_PUBLIC_KEY_TAG + public)
    } finally {
        private.fill(0)
    }
}

