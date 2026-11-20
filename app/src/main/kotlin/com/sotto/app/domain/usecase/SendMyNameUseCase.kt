package com.sotto.app.domain.usecase

import com.sotto.app.BuildConfig
import com.sotto.app.domain.repository.IdentityRepository
import com.sotto.app.domain.repository.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends the user's own name, if they set one, to a contact they just connected with:
 * someone they added, or whose message request they accepted. The send runs in this
 * object's own scope, so leaving the screen right afterwards doesn't cut it off.
 */
@Singleton
class SendMyNameUseCase @Inject constructor(
    private val identityRepository: IdentityRepository,
    private val messageRepository: MessageRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    operator fun invoke(contactId: String) {
        scope.launch {
            val name = identityRepository.getMyName() ?: return@launch
            try {
                messageRepository.sendProfile(contactId, name)
            } catch (e: Exception) {
                // Best effort: the contact keeps seeing the start of the user's id.
                if (BuildConfig.DEBUG) android.util.Log.w("SendMyName", "Couldn't send name to ${contactId.take(8)}", e)
            }
        }
    }
}
