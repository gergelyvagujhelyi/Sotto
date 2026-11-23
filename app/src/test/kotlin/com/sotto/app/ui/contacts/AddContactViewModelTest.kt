package com.sotto.app.ui.contacts

import com.sotto.app.domain.model.Contact
import com.sotto.app.domain.model.Identity
import com.sotto.app.domain.repository.ConversationRepository
import com.sotto.app.domain.repository.IdentityRepository
import com.sotto.app.domain.usecase.AddContactUseCase
import com.sotto.app.domain.usecase.SendMyNameUseCase
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddContactViewModelTest {
    private val mine = "MyOwnShareCode01"
    private val theirs = "TheirShareCode02"
    private val identity = Identity("me", ByteArray(32), ByteArray(32), Instant.fromEpochSeconds(0), mine)

    private val addContact = mockk<AddContactUseCase>()
    private val conversations = mockk<ConversationRepository>()
    private val identities = mockk<IdentityRepository>()
    private val sendMyName = mockk<SendMyNameUseCase>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { identities.getContacts() } returns flowOf(emptyList())
        coEvery { identities.getIdentity() } returns identity
        coEvery { identities.getMyName() } returns "Me"
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val verifier = mockk<com.sotto.app.crypto.InPersonVerifier>()
    private val myCode = com.sotto.app.crypto.InPersonCode("aaaaaaaaaaaaaaaa", ByteArray(32) { 1 })
    private val theirCode = com.sotto.app.crypto.InPersonCode("bbbbbbbbbbbbbbbb", ByteArray(32) { 2 })
    private val theirShareCode = com.sotto.app.crypto.KeyFingerprint.shareCode(theirCode.fingerprint)
    private val them = com.sotto.app.domain.model.Contact(
        userId = theirCode.userId,
        displayName = "Astrid",
        publicIdentityKey = ByteArray(32),
        publicSigningKey = ByteArray(32),
        verified = true,
    )

    private fun viewModel(): AddContactViewModel {
        every { verifier.myCode() } returns myCode
        return AddContactViewModel(addContact, conversations, identities, sendMyName, verifier)
    }

    @Test
    fun `a code read off their phone adds them and verifies them in person`() {
        coEvery { identities.getContact(theirCode.userId) } returns null
        coEvery { addContact(theirShareCode) } returns Result.success(them)
        coEvery { verifier.verify(theirCode.userId, theirCode) } returns com.sotto.app.crypto.InPersonResult.Verified
        coEvery { conversations.getOrCreateConversation(theirCode.userId) } returns "conv-1"

        val vm = viewModel()
        vm.addInPerson(theirCode)

        val state = vm.state.value as AddContactState.Success
        assertEquals("conv-1", state.conversationId)
        assertEquals(true, state.contact.verifiedInPerson)
        coVerify(exactly = 1) { sendMyName(theirCode.userId) }
    }

    @Test
    fun `keys that don't match their phone are not kept`() {
        coEvery { identities.getContact(theirCode.userId) } returns null
        coEvery { addContact(theirShareCode) } returns Result.success(them)
        coEvery { verifier.verify(theirCode.userId, theirCode) } returns com.sotto.app.crypto.InPersonResult.KeysDiffer
        coEvery { conversations.removeContact(theirCode.userId) } just Runs

        val vm = viewModel()
        vm.addInPerson(theirCode)

        assertTrue(vm.state.value is AddContactState.Error)
        coVerify(exactly = 1) { conversations.removeContact(theirCode.userId) }
        coVerify(exactly = 0) { sendMyName(any()) }
    }

    @Test
    fun `the user's own in-person code is refused`() {
        val vm = viewModel()
        vm.addInPerson(com.sotto.app.crypto.InPersonCode(identity.userId, ByteArray(32) { 1 }))
        assertEquals(AddContactState.Error(OWN_SHARE_CODE_MESSAGE), vm.state.value)
        coVerify(exactly = 0) { addContact(any()) }
    }

    @Test
    fun `a contact removed from the list is removed with the chat`() {
        coEvery { conversations.removeContact("peer-1") } just Runs
        viewModel().removeContact("peer-1")
        coVerify(exactly = 1) { conversations.removeContact("peer-1") }
    }

    @Test
    fun `the user's own share code is refused at once, without asking the relay`() {
        val vm = viewModel()
        vm.updateShareCode(" MyOwn ShareCode01\n") // pasted, with spaces
        vm.submitShareCode()

        assertEquals(AddContactState.Error(OWN_SHARE_CODE_MESSAGE), vm.state.value)
        coVerify(exactly = 0) { addContact(any()) }
    }

    @Test
    fun `the own code is recognised even before the screen has loaded the identity`() {
        coEvery { identities.getIdentity() } returnsMany listOf(null, identity)
        val vm = viewModel()
        vm.submitScannedShareCode(mine)

        assertEquals(AddContactState.Error(OWN_SHARE_CODE_MESSAGE), vm.state.value)
        coVerify(exactly = 0) { addContact(any()) }
    }

    @Test
    fun `someone else's share code is added`() {
        val contact = Contact(theirs, "Them", ByteArray(32), ByteArray(32))
        coEvery { addContact(theirs) } returns Result.success(contact)
        coEvery { conversations.getOrCreateConversation(theirs) } returns "conversation-1"
        val vm = viewModel()
        vm.updateShareCode(theirs)
        vm.submitShareCode()

        val state = vm.state.value
        assertTrue("expected success, got $state", state is AddContactState.Success)
        assertEquals("conversation-1", (state as AddContactState.Success).conversationId)
    }
}
