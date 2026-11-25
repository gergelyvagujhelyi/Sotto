package com.sotto.app.domain.model

import kotlinx.datetime.Instant

/** Where the user stands in a group (ADR-32). */
enum class GroupStatus(val wire: String) {
    /** Invited, not answered yet: no group content arrives. */
    INVITED("invited"),
    /** Accepted; waiting for the admin's roster that makes the user a member. */
    JOINING("joining"),
    ACTIVE("active"),
    LEFT("left"),
    REMOVED("removed"),
    /** The admin deleted the group. */
    ENDED("ended"),
    /** The user declined the invitation (kept so the group's later packets are dropped). */
    REJECTED("rejected"),
    ;

    /** Whether the user can still write in it. */
    val canSend: Boolean get() = this == ACTIVE

    companion object {
        fun fromWire(wire: String): GroupStatus = entries.firstOrNull { it.wire == wire } ?: ENDED
    }
}

data class Group(
    val id: String,
    val title: String,
    val adminId: String,
    val isAdmin: Boolean,
    val epoch: Long,
    /** Disappearing messages in this group, set by its admin; 0: off. */
    val timerMs: Long,
    val status: GroupStatus,
    /** The admin's account is gone: members, title and timer can't change any more. */
    val frozen: Boolean,
    val invitedBy: String?,
    val invitedByName: String?,
    val lastMessagePreview: String?,
    val lastMessageTimestamp: Instant?,
    val unreadCount: Int,
    val memberCount: Int,
)

enum class GroupMemberStatus { ACTIVE, INVITED, GONE }

/** What this phone knows about a member's keys. */
enum class GroupKeyState(val wire: String) {
    /** Keys matching what the admin vouched for are pinned here. */
    OK("ok"),
    /** Not fetched yet (they're invited, or the relay couldn't be reached). */
    PENDING("pending"),
    /** The keys this phone has for them differ from the admin's: nothing goes to or comes from them here. */
    MISMATCH("mismatch"),
    ;

    companion object {
        fun fromWire(wire: String): GroupKeyState = entries.firstOrNull { it.wire == wire } ?: PENDING
    }
}

data class GroupMember(
    val userId: String,
    val name: String,
    val isAdmin: Boolean,
    val isMe: Boolean,
    val status: GroupMemberStatus,
    /** How the admin verified them: the vouch the group rests on. */
    val vouchedTrust: GroupVouch,
    /** The user's own contact (null when only the admin vouches for them), and how the user verified them. */
    val contactTrust: ContactTrust?,
    val keyState: GroupKeyState,
)

/** How a group's admin verified a member (RosterMember.trust). */
enum class GroupVouch { IN_PERSON, BY_PHONE, SHARE_CODE, NONE }

/** Where one member's copy of a group message (or a roster) is. */
enum class GroupCopyState { SENDING, SENT, DELIVERED, READ, FAILED, SKIPPED }

data class GroupCopy(
    val recipientId: String,
    val recipientName: String,
    val state: GroupCopyState,
    val error: String?,
)

/** A reaction to a group message: who, which emoji. */
data class GroupReaction(val reactorId: String, val reactorName: String, val emoji: String, val isMine: Boolean)
