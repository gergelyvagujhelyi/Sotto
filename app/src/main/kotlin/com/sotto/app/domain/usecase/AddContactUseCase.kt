package com.sotto.app.domain.usecase

import com.sotto.app.domain.model.Contact
import com.sotto.app.domain.repository.IdentityRepository
import javax.inject.Inject

class AddContactUseCase @Inject constructor(
    private val identityRepository: IdentityRepository,
) {
    suspend operator fun invoke(shareCode: String): Result<Contact> = runCatching {
        require(shareCode.isNotBlank()) { "Share code cannot be blank" }
        identityRepository.addContact(shareCode.trim())
    }
}
