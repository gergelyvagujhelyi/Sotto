package com.sotto.app.domain.usecase

import com.sotto.app.domain.model.Message
import com.sotto.app.domain.repository.MessageRepository
import javax.inject.Inject

class SendMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository,
) {
    suspend operator fun invoke(
        conversationId: String,
        recipientId: String,
        plaintext: String,
    ): Result<Message> = runCatching {
        require(plaintext.isNotBlank()) { "Message cannot be blank" }
        require(plaintext.length <= MAX_MESSAGE_LENGTH) { "Message exceeds maximum length" }
        messageRepository.sendMessage(conversationId, recipientId, plaintext)
    }

    companion object {
        const val MAX_MESSAGE_LENGTH = 10_000
    }
}
