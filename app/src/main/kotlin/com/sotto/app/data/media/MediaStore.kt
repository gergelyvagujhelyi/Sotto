package com.sotto.app.data.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a message's media lives on the phone: `files/media/<message id>`, encrypted with
 * AES-256-GCM under a random key of its own, which is kept in the message's row of the
 * (SQLCipher) database. Without the database the files are noise, and deleting the row
 * makes its file unreadable even before [sweep] removes it. Reading decrypts into memory
 * only.
 */
@Singleton
class MediaStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    /** Encrypts and stores [bytes] for [messageId]; returns the key to keep in its row. */
    suspend fun save(messageId: String, bytes: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val key = MediaCrypto.newKey()
        val file = fileFor(messageId)
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeBytes(MediaCrypto.seal(key, bytes, aad(messageId)))
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("Could not store media")
        }
        key
    }

    /** The media of [messageId], or null when it's gone or doesn't decrypt under [key]. */
    suspend fun load(messageId: String, key: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        val file = fileFor(messageId)
        if (!file.isFile) return@withContext null
        runCatching { MediaCrypto.open(key, file.readBytes(), aad(messageId)) }.getOrNull()
    }

    suspend fun delete(messageId: String) = withContext(Dispatchers.IO) {
        fileFor(messageId).delete()
        Unit
    }

    /** Removes the files of messages that are gone: [keep] is every message id that still has media. */
    suspend fun sweep(keep: Set<String>) = withContext(Dispatchers.IO) {
        val staleTmp = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
        val fresh = System.currentTimeMillis() - 10 * 60 * 1000L
        dir.listFiles()?.forEach { file ->
            // A .tmp is a file being written (an encryption or download under way) until it's old.
            if (file.name.endsWith(".tmp")) {
                if (file.lastModified() < staleTmp) file.delete()
            } else if (file.name.removeSuffix(HD_SUFFIX) !in keep && file.lastModified() < fresh) {
                // Files are written before their message's row: a new one may have no row yet.
                file.delete()
            }
        }
        Unit
    }

    /** Everything, for wiping the app. */
    fun deleteAll() {
        File(context.filesDir, DIR).deleteRecursively()
    }

    private fun fileFor(messageId: String): File {
        // Ids are UUIDs or hex from the relay; anything else could be a path.
        require(ID.matches(messageId)) { "Bad message id" }
        return File(dir, messageId)
    }

    /** Ties a file to its message, so files can't be swapped between messages unnoticed. */
    private fun aad(messageId: String) = "sotto/media-file/v1:$messageId".toByteArray(Charsets.UTF_8)

    companion object {
        /** An HD photo's full-size copy sits beside its message's file. */
        const val HD_SUFFIX = "-hd"
        private const val DIR = "media"
        private val ID = Regex("^[A-Za-z0-9-]{1,64}$")
    }
}

/** AES-256-GCM for small media blobs: a random 12-byte nonce, then the ciphertext and tag. */
object MediaCrypto {
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also { random.nextBytes(it) }

    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = cipher()
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        require(sealed.size > NONCE_BYTES) { "Too short" }
        val cipher = cipher()
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
    }

    /**
     * Android's own OpenSSL-backed AES-GCM when there is one: Bouncy Castle is registered
     * first (for the post-quantum keys) and its AES-GCM is pure Java, many times slower.
     */
    internal fun cipher(): Cipher =
        runCatching { Cipher.getInstance("AES/GCM/NoPadding", "AndroidOpenSSL") }.getOrNull()
            ?: Cipher.getInstance("AES/GCM/NoPadding")
}
