package com.sotto.app.data.repository

import com.sotto.app.crypto.KeyFingerprint
import com.sotto.app.crypto.KeyManager
import com.sotto.app.data.remote.ApiService
import com.sotto.app.data.remote.dto.ResolveShareCodeResponse
import com.sotto.app.domain.repository.KeyPair
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/** Our private keys don't outlive their use, and contacts are only added with keys matching their share code. */
class IdentityKeysTest {
    private val keyManager = mockk<KeyManager>(relaxed = true)
    private val apiService = mockk<ApiService>(relaxed = true)
    private val contactDao = mockk<com.sotto.app.data.local.dao.ContactDao>(relaxed = true)
    private val repo = IdentityRepositoryImpl(
        cryptoEngine = mockk(relaxed = true),
        pqcProvider = mockk(relaxed = true),
        keyManager = keyManager,
        apiService = apiService,
        contactDao = contactDao,
        conversationDao = mockk(relaxed = true),
        messageDao = mockk(relaxed = true),
        sessionResetSender = mockk(relaxed = true),
        keyVerifier = mockk(relaxed = true),
        contactNames = mockk(relaxed = true),
    )

    @Before
    fun setUp() {
        mockkStatic(android.util.Base64::class, android.util.Log::class)
        every { android.util.Base64.encodeToString(any(), any()) } answers { Base64.getEncoder().encodeToString(firstArg()) }
        every { android.util.Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

    private fun stubIdentity(loaded: KeyPair) {
        every { keyManager.loadIdentityKeyPair() } returns loaded
        every { keyManager.getCreatedAt() } returns 1L
        every { keyManager.getPqcEncapsulationKey() } returns null
        every { keyManager.getMlDsaPublicKey() } returns null
    }

    private fun KeyPair.privateKeysWiped() =
        privateSigningKey.all { it == 0.toByte() } && privateIdentityKey.all { it == 0.toByte() }

    @Test
    fun `getIdentity wipes the private keys it loads`() = runTest {
        val loaded = KeyPair(ByteArray(32) { 1 }, ByteArray(48) { 2 }, ByteArray(32) { 3 }, ByteArray(32) { 4 })
        stubIdentity(loaded)
        every { keyManager.getUserId() } returns "me"

        val identity = repo.getIdentity()

        assertEquals("me", identity!!.userId)
        assertTrue(identity.publicSigningKey.all { it == 1.toByte() })
        assertTrue(loaded.privateKeysWiped())
    }

    @Test
    fun `signing up again with keys left from an unfinished sign-up wipes their private keys`() = runTest {
        val loaded = KeyPair(ByteArray(32) { 1 }, ByteArray(48) { 2 }, ByteArray(32) { 3 }, ByteArray(32) { 4 })
        stubIdentity(loaded)
        every { keyManager.getUserId() } returns null // no account yet
        coEvery { apiService.bootstrapIdentity(any()) } returns
            com.sotto.app.data.remote.dto.BootstrapResponse(userId = "me", shareCode = "x")

        repo.getOrCreateIdentity(com.sotto.app.domain.model.KeyType.CLASSICAL, "invite")

        assertTrue(loaded.privateKeysWiped())
    }

    @Test
    fun `keys that don't match the share code are refused`() = runTest {
        val enc = Base64.getEncoder()
        val realId = ByteArray(32) { 1 }; val realSign = ByteArray(32) { 2 }
        val code = KeyFingerprint.shareCode(KeyFingerprint.compute(realId, realSign, ByteArray(0), ByteArray(0)))
        assertEquals(16, code.length) // 12 bytes, base64url

        every { keyManager.getUserId() } returns "me"
        coEvery { apiService.resolveShareCode(code) } returns ResolveShareCodeResponse(
            userId = "peer", publicSigningKey = enc.encodeToString(ByteArray(32) { 9 }),
            publicIdentityKey = enc.encodeToString(realId), pqcEncapsulationKey = "",
        )
        try {
            repo.addContact(code)
            fail("substituted keys must be refused")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("do not match"))
        }
        coVerify(exactly = 0) { contactDao.upsert(any()) }
    }

    @Test
    fun `after a crash mid-rotation, a relay that refuses the old keys is asked with the staged ones`() = runTest {
        val staged = ByteArray(32) { 7 }
        every { android.util.Base64.decode(any<String>(), any()) } answers { Base64.getDecoder().decode(firstArg<String>()) }
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.d(any(), any()) } returns 0
        every { keyManager.hasStagedKeys() } returns true
        every { keyManager.isRotationConfirmedByServer() } returns false
        every { keyManager.getStagedPublicSigningKey() } returns staged
        every { keyManager.getUserId() } returns "me"
        // Signed in with the old keys: the relay already holds the new ones and refuses (401).
        coEvery { apiService.fetchKeyBundle("me", false) } throws com.sotto.app.data.remote.ApiHttpException(401, "no")
        coEvery { apiService.fetchKeyBundle("me", true) } returns com.sotto.app.data.remote.dto.KeyBundleDto(
            userId = "me",
            publicSigningKey = Base64.getEncoder().encodeToString(staged),
            publicIdentityKey = "",
            pqcEncapsulationKey = "",
        )

        repo.resolvePendingRotation()

        coVerify { apiService.fetchKeyBundle("me", true) }
        io.mockk.verify { keyManager.markRotationConfirmedByServer() }
    }
}
