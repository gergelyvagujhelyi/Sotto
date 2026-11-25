package com.sotto.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A group this phone knows (ADR-32): one it's in, was invited to, or was in. Its messages
 * are rows of `messages` with [id] as their conversation and group id.
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val title: String,
    val adminId: String,
    val epoch: Long,
    /** Disappearing messages, set by the admin (0: off). */
    val timerMs: Long,
    /** [com.sotto.app.domain.model.GroupStatus.wire]. */
    val status: String,
    /** The newest signed roster this phone took (GroupRoster as JSON). */
    val roster: String,
    /** Who invited the user, while the invitation is open. */
    val invitedBy: String?,
    /** The admin's account is gone without a handover: the roster can't change any more. */
    val frozen: Boolean = false,
    val lastMessagePreview: String? = null,
    val lastMessageTimestamp: Long? = null,
    val unreadCount: Int = 0,
    val createdAt: Long,
)

/** A member of a group as its roster says, with what this phone knows about their keys. */
@Entity(tableName = "group_members", primaryKeys = ["groupId", "userId"], indices = [Index("userId")])
data class GroupMemberEntity(
    val groupId: String,
    val userId: String,
    val name: String,
    /** "admin" or "member". */
    val role: String,
    /** "active", "invited", or "gone" (left or deleted their account; the roster may lag). */
    val status: String,
    /** The fingerprint the admin vouched for. */
    val fingerprint: String,
    /** How the admin verified them (RosterMember.trust). */
    val vouchedTrust: String,
    /** "ok" (this phone uses keys matching [fingerprint]), "pending" (not fetched yet) or "mismatch". */
    val keyState: String,
)

/**
 * One copy of a group packet for one recipient (ADR-32), from the moment it's queued until the
 * relay has it. Its [id] is the relay's client message id ([com.sotto.app.group.GroupIds.copyClientId]):
 * a retry sends the same sealed bytes under the same id, so a copy is never sent twice under
 * two ids and never re-encrypted once the ratchet has moved.
 */
@Entity(
    tableName = "group_outbox",
    indices = [Index("messageId"), Index("relayId"), Index("state"), Index("groupId")],
)
data class GroupOutboxEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    /** The group message (row id in `messages`) this copy carries; null for control packets. */
    val messageId: String?,
    val recipientId: String,
    /** The packet's kind (GroupPacket.kind). */
    val kind: String,
    /** The packet, until it's sealed. */
    val packet: String?,
    /** The sealed copy (ratchet-encrypted), its nonce and signatures, until the relay has it. */
    val ciphertext: ByteArray? = null,
    val nonce: ByteArray? = null,
    val signature: String? = null,
    val pqcSignature: String? = null,
    /** The relay's id for this copy, for delivery and read receipts. */
    val relayId: String,
    /** "queued", "sealed", "sent", "delivered", "read", "failed" or "skipped". */
    val state: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean = other is GroupOutboxEntity && other.id == id && other.state == state && other.updatedAt == updatedAt
    override fun hashCode(): Int = id.hashCode()
}

/** One member's reaction to a group message (by its row id). */
@Entity(tableName = "group_reactions", primaryKeys = ["messageId", "reactorId"])
data class GroupReactionEntity(
    val messageId: String,
    val reactorId: String,
    val emoji: String,
)

/**
 * A group packet that arrived before the roster it needs (from a member this phone doesn't know
 * yet, or with a newer epoch): kept, already decrypted, until that roster comes or a week passes.
 * [senderFingerprint] is the fingerprint of the keys its envelope was verified with; the member's
 * vouched fingerprint must match it when it's let in.
 */
@Entity(tableName = "group_held", indices = [Index("groupId")])
data class GroupHeldEntity(
    @PrimaryKey val relayId: String,
    val groupId: String,
    val senderId: String,
    val senderFingerprint: String,
    val packet: String,
    val epoch: Long,
    val receivedAt: Long,
)
