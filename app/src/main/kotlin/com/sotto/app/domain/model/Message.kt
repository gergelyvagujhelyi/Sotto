package com.sotto.app.domain.model

import kotlinx.datetime.Instant

data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val recipientId: String,
    val plaintext: String,
    val state: MessageState,
    val timestamp: Instant,
    val expiresAt: Instant? = null,
    val isOutgoing: Boolean,
    val replyTo: ReplyRef? = null,
    /** The user's reaction to this message (an emoji), and the contact's; null when none. */
    val myReaction: String? = null,
    val theirReaction: String? = null,
) {
    /** Whether this can be replied to: a readable message, not a call, key change or "could not decrypt" placeholder. */
    val canReply: Boolean
        get() = !state.isCallEvent && !state.isKeyChangeEvent &&
            state != MessageState.REJECTED && state != MessageState.DECRYPT_FAILED &&
            plaintext.isNotEmpty()

    /**
     * Whether this can be reacted to: what can be replied to, once the contact has it
     * (not our own message while it's still sending or failed).
     */
    val canReact: Boolean
        get() = canReply && !(isOutgoing && (state == MessageState.SENDING || state == MessageState.FAILED))
}
