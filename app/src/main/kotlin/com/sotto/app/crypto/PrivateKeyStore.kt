package com.sotto.app.crypto

import android.annotation.SuppressLint
import android.content.SharedPreferences
import java.io.File
import java.io.FileOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Our private keys, each in its own file, encrypted with AES-256-GCM under [secretKey]
 * (in the app, a key that never leaves the Android Keystore). A key comes back as bytes
 * the caller wipes after use. EncryptedSharedPreferences, where they used to be, only keeps
 * text: every read made a hex String of the key, and a String can't be wiped, so copies
 * stayed in memory until the garbage collector freed them.
 *
 * File: [VERSION], IV length, IV, ciphertext + GCM tag. The key's name is the GCM
 * associated data, so a file renamed to another key's name doesn't decrypt.
 * Writes go to a temporary file that is synced and then renamed over the old one, so a
 * crash leaves either the old key or the new one, never half of one.
 */
internal class PrivateKeyStore(
    private val dir: File,
    private val secretKey: () -> SecretKey,
) {
    /** The key called [name], or null if there is none. Throws if its file can't be decrypted. */
    @Synchronized
    fun read(name: String): ByteArray? {
        val file = File(dir, name)
        if (!file.exists()) return null
        val data = file.readBytes()
        check(data.size > 2 && data[0] == VERSION) { "Unknown key file format for $name" }
        val ivLength = data[1].toInt()
        check(data.size > 2 + ivLength) { "Truncated key file for $name" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, data, 2, ivLength))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(data, 2 + ivLength, data.size - 2 - ivLength)
    }

    /** Stores [key] as [name], replacing any earlier one. The caller still wipes [key]. */
    @Synchronized
    fun write(name: String, key: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(key)
        val iv = cipher.iv
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(byteArrayOf(VERSION, iv.size.toByte()))
            out.write(iv)
            out.write(sealed)
            out.fd.sync()
        }
        check(tmp.renameTo(File(dir, name))) { "Could not store key $name" }
    }

    fun contains(name: String): Boolean = File(dir, name).exists()

    @Synchronized
    fun delete(vararg names: String) {
        names.forEach { File(dir, it).delete() }
    }

    @Synchronized
    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val VERSION: Byte = 1
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}

/**
 * Moves the private keys called [names] from [prefs] (hex text, as stored up to 0.7.3) into
 * [store]. Each key is written, read back and compared before any is removed from [prefs],
 * and all are removed in one commit, so this can stop anywhere (a crash, a full disk) and
 * simply run again: until the commit, [prefs] still holds every key. A key in [prefs] wins
 * over an existing file: nothing writes to [prefs] any more, so its copy is the one that was
 * being moved. Throws if a key can't be moved; [prefs] is then left as it was.
 */
// Explicit edit() (UseKtx): the commit's result says whether the old copies are really gone.
@SuppressLint("UseKtx")
internal fun migratePrivateKeys(prefs: SharedPreferences, store: PrivateKeyStore, names: List<String>) {
    val moved = mutableListOf<String>()
    for (name in names) {
        val hex = prefs.getString(name, null) ?: continue
        val key = decodeHex(hex)
        try {
            val stored = try {
                store.read(name)
            } catch (_: Exception) {
                null // unreadable (an interrupted earlier move): written again below
            }
            if (stored == null || !stored.contentEquals(key)) {
                store.write(name, key)
                val back = store.read(name) ?: error("Moved key $name is missing")
                try {
                    check(back.contentEquals(key)) { "Moved key $name reads back differently" }
                } finally {
                    back.fill(0)
                }
            }
            stored?.fill(0)
            moved += name
        } finally {
            key.fill(0)
        }
    }
    if (moved.isEmpty()) return
    val editor = prefs.edit()
    moved.forEach { editor.remove(it) }
    check(editor.commit()) { "Could not remove the moved keys from the old storage" }
}

/** Hex to bytes without cutting the text into smaller Strings (each would be a piece of the key). */
internal fun decodeHex(hex: String): ByteArray {
    check(hex.length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(hex.length / 2) { i ->
        val high = Character.digit(hex[2 * i], 16)
        val low = Character.digit(hex[2 * i + 1], 16)
        check(high >= 0 && low >= 0) { "Not a hex string" }
        ((high shl 4) or low).toByte()
    }
}
