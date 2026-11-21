package com.sotto.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The wiping HMAC, HKDF and AES-GCM give exactly what the JCA versions gave (old sessions depend on it). */
class WipingCryptoTest {
    private val random = SecureRandom()
    private fun bytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun jcaHmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** The HKDF as it was up to 0.7.3, through javax.crypto.Mac. */
    private fun jcaHkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        val out = ByteArray(((length + 31) / 32) * 32)
        var t = ByteArray(0)
        for (i in 1..(length + 31) / 32) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t); mac.update(info); mac.update(i.toByte())
            t = mac.doFinal()
            t.copyInto(out, (i - 1) * 32)
        }
        return out.copyOfRange(0, length)
    }

    @Test
    fun `HMAC-SHA256 matches javax Mac for keys of every length and split input`() {
        for (keyLength in listOf(1, 16, 32, 63, 64, 65, 100, 200)) {
            repeat(10) {
                val key = bytes(keyLength)
                val a = bytes(random.nextInt(100))
                val b = bytes(random.nextInt(100))
                assertArrayEquals(jcaHmac(key, a + b), hmacSha256(key, a, b))
            }
        }
    }

    @Test
    fun `HMAC-SHA256 matches RFC 4231 test cases 1, 2 and 6`() {
        assertArrayEquals(
            hex("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"),
            hmacSha256(ByteArray(20) { 0x0b }, "Hi There".toByteArray()),
        )
        assertArrayEquals(
            hex("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"),
            hmacSha256("Jefe".toByteArray(), "what do ya want for nothing?".toByteArray()),
        )
        assertArrayEquals( // a 131-byte key: hashed first
            hex("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"),
            hmacSha256(ByteArray(131) { 0xaa.toByte() }, "Test Using Larger Than Block-Size Key - Hash Key First".toByteArray()),
        )
    }

    @Test
    fun `HKDF matches the old JCA version and RFC 5869 test case 1`() {
        repeat(50) {
            // Never an empty salt: the old JCA version refused one (SecretKeySpec), so none is ever used.
            val ikm = bytes(32); val salt = bytes(if (it % 5 == 0) 13 else 32); val info = bytes(it)
            val length = listOf(32, 64, 42, 96)[it % 4]
            assertArrayEquals(jcaHkdf(ikm, salt, info, length), hkdfSha256(ikm, salt, info, length))
        }
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            hkdfSha256(ByteArray(22) { 0x0b }, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42),
        )
    }

    @Test
    fun `AES-GCM matches the JCA version both ways`() {
        repeat(50) {
            val key = bytes(32); val nonce = bytes(12); val aad = bytes(it); val plain = bytes(it * 3)
            val jca = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                if (aad.isNotEmpty()) updateAAD(aad)
            }.doFinal(plain)
            assertArrayEquals(jca, aesGcmSeal(key, nonce, aad, plain))
            assertArrayEquals(plain, aesGcmOpen(key, nonce, aad, jca))
        }
    }

    @Test
    fun `AES-GCM refuses a tampered message or the wrong associated data`() {
        val key = bytes(32); val nonce = bytes(12)
        val sealed = aesGcmSeal(key, nonce, "header".toByteArray(), "hej".toByteArray())
        for ((aad, data) in listOf(
            "header".toByteArray() to sealed.copyOf().also { it[0] = (it[0] + 1).toByte() },
            "other".toByteArray() to sealed,
        )) {
            try {
                aesGcmOpen(key, nonce, aad, data)
                fail("must not authenticate")
            } catch (_: SecurityException) {
            }
        }
    }

    @Test
    fun `the caller's key is left intact`() {
        val key = bytes(32); val copy = key.copyOf()
        hmacSha256(key, bytes(10)); aesGcmSeal(key, bytes(12), ByteArray(0), bytes(10))
        assertArrayEquals(copy, key)
    }
}
