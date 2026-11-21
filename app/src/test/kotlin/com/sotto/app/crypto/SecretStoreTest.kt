package com.sotto.app.crypto

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** The secret files, and moving private keys into them from the old storage. */
class SecretStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A software AES key in place of the Android Keystore one. */
    private val aesKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val dir: File get() = File(tmp.root, "private_keys")
    private val rawKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
    /** As the app stores secrets: under the raw file key. */
    private fun store() = SecretStore(dir, RawKeyFileCipher { rawKey })
    /** As the app stores the file key: through a JCA key (the Keystore's in the app). */
    private fun jcaStore(key: SecretKey = aesKey) = SecretStore(dir, JcaFileCipher { key })

    private val signing = ByteArray(48) { (it * 7 + 1).toByte() }
    private val identity = ByteArray(32) { (it * 3 + 5).toByte() }

    // ── The store ──────────────────────────────────────────────────────────

    @Test
    fun `a key comes back as it was written`() {
        val s = store()
        s.write("priv_sign", signing)
        assertArrayEquals(signing, s.read("priv_sign"))
        assertTrue(s.contains("priv_sign"))
        assertNull(s.read("priv_id"))
        assertFalse(s.contains("priv_id"))
    }

    @Test
    fun `the file holds neither the key nor its hex`() {
        store().write("priv_sign", signing)
        val file = File(dir, "priv_sign").readBytes()
        assertFalse(String(file, Charsets.ISO_8859_1).contains(String(signing, Charsets.ISO_8859_1)))
        assertFalse(String(file, Charsets.ISO_8859_1).contains(signing.joinToString("") { "%02x".format(it) }))
    }

    @Test
    fun `a file renamed to another key's name doesn't decrypt`() {
        store().write("priv_sign", signing)
        File(dir, "priv_sign").renameTo(File(dir, "priv_id"))
        try {
            store().read("priv_id")
            fail("a key must not read under another name")
        } catch (_: Exception) {
        }
    }

    @Test
    fun `another key can't read the files`() {
        jcaStore().write("priv_sign", signing)
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        try {
            jcaStore(other).read("priv_sign")
            fail("a different key must not decrypt it")
        } catch (_: Exception) {
        }
    }

    @Test
    fun `writing again replaces the key, and leaves no temporary file`() {
        val s = store()
        s.write("priv_sign", signing)
        s.write("priv_sign", identity)
        assertArrayEquals(identity, s.read("priv_sign"))
        assertEquals(listOf("priv_sign"), dir.list()!!.toList())
    }

    @Test
    fun `keys can be deleted one by one or all together`() {
        val s = store()
        s.write("priv_sign", signing)
        s.write("priv_id", identity)
        s.delete("priv_sign")
        assertNull(s.read("priv_sign"))
        assertArrayEquals(identity, s.read("priv_id"))
        s.deleteAll()
        assertNull(s.read("priv_id"))
    }

    // ── Moving keys from the old storage ───────────────────────────────────

    private val names = listOf("priv_sign", "priv_id", "pqc_dk", "mldsa_priv")

    /** SharedPreferences over a map; [commitWorks] false makes commit fail. */
    private class Prefs(val values: MutableMap<String, String>) {
        var commitWorks = true
        var commits = 0
        val prefs: SharedPreferences = mockk {
            every { getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
            every { edit() } answers {
                val removals = mutableListOf<String>()
                mockk<SharedPreferences.Editor> {
                    every { remove(any()) } answers { removals += firstArg<String>(); self as SharedPreferences.Editor }
                    every { commit() } answers {
                        commits++
                        if (commitWorks) removals.forEach { values.remove(it) }
                        commitWorks
                    }
                }
            }
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun oldStorage() = Prefs(mutableMapOf(
        "priv_sign" to hex(signing), "priv_id" to hex(identity), "pub_sign" to "abcd", "user_id" to "me",
    ))

    @Test
    fun `keys move to the store and leave the old storage, which keeps everything else`() {
        val old = oldStorage()
        migratePrivateKeys(old.prefs, store(), names)

        assertArrayEquals(signing, store().read("priv_sign"))
        assertArrayEquals(identity, store().read("priv_id"))
        assertEquals(mapOf("pub_sign" to "abcd", "user_id" to "me"), old.values)
    }

    @Test
    fun `stopped before the old copies were removed, it finishes the next time`() {
        val old = oldStorage()
        old.commitWorks = false // the removal never reaches the disk (a crash right there)
        try {
            migratePrivateKeys(old.prefs, store(), names)
            fail("a failed removal must be reported")
        } catch (_: IllegalStateException) {
        }
        assertTrue("old copies kept until removed", old.values.containsKey("priv_sign"))
        assertArrayEquals(signing, store().read("priv_sign"))

        old.commitWorks = true
        migratePrivateKeys(old.prefs, store(), names)
        assertFalse(old.values.containsKey("priv_sign"))
        assertArrayEquals(signing, store().read("priv_sign"))
        assertArrayEquals(identity, store().read("priv_id"))
    }

    @Test
    fun `a damaged file from an interrupted move is written again from the old copy`() {
        val old = oldStorage()
        dir.mkdirs()
        File(dir, "priv_sign").writeBytes(byteArrayOf(1, 12, 9, 9, 9)) // half-written garbage
        migratePrivateKeys(old.prefs, store(), names)
        assertArrayEquals(signing, store().read("priv_sign"))
    }

    @Test
    fun `if the store can't be written, the old storage is left as it was`() {
        val old = oldStorage()
        val broken = SecretStore(dir, RawKeyFileCipher { throw IllegalStateException("keystore unavailable") })
        try {
            migratePrivateKeys(old.prefs, broken, names)
            fail("expected the move to fail")
        } catch (_: IllegalStateException) {
        }
        assertEquals(hex(signing), old.values["priv_sign"])
        assertEquals(hex(identity), old.values["priv_id"])
        assertEquals(0, old.commits)
    }

    @Test
    fun `with nothing to move, nothing is written`() {
        val old = Prefs(mutableMapOf("user_id" to "me"))
        migratePrivateKeys(old.prefs, store(), names)
        assertEquals(0, old.commits)
        assertFalse(dir.exists())
    }

    @Test
    fun `hex is decoded exactly, and anything else is refused`() {
        assertArrayEquals(byteArrayOf(0, 15, -1, 16), decodeHex("000fff10"))
        assertArrayEquals(byteArrayOf(-85), decodeHex("AB"))
        for (bad in listOf("abc", "zz", "0g")) {
            try {
                decodeHex(bad)
                fail("'$bad' must be refused")
            } catch (_: IllegalStateException) {
            }
        }
    }

    @Test
    fun `files written through JCA AES-GCM read the same through the raw-key cipher`() {
        // The Note 9 has key files from the first version of this change (JCA AES-GCM under the
        // file key); they must still open now that the raw key does it.
        val fileKey = javax.crypto.spec.SecretKeySpec(rawKey, "AES")
        SecretStore(dir, JcaFileCipher { fileKey }).write("priv_sign", signing)
        assertArrayEquals(signing, store().read("priv_sign"))
        store().write("priv_id", identity)
        assertArrayEquals(identity, SecretStore(dir, JcaFileCipher { fileKey }).read("priv_id"))
    }

    @Test
    fun `a name that isn't a plain file name is refused`() {
        for (bad in listOf("../priv_sign", "a/b", "", "x.tmp")) {
            try {
                store().write(bad, signing)
                fail("'$bad' must be refused")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
