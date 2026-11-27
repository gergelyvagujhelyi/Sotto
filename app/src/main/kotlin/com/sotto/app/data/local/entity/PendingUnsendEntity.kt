package com.sotto.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A one-to-one unsend (ADR-36) the relay doesn't have yet. Kept apart from the message's row,
 * which can go before it's out (deleted, its timer, the chat deleted): the contact must still
 * be told. Gone once the relay has it.
 */
@Entity(tableName = "pending_unsends")
data class PendingUnsendEntity(
    /** Our local id for the unsent message. */
    @PrimaryKey val messageId: String,
    val recipientId: String,
    val createdAt: Long,
)
