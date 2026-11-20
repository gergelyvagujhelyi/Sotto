package com.sotto.app.data.repository

import com.sotto.app.data.local.dao.BlockedUserDao
import com.sotto.app.data.local.dao.ContactDao
import com.sotto.app.data.local.dao.ConversationDao
import com.sotto.app.data.local.dao.MessageDao
import com.sotto.app.data.local.entity.ConversationEntity
import com.sotto.app.notification.ChatNotificationManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ClearUnreadTest {
    private val conversationDao = mockk<ConversationDao>(relaxed = true)
    private val notifications = mockk<ChatNotificationManager>(relaxed = true)
    private val repository = ConversationRepositoryImpl(
        conversationDao = conversationDao,
        messageDao = mockk<MessageDao>(relaxed = true),
        contactDao = mockk<ContactDao>(relaxed = true),
        notificationManager = dagger.Lazy { notifications },
        blockedUserDao = mockk<BlockedUserDao>(relaxed = true),
    )

    @Test
    fun `opening a chat clears its message and missed-call notifications`() = runTest {
        // The missed-call one stayed before 0.6.7, so its count would have gone on from calls
        // the user had already seen in the chat.
        val chat = mockk<ConversationEntity> {
            every { id } returns "conv-1"
            every { contactId } returns "peer-1"
        }
        coEvery { conversationDao.getByIdOnce("conv-1") } returns chat

        repository.clearUnread("conv-1")

        coVerify { conversationDao.clearUnread("conv-1") }
        verify { notifications.cancelNotification("conv-1", "peer-1") }
    }
}
