package com.sotto.app.crypto

import org.bouncycastle.jcajce.SecretKeyWithEncapsulation
import org.bouncycastle.jcajce.spec.KEMExtractSpec
import org.bouncycastle.jcajce.spec.KEMGenerateSpec
import org.bouncycastle.jcajce.spec.MLDSAParameterSpec
import org.bouncycastle.jcajce.spec.MLKEMParameterSpec
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.crypto.params.ParametersWithRandom
import org.bouncycastle.jcajce.spec.KEMKDFSpec
import org.bouncycastle.pqc.jcajce.provider.util.KdfUtil
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSASigner
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import org.bouncycastle.pqc.crypto.util.PublicKeyFactory
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyGenerator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production ML-KEM-768 provider backed by Bouncy Castle 1.79+.
 *
 * ML-KEM-768 is standardized as NIST FIPS 203. Bouncy Castle implements it
 * under the "ML-KEM" algorithm name with [MLKEMParameterSpec.ml_kem_768] parameters.
 *
 * Since BC 1.78 the PQC algorithms are bundled in the main bcprov jar.
 * The full [BouncyCastleProvider] (replacing Android's stripped version)
 * includes both classical and PQC algorithms.
 *
 * Signing, decapsulation and encapsulation go through BouncyCastle's low-level classes, not
 * the JCA: a JCA key object holds a copy of the private key (or the shared secret) that
 * can't be wiped. The private key's seed is read straight from our stored key, and after
 * use [wipeByteArrayFields] wipes the key material in BouncyCastle's parameter object,
 * which has no wipe of its own (PqcWipingTest guards that across BouncyCastle updates).
 * A key in any other form than BouncyCastle's seed-only PKCS#8 takes the JCA path.
 */
@Singleton
class BouncyCastlePqcProvider @Inject constructor() : PqcProvider {

    init {
        // Ensure full BC provider is registered (Android's built-in BC is stripped)
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)?.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    override fun generateKemKeyPair(): KemKeyPair {
        val kpg = KeyPairGenerator.getInstance(ALGORITHM, PROVIDER)
        kpg.initialize(MLKEMParameterSpec.ml_kem_768, SecureRandom())
        val kp = kpg.generateKeyPair()
        return KemKeyPair(
            encapsulationKey = kp.public.encoded,
            decapsulationKey = kp.private.encoded,
        )
    }

    private val random = SecureRandom()

    override fun encapsulate(encapsulationKey: ByteArray): KemEncapsulation {
        val public = try {
            PublicKeyFactory.createKey(encapsulationKey) as? MLKEMPublicKeyParameters
        } catch (_: Exception) {
            null
        }
        if (public != null && public.parameters == MLKEMParameters.ml_kem_768) {
            val result = MLKEMGenerator(random).generateEncapsulated(public)
            val raw = result.secret
            try {
                return KemEncapsulation(ciphertext = result.encapsulation, sharedSecret = jcaKemKey(raw))
            } finally {
                raw.fill(0)
                result.destroy() // wipes its copy of the shared secret
            }
        }
        val kf = KeyFactory.getInstance(ALGORITHM, PROVIDER)
        val publicKey = kf.generatePublic(X509EncodedKeySpec(encapsulationKey))

        val keyGen = KeyGenerator.getInstance(ALGORITHM, PROVIDER)
        keyGen.init(KEMGenerateSpec(publicKey, "AES"), SecureRandom())

        val secretKey = keyGen.generateKey() as SecretKeyWithEncapsulation
        try {
            return KemEncapsulation(
                ciphertext = secretKey.encapsulation,
                sharedSecret = secretKey.encoded,
            )
        } finally {
            tryDestroy(secretKey)
        }
    }

    override fun decapsulate(ciphertext: ByteArray, decapsulationKey: ByteArray): ByteArray {
        seedOf(decapsulationKey, ML_KEM_768_PKCS8_HEADER, 64)?.let { seed ->
            val key = MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, seed)
            var raw: ByteArray? = null
            try {
                raw = MLKEMExtractor(key).extractSecret(ciphertext)
                return jcaKemKey(raw)
            } finally {
                raw?.fill(0)
                seed.fill(0)
                wipeByteArrayFields(key)
            }
        }
        val kf = KeyFactory.getInstance(ALGORITHM, PROVIDER)
        val privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(decapsulationKey))

        val keyGen = KeyGenerator.getInstance(ALGORITHM, PROVIDER)
        keyGen.init(KEMExtractSpec(privateKey, ciphertext, "AES"))

        val secretKey = keyGen.generateKey() as SecretKeyWithEncapsulation
        try {
            return secretKey.encoded
        } finally {
            tryDestroy(secretKey)
        }
    }

    override fun generateSigningKeyPair(): DsaKeyPair {
        val kpg = KeyPairGenerator.getInstance(DSA_ALGORITHM, PROVIDER)
        kpg.initialize(MLDSAParameterSpec.ml_dsa_65, SecureRandom())
        val kp = kpg.generateKeyPair()
        return DsaKeyPair(
            publicKey = kp.public.encoded,
            privateKey = kp.private.encoded,
        )
    }

    override fun sign(data: ByteArray, privateKey: ByteArray): ByteArray {
        seedOf(privateKey, ML_DSA_65_PKCS8_HEADER, 32)?.let { seed ->
            val key = MLDSAPrivateKeyParameters(MLDSAParameters.ml_dsa_65, seed)
            try {
                val signer = MLDSASigner()
                signer.init(true, ParametersWithRandom(key, random))
                signer.update(data, 0, data.size)
                return signer.generateSignature()
            } finally {
                seed.fill(0)
                wipeByteArrayFields(key)
            }
        }
        val kf = KeyFactory.getInstance(DSA_ALGORITHM, PROVIDER)
        val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(privateKey))
        try {
            val sig = Signature.getInstance(DSA_ALGORITHM, PROVIDER)
            sig.initSign(privKey)
            sig.update(data)
            return sig.sign()
        } finally {
            tryDestroy(privKey)
        }
    }

    override fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        val kf = KeyFactory.getInstance(DSA_ALGORITHM, PROVIDER)
        val pubKey = kf.generatePublic(X509EncodedKeySpec(publicKey))
        val sig = Signature.getInstance(DSA_ALGORITHM, PROVIDER)
        sig.initVerify(pubKey)
        sig.update(data)
        return sig.verify(signature)
    }

    /** Best-effort key zeroization — Android's default Destroyable.destroy() throws. */
    private fun tryDestroy(key: javax.security.auth.Destroyable) {
        try {
            key.destroy()
        } catch (_: javax.security.auth.DestroyFailedException) {
            // Android's default implementation doesn't support destroy;
            // the shared secret was already copied out by the caller.
        }
    }

    /**
     * The shared secret as the JCA path always gave it (sessions with older versions depend
     * on it): not ML-KEM's raw secret, but KDF3/SHA-256 of it, 256 bits, as BouncyCastle's
     * KEMGenerateSpec/KEMExtractSpec do by default. BouncyCastle's own KdfUtil derives it.
     */
    private fun jcaKemKey(raw: ByteArray): ByteArray = KdfUtil.makeKeyBytes(JcaDefaultKemKdf, raw.copyOf())

    /** KEMKDFSpec's constructor is protected: this is the JCA default, for [jcaKemKey]. */
    private object JcaDefaultKemKdf : KEMKDFSpec(
        AlgorithmIdentifier(X9ObjectIdentifiers.id_kdf_kdf3, AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256)),
        ByteArray(0),
        "AES",
        256,
    )

    /** The seed in a seed-only PKCS#8 key with this [header]; null for any other form. */
    private fun seedOf(key: ByteArray, header: ByteArray, seedSize: Int): ByteArray? {
        if (key.size != header.size + seedSize || header.indices.any { key[it] != header[it] }) return null
        return key.copyOfRange(header.size, key.size)
    }

    companion object {
        /** BouncyCastle's seed-only PKCS#8 headers (OID 2.16.840.1.101.3.4.3.18 / .4.4.2). */
        private val ML_DSA_65_PKCS8_HEADER = hexToBytes("3034020100300b060960864801650304031204220420")
        private val ML_KEM_768_PKCS8_HEADER = hexToBytes("3054020100300b060960864801650304040204420440")
        private fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

        private const val ALGORITHM = "ML-KEM"
        private const val DSA_ALGORITHM = "ML-DSA"
        private const val PROVIDER = "BC"
    }
}
