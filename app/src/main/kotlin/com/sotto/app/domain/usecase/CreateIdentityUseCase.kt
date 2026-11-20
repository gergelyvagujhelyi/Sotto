package com.sotto.app.domain.usecase

import com.sotto.app.domain.model.Identity
import com.sotto.app.domain.model.KeyType
import com.sotto.app.domain.repository.IdentityRepository
import javax.inject.Inject

class CreateIdentityUseCase @Inject constructor(
    private val identityRepository: IdentityRepository,
) {
    suspend operator fun invoke(keyType: KeyType = KeyType.CLASSICAL, inviteCode: String = ""): Identity {
        return identityRepository.getOrCreateIdentity(keyType, inviteCode)
    }
}
