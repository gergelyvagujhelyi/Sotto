package com.sotto.app.ui.chat

import androidx.lifecycle.SavedStateHandle
import com.sotto.app.data.repository.ReceiptManager
import com.sotto.app.domain.model.Conversation
import com.sotto.app.domain.repository.ConversationRepository
import com.sotto.app.domain.repository.IdentityRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatDeleteTest {
    private val conversations = mockk<ConversationRepository>(relaxed = true)
    private val identity = mockk<IdentityRepository>(relaxed = true)
    private val receipts = mockk<ReceiptManager>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { identity.isPqcSession(any()) } returns false
        every { identity.observePqcSession(any()) } returns flowOf(false)
        every { receipts.observeUnseen(any()) } returns flowOf(0)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun chat(approved: Boolean): ChatViewModel {
        every { conversations.getConversation("conv-1") } returns flowOf(
            Conversation(
                id = "conv-1",
                contactId = "peer-1",
                contactDisplayName = "Maja Sjöberg",
                lastMessagePreview = "Hej!",
                lastMessageTimestamp = null,
                unreadCount = 0,
                isEncrypted = true,
                isApproved = approved,
            ),
        )
        return ChatViewModel(
            savedStateHandle = SavedStateHandle(mapOf("conversationId" to "conv-1", "contactId" to "peer-1")),
            messageRepository = mockk(relaxed = true),
            conversationRepository = conversations,
            identityRepository = identity,
            sendMessage = mockk(relaxed = true),
            receiptManager = receipts,
            sendMyName = mockk(relaxed = true),
        )
    }

    @Test
    fun `deleting a chat removes it from this phone, then leaves it`() {
        var left = false
        chat(approved = true).deleteChat { left = true }

        coVerify(exactly = 1) { conversations.deleteConversation("conv-1") }
        coVerify(exactly = 0) { conversations.rejectMessageRequest(any()) }
        assertTrue(left)
    }

    @Test
    fun `deleting a message request rejects it, so the stranger is forgotten too`() {
        var left = false
        chat(approved = false).deleteChat { left = true }

        coVerify(exactly = 1) { conversations.rejectMessageRequest("conv-1") }
        coVerify(exactly = 0) { conversations.deleteConversation(any()) }
        assertTrue(left)
    }
}
