package com.sotto.app.domain.model

import com.sotto.app.ui.text.UiText
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
    /** A voice message or photo carried by it; [plaintext] is then its caption (often empty). */
    val media: MessageMedia? = null,
) {
    /** What a chat list, notification or quote says for it: the text, or the media's kind and caption. */
    val preview: UiText get() = previewUiText(media?.kind, plaintext, media?.name.orEmpty())

    /** Whether this can be replied to: a readable message, not a call, key change or "could not decrypt" placeholder. */
    val canReply: Boolean
        get() = !state.isCallEvent && !state.isKeyChangeEvent &&
            state != MessageState.REJECTED && state != MessageState.DECRYPT_FAILED &&
            (plaintext.isNotEmpty() || media != null)

    /**
     * Whether this can be reacted to: what can be replied to, once the contact has it
     * (not our own message while it's still sending or failed).
     */
    val canReact: Boolean
        get() = canReply && !(isOutgoing && (state == MessageState.SENDING || state == MessageState.FAILED))
}
