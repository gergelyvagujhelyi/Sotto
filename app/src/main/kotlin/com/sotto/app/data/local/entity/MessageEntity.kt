package com.sotto.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [
        Index("conversationId", "timestamp"),
        Index("state"),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderId: String,
    val recipientId: String,
    val encryptedBody: ByteArray,
    val nonce: ByteArray,
    val plaintext: String = "",
    val state: String,
    val timestamp: Long,
    val expiresAt: Long?,
    val isOutgoing: Boolean,
    /** The message this one replies to (see ReplyRef); null when it isn't a reply. */
    val replyToId: String? = null,
    val replyQuote: String? = null,
    val replyToOwn: Boolean = false,
    /** The emoji the user reacted to this message with, and the contact's; null when none. */
    val myReaction: String? = null,
    val theirReaction: String? = null,
    /**
     * Media carried by the message (ADR-29): its kind (MediaKind.wire) and format, the key
     * its file on disk is encrypted under (MediaStore), and for voice its length, for a
     * photo its size in pixels. All null/0 for a text message; [plaintext] is the caption.
     */
    val mediaKind: String? = null,
    val mediaMime: String? = null,
    val mediaKey: ByteArray? = null,
    val mediaDurationMs: Long = 0,
    val mediaWidth: Int = 0,
    val mediaHeight: Int = 0,
    /** A file's name, its size in bytes, its transfer state (FileState.wire) and the relay's id for it. */
    val mediaName: String? = null,
    val mediaSize: Long = 0,
    val mediaState: String? = null,
    val attachmentId: String? = null,
    /** An HD photo's key: its full-size copy goes through the store like a file (attachmentId, mediaSize, mediaState). */
    val hdKey: ByteArray? = null,
    /** A file's preview (a few KB of JPEG), kept in the (encrypted) database. */
    val thumbnail: ByteArray? = null,
    /**
     * The group this message belongs to (ADR-32), or null for a one-to-one message. A group
     * message's [conversationId] is the group's id too; our own has an empty [recipientId]
     * (its copies, one per member, are in `group_outbox`), and its id is
     * [com.sotto.app.group.GroupIds.rowId], the same on every member's phone.
     */
    val groupId: String? = null,
    /** A group message's own id, the one its packet carries; with [senderId] it names the message for every member. */
    val groupMessageId: String? = null,
    /** For a group message received: the relay's id for our copy, which a read receipt names. */
    val relayId: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
