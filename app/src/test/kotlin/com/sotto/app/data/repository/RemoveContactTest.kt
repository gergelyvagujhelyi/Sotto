package com.sotto.app.data.repository

import com.sotto.app.data.local.dao.BlockedUserDao
import com.sotto.app.data.local.dao.ContactDao
import com.sotto.app.data.local.dao.ConversationDao
import com.sotto.app.data.local.dao.MessageDao
import com.sotto.app.data.local.entity.ConversationEntity
import com.sotto.app.notification.ChatNotificationManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RemoveContactTest {
    private val conversationDao = mockk<ConversationDao>(relaxed = true)
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val contactDao = mockk<ContactDao>(relaxed = true)
    private val notifications = mockk<ChatNotificationManager>(relaxed = true)
    private val repository = ConversationRepositoryImpl(
        conversationDao = conversationDao,
        messageDao = messageDao,
        contactDao = contactDao,
        notificationManager = dagger.Lazy { notifications },
        blockedUserDao = mockk<BlockedUserDao>(relaxed = true),
    )

    @Test
    fun `removing a contact deletes the chat with them, then the contact`() = runTest {
        val chat = mockk<ConversationEntity> {
            every { id } returns "conv-1"
            every { contactId } returns "peer-1"
        }
        coEvery { conversationDao.getByContactId("peer-1") } returns chat
        coEvery { conversationDao.getByIdOnce("conv-1") } returns chat

        repository.removeContact("peer-1")

        coVerifyOrder {
            messageDao.deleteAllForConversation("conv-1")
            conversationDao.delete("conv-1")
            contactDao.delete("peer-1")
        }
        // A leftover notification would reopen the chat.
        verify { notifications.cancelNotification("conv-1", "peer-1") }
    }

    @Test
    fun `a contact without a chat is just removed`() = runTest {
        coEvery { conversationDao.getByContactId("peer-2") } returns null

        repository.removeContact("peer-2")

        coVerify(exactly = 0) { conversationDao.delete(any()) }
        coVerify(exactly = 1) { contactDao.delete("peer-2") }
    }
}
