package com.sotto.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user whose messages and calls are dropped. Kept separately from contacts so
 * a block outlives rejecting a stranger (which deletes their contact entry).
 */
@Entity(tableName = "blocked_users")
data class BlockedUserEntity(
    @PrimaryKey val userId: String,
    /** Name shown in the blocked list (the stranger's contact may be gone). */
    val displayName: String,
    val blockedAt: Long,
)
