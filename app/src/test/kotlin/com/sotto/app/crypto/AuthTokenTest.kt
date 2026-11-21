package com.sotto.app.crypto

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** KeyManager.generateAuthToken, run for real on a KeyManager whose key storage is mocked. */
class AuthTokenTest {
    private val pqc = mockk<PqcProvider>()
    private val keyManager = mockk<KeyManager> {
        every { generateAuthToken(any()) } answers { callOriginal() }
        every { getUserId() } returns "u1"
        every { sign(any()) } returns ByteArray(64) { 1 }
    }

    @Before
    fun setUp() {
        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg())
        }
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `a post-quantum account's token carries both signatures`() {
        every { keyManager.getMlDsaPrivateKey() } returns ByteArray(32)
        every { pqc.sign(any(), any()) } returns ByteArray(8) { 2 }

        assertEquals(4, keyManager.generateAuthToken(pqc).split(".").size)
    }

    @Test
    fun `a classical account's token has no ML-DSA part`() {
        every { keyManager.getMlDsaPrivateKey() } returns null

        assertEquals(3, keyManager.generateAuthToken(pqc).split(".").size)
    }

    @Test
    fun `a failed ML-DSA signature fails the token instead of leaving the signature out`() {
        every { keyManager.getMlDsaPrivateKey() } returns ByteArray(32)
        every { pqc.sign(any(), any()) } throws IllegalStateException("provider broke")

        assertThrows(IllegalStateException::class.java) { keyManager.generateAuthToken(pqc) }
    }

    @Test
    fun `tokens made in quick succession all differ, so the relay can refuse a replayed one`() {
        every { keyManager.getMlDsaPrivateKey() } returns null

        val tokens = (1..500).map { keyManager.generateAuthToken(pqc) }

        assertEquals(500, tokens.toSet().size)
    }
}
