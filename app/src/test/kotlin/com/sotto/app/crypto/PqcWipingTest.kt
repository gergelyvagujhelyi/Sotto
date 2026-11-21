package com.sotto.app.crypto

import org.bouncycastle.crypto.params.ParametersWithRandom
import org.bouncycastle.jcajce.SecretKeyWithEncapsulation
import org.bouncycastle.jcajce.spec.KEMExtractSpec
import org.bouncycastle.jcajce.spec.KEMGenerateSpec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSASigner
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyGenerator

/**
 * ML-DSA and ML-KEM through BouncyCastle's low-level classes give what the JCA path gave
 * (sessions and the relay depend on it), and the wipe leaves nothing usable behind.
 */
class PqcWipingTest {
    private lateinit var pqc: BouncyCastlePqcProvider

    @Before
    fun setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)?.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
        pqc = BouncyCastlePqcProvider()
    }

    // The JCA paths as they were up to 0.7.3.
    private fun jcaVerify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        val sig = Signature.getInstance("ML-DSA", "BC")
        sig.initVerify(KeyFactory.getInstance("ML-DSA", "BC").generatePublic(X509EncodedKeySpec(publicKey)))
        sig.update(data)
        return sig.verify(signature)
    }

    private fun jcaSign(data: ByteArray, privateKey: ByteArray): ByteArray {
        val sig = Signature.getInstance("ML-DSA", "BC")
        sig.initSign(KeyFactory.getInstance("ML-DSA", "BC").generatePrivate(PKCS8EncodedKeySpec(privateKey)))
        sig.update(data)
        return sig.sign()
    }

    private fun jcaDecapsulate(ciphertext: ByteArray, privateKey: ByteArray): ByteArray {
        val key = KeyFactory.getInstance("ML-KEM", "BC").generatePrivate(PKCS8EncodedKeySpec(privateKey))
        val gen = KeyGenerator.getInstance("ML-KEM", "BC")
        gen.init(KEMExtractSpec(key, ciphertext, "AES"))
        return (gen.generateKey() as SecretKeyWithEncapsulation).encoded
    }

    private fun jcaEncapsulate(publicKey: ByteArray): Pair<ByteArray, ByteArray> {
        val key = KeyFactory.getInstance("ML-KEM", "BC").generatePublic(X509EncodedKeySpec(publicKey))
        val gen = KeyGenerator.getInstance("ML-KEM", "BC")
        gen.init(KEMGenerateSpec(key, "AES"), SecureRandom())
        val k = gen.generateKey() as SecretKeyWithEncapsulation
        return k.encapsulation to k.encoded
    }

    @Test
    fun `ML-DSA signatures from the low-level path verify as before, and the other way round`() {
        repeat(10) { i ->
            val keys = pqc.generateSigningKeyPair()
            val data = ByteArray(i * 37) { (it + i).toByte() }
            val signature = pqc.sign(data, keys.privateKey)
            assertTrue(jcaVerify(data, signature, keys.publicKey))
            assertTrue(pqc.verify(data, jcaSign(data, keys.privateKey), keys.publicKey))
            assertFalse(jcaVerify(data + 1, signature, keys.publicKey))
        }
    }

    @Test
    fun `ML-KEM decapsulation gives exactly the old shared secret, both ways`() {
        repeat(10) {
            val keys = pqc.generateKemKeyPair()
            val (oldCiphertext, oldSecret) = jcaEncapsulate(keys.encapsulationKey)
            assertArrayEquals(oldSecret, pqc.decapsulate(oldCiphertext, keys.decapsulationKey))
            val new = pqc.encapsulate(keys.encapsulationKey)
            assertArrayEquals(new.sharedSecret, jcaDecapsulate(new.ciphertext, keys.decapsulationKey))
            assertArrayEquals(new.sharedSecret, pqc.decapsulate(new.ciphertext, keys.decapsulationKey))
        }
    }

    @Test
    fun `the caller's stored key is left intact`() {
        val dsa = pqc.generateSigningKeyPair()
        val kem = pqc.generateKemKeyPair()
        val dsaCopy = dsa.privateKey.copyOf(); val kemCopy = kem.decapsulationKey.copyOf()
        pqc.sign(byteArrayOf(1), dsa.privateKey)
        pqc.decapsulate(pqc.encapsulate(kem.encapsulationKey).ciphertext, kem.decapsulationKey)
        assertArrayEquals(dsaCopy, dsa.privateKey)
        assertArrayEquals(kemCopy, kem.decapsulationKey)
    }

    // ── The wipe: if a BouncyCastle update moves the key material, these fail ──

    private fun allByteArraysZero(obj: Any): Boolean {
        var type: Class<*>? = obj.javaClass
        while (type != null && type != Any::class.java) {
            for (f in type.declaredFields) {
                if (f.type != ByteArray::class.java || java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                val v = f.get(obj) as ByteArray? ?: continue
                if (v.any { it != 0.toByte() }) return false
            }
            type = type.superclass
        }
        return true
    }

    @Test
    fun `a wiped ML-DSA key holds nothing and can't sign any more`() {
        val keys = pqc.generateSigningKeyPair()
        val seed = keys.privateKey.copyOfRange(keys.privateKey.size - 32, keys.privateKey.size)
        val key = MLDSAPrivateKeyParameters(MLDSAParameters.ml_dsa_65, seed)
        wipeByteArrayFields(key)
        assertTrue(allByteArraysZero(key))
        val signer = MLDSASigner()
        val signature = try {
            signer.init(true, ParametersWithRandom(key, SecureRandom()))
            signer.update(byteArrayOf(1, 2, 3), 0, 3)
            signer.generateSignature()
        } catch (_: Exception) {
            null
        }
        assertFalse(signature != null && jcaVerify(byteArrayOf(1, 2, 3), signature, keys.publicKey))
    }

    @Test
    fun `a wiped ML-KEM key holds nothing and can't decapsulate any more`() {
        val keys = pqc.generateKemKeyPair()
        val (ciphertext, secret) = jcaEncapsulate(keys.encapsulationKey)
        val seed = keys.decapsulationKey.copyOfRange(keys.decapsulationKey.size - 64, keys.decapsulationKey.size)
        val key = MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, seed)
        wipeByteArrayFields(key)
        assertTrue(allByteArraysZero(key))
        val after = try { MLKEMExtractor(key).extractSecret(ciphertext) } catch (_: Exception) { null }
        assertFalse(after != null && after.contentEquals(secret))
    }
}
