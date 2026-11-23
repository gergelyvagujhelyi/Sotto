package com.sotto.app.data.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** A file encrypted for sending: its key, what it's called, and its sizes. */
data class EncryptedFile(val key: ByteArray, val name: String, val mime: String, val size: Long, val encryptedSize: Long)

/**
 * Files sent through the relay's object store (ADR-29). A file is encrypted on the phone
 * into `files/media/<message id>` ([SegmentedCrypto]); that ciphertext is what gets
 * uploaded, what the receiver downloads and keeps, and what both sides decrypt only
 * when the user opens it. The upload and download use the presigned URLs the relay
 * hands out; the relay itself never sees the bytes.
 */
@Singleton
class FileDrop @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mediaDir: File get() = File(context.filesDir, "media").apply { mkdirs() }
    private val openDir: File get() = File(context.cacheDir, OPEN_DIR).apply { mkdirs() }

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Transfers under way, by message id: 0..1. */
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    private fun report(messageId: String, value: Float?) {
        _progress.update { if (value == null) it - messageId else it + (messageId to value) }
    }

    fun fileFor(messageId: String): File {
        require(ID.matches(messageId)) { "Bad message id" }
        return File(mediaDir, messageId)
    }

    /** Encrypts the picked file at [uri] into this message's file; throws if it's bigger than [maxBytes]. */
    suspend fun encrypt(uri: Uri, messageId: String, maxBytes: Long): EncryptedFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "file"
        var declared = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) declared = c.getLong(1)
            }
        }
        if (declared > maxBytes) throw FileTooLargeException(maxBytes)
        val mime = resolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val key = SegmentedCrypto.newKey()
        val out = fileFor(messageId)
        val tmp = File(out.parentFile, out.name + ".tmp")
        val size = try {
            (resolver.openInputStream(uri) ?: throw IOException("Can't open the file")).use { input ->
                tmp.outputStream().buffered().use { output ->
                    SegmentedCrypto.encrypt(LimitedInput(input, maxBytes), output, key)
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        if (!tmp.renameTo(out)) {
            tmp.delete()
            throw IOException("Could not store the file")
        }
        EncryptedFile(key, FileNames.clean(name), mime, size, out.length())
    }

    /** Encrypts [bytes] (an HD photo, say) into the file [name]; returns its key. */
    suspend fun encryptBytes(bytes: ByteArray, name: String): ByteArray = withContext(Dispatchers.IO) {
        val key = SegmentedCrypto.newKey()
        val out = fileFor(name)
        val tmp = File(out.parentFile, out.name + ".tmp")
        try {
            tmp.outputStream().buffered().use { SegmentedCrypto.encrypt(bytes.inputStream(), it, key) }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        if (!tmp.renameTo(out)) {
            tmp.delete()
            throw IOException("Could not store the file")
        }
        key
    }

    /** The file [name] decrypted into memory (an HD photo); null when it isn't here or doesn't decrypt. */
    suspend fun decryptToMemory(name: String, key: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        if (!hasFile(name)) return@withContext null
        runCatching {
            java.io.ByteArrayOutputStream().also { out ->
                fileFor(name).inputStream().buffered().use { SegmentedCrypto.decrypt(it, out, key) }
            }.toByteArray()
        }.getOrNull()
    }

    /** PUTs this message's encrypted file to [url] (presigned, with its size signed). */
    suspend fun upload(messageId: String, url: String) = withContext(Dispatchers.IO) {
        val file = fileFor(messageId)
        val total = file.length()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.setFixedLengthStreamingMode(total)
            report(messageId, 0f)
            file.inputStream().use { input ->
                connection.outputStream.use { output ->
                    copy(input, output) { done -> report(messageId, done.toFloat() / total) }
                }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Upload refused ($code)")
        } finally {
            connection.disconnect()
            report(messageId, null)
        }
    }

    /**
     * Downloads the encrypted file from [url] into this message's file, after checking
     * every segment decrypts under [key] (so a damaged or swapped file is never kept).
     */
    suspend fun download(messageId: String, url: String, key: ByteArray, expectedSize: Long) = withContext(Dispatchers.IO) {
        val out = fileFor(messageId)
        val tmp = File(out.parentFile, out.name + ".tmp")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            report(messageId, 0f)
            val code = connection.responseCode
            if (code == 404) throw FileGoneException()
            if (code !in 200..299) throw IOException("Download refused ($code)")
            connection.inputStream.use { input ->
                tmp.outputStream().buffered().use { output ->
                    copy(LimitedInput(input, expectedSize), output) { done -> report(messageId, done.toFloat() / expectedSize) }
                }
            }
            if (tmp.length() != expectedSize) throw IOException("Incomplete download")
            tmp.inputStream().buffered().use { SegmentedCrypto.decrypt(it, NullOutput, key) }
            if (!tmp.renameTo(out)) throw IOException("Could not store the file")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            connection.disconnect()
            report(messageId, null)
        }
    }

    /**
     * Decrypts this message's file into the cache under its own name, for another app to
     * open it; the copy is removed by [sweepOpened] after a while.
     */
    suspend fun decryptForOpening(messageId: String, key: ByteArray, name: String): File = withContext(Dispatchers.IO) {
        val dir = File(openDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val out = File(dir, FileNames.clean(name))
        try {
            fileFor(messageId).inputStream().buffered().use { input ->
                out.outputStream().buffered().use { output -> SegmentedCrypto.decrypt(input, output, key) }
            }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
        out
    }

    fun hasFile(messageId: String): Boolean = runCatching { fileFor(messageId).isFile }.getOrDefault(false)

    /** Removes decrypted copies made for opening, once they're older than [maxAgeMs]. */
    fun sweepOpened(maxAgeMs: Long = OPENED_MAX_AGE_MS) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        File(context.cacheDir, OPEN_DIR).listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
    }

    private fun copy(input: InputStream, output: OutputStream, onProgress: (Long) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        var lastReport = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            done += n
            if (done - lastReport >= 256 * 1024) {
                onProgress(done)
                lastReport = done
            }
        }
        onProgress(done)
    }

    companion object {
        const val OPEN_DIR = "open"
        const val OPENED_MAX_AGE_MS = 60 * 60 * 1000L
        private val ID = Regex("^[A-Za-z0-9-]{1,64}$")
    }
}

class FileTooLargeException(val maxBytes: Long) : IOException("File larger than $maxBytes bytes")
class FileGoneException : IOException("The file is no longer on the relay")

private object NullOutput : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

/** Fails once more than [max] bytes come through. */
private class LimitedInput(private val input: InputStream, private val max: Long) : InputStream() {
    private var count = 0L
    override fun read(): Int {
        val b = input.read()
        if (b >= 0 && ++count > max) throw FileTooLargeException(max)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = input.read(b, off, len)
        if (n > 0) {
            count += n
            if (count > max) throw FileTooLargeException(max)
        }
        return n
    }

    override fun close() = input.close()
}

/** File names from other phones are shown and used to save files, so they're tidied first. */
object FileNames {
    private const val MAX = 120
    private val UNSAFE = Regex("[\\u0000-\\u001f\\u007f/\\\\:*?\"<>|\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]")

    fun clean(name: String): String {
        var n = UNSAFE.replace(name, "_").trim().trim('.')
        if (n.isEmpty()) n = "file"
        if (n.length > MAX) {
            val ext = n.substringAfterLast('.', "").take(10)
            n = n.take(MAX - ext.length - 1).trimEnd() + if (ext.isNotEmpty()) ".$ext" else ""
        }
        return n
    }
}

/**
 * Streaming AES-256-GCM, the construction Tink documents for its streaming AEAD: the
 * file is cut into 64 KiB segments, each sealed under a nonce of a random 7-byte prefix,
 * the segment's 4-byte number and a byte saying whether it's the last. Segments can't be
 * reordered, dropped or cut off without the decryption failing. Layout: a version byte,
 * the prefix, then the sealed segments (each 16 bytes longer than its plaintext).
 */
object SegmentedCrypto {
    const val SEGMENT = 64 * 1024
    private const val TAG = 16
    private const val VERSION: Byte = 1
    private const val PREFIX = 7
    const val HEADER = 1 + PREFIX
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    /** The encrypted size of [plainSize] bytes. */
    fun encryptedSize(plainSize: Long): Long {
        val segments = maxOf(1L, (plainSize + SEGMENT - 1) / SEGMENT)
        return HEADER + plainSize + segments * TAG
    }

    /** Encrypts all of [input] into [output]; returns the plaintext size. */
    fun encrypt(input: InputStream, output: OutputStream, key: ByteArray): Long {
        val prefix = ByteArray(PREFIX).also { random.nextBytes(it) }
        output.write(VERSION.toInt())
        output.write(prefix)
        var current = ByteArray(SEGMENT)
        var next = ByteArray(SEGMENT)
        var currentLen = readFully(input, current)
        var index = 0
        var total = 0L
        while (true) {
            val nextLen = if (currentLen == SEGMENT) readFully(input, next) else 0
            val last = nextLen == 0
            output.write(cipher(Cipher.ENCRYPT_MODE, key, prefix, index, last).doFinal(current, 0, currentLen))
            total += currentLen
            if (last) break
            val swap = current
            current = next
            next = swap
            currentLen = nextLen
            index++
        }
        return total
    }

    /** Decrypts all of [input] into [output]; throws if anything was changed, reordered or cut off. */
    fun decrypt(input: InputStream, output: OutputStream, key: ByteArray): Long {
        val header = ByteArray(HEADER)
        if (readFully(input, header) != HEADER || header[0] != VERSION) throw GeneralSecurityException("Not a Sotto file")
        val prefix = header.copyOfRange(1, HEADER)
        val size = SEGMENT + TAG
        var current = ByteArray(size)
        var next = ByteArray(size)
        var currentLen = readFully(input, current)
        if (currentLen < TAG) throw GeneralSecurityException("Truncated")
        var index = 0
        var total = 0L
        while (true) {
            val nextLen = if (currentLen == size) readFully(input, next) else 0
            if (nextLen in 1 until TAG) throw GeneralSecurityException("Truncated")
            val last = nextLen == 0
            val plain = cipher(Cipher.DECRYPT_MODE, key, prefix, index, last).doFinal(current, 0, currentLen)
            output.write(plain)
            total += plain.size
            if (last) break
            val swap = current
            current = next
            next = swap
            currentLen = nextLen
            index++
        }
        return total
    }

    private fun cipher(mode: Int, key: ByteArray, prefix: ByteArray, index: Int, last: Boolean): Cipher {
        val nonce = ByteArray(12)
        System.arraycopy(prefix, 0, nonce, 0, PREFIX)
        nonce[7] = (index ushr 24).toByte()
        nonce[8] = (index ushr 16).toByte()
        nonce[9] = (index ushr 8).toByte()
        nonce[10] = index.toByte()
        nonce[11] = if (last) 1 else 0
        return MediaCrypto.cipher().apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce)) }
    }

    /** Reads until [buffer] is full or the stream ends; returns how much was read. */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }
}
