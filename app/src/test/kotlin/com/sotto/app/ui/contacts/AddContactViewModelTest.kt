package com.sotto.app.ui.contacts

import com.sotto.app.domain.model.Contact
import com.sotto.app.domain.model.Identity
import com.sotto.app.domain.repository.ConversationRepository
import com.sotto.app.domain.repository.IdentityRepository
import com.sotto.app.domain.usecase.AddContactUseCase
import com.sotto.app.domain.usecase.SendMyNameUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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

    private fun viewModel() = AddContactViewModel(addContact, conversations, identities, sendMyName)

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
