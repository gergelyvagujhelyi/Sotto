package com.sotto.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sotto.app.data.local.entity.BlockedUserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedUserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun block(user: BlockedUserEntity)

    @Query("DELETE FROM blocked_users WHERE userId = :userId")
    suspend fun unblock(userId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_users WHERE userId = :userId)")
    suspend fun isBlocked(userId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_users WHERE userId = :userId)")
    fun observeIsBlocked(userId: String): Flow<Boolean>

    @Query("UPDATE blocked_users SET displayName = :name WHERE userId = :userId")
    suspend fun setDisplayName(userId: String, name: String)

    @Query("SELECT * FROM blocked_users ORDER BY blockedAt DESC")
    fun getAll(): Flow<List<BlockedUserEntity>>

    @Query("DELETE FROM blocked_users")
    suspend fun deleteAll()
}
