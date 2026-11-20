package com.sotto.app.connection

import com.sotto.app.domain.model.InviteLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An invite link that opened the app (sotto://invite?...), until something takes it:
 * setup, to join with it, or, once an account exists, the app, to add the inviter.
 */
@Singleton
class PendingInvite @Inject constructor() {
    private val _invite = MutableStateFlow<InviteLink?>(null)
    val invite: StateFlow<InviteLink?> = _invite.asStateFlow()

    fun offer(invite: InviteLink) {
        _invite.value = invite
    }

    /** The waiting invite, which is then gone; null if there's none. */
    fun take(): InviteLink? = _invite.getAndUpdate { null }
}
