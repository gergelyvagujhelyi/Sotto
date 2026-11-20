package com.sotto.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sotto.app.data.local.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {

    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    fun getAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE userId = :userId")
    suspend fun getByUserId(userId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE userId = :userId")
    fun observeByUserId(userId: String): Flow<ContactEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    /**
     * Set the name the user gave this contact (null clears it) and recompute displayName:
     * nickname, else the contact's own name, else the start of their id. A targeted UPDATE,
     * because rewriting the whole row could undo a change made in between.
     */
    @Query(
        "UPDATE contacts SET nickname = :nickname, " +
            "displayName = COALESCE(:nickname, profileName, substr(userId, 1, 8)) WHERE userId = :userId"
    )
    suspend fun setNickname(userId: String, nickname: String?): Int

    /** Set the name the contact chose for themselves and recompute displayName (see [setNickname]). */
    @Query(
        "UPDATE contacts SET profileName = :profileName, " +
            "displayName = COALESCE(nickname, :profileName, substr(userId, 1, 8)) WHERE userId = :userId"
    )
    suspend fun setProfileName(userId: String, profileName: String?): Int

    /** Remember Louder for this contact's calls; a targeted UPDATE, like [setNickname]. */
    @Query("UPDATE contacts SET louderVoice = :louder WHERE userId = :userId")
    suspend fun setLouderVoice(userId: String, louder: Boolean): Int

    @Query("DELETE FROM contacts WHERE userId = :userId")
    suspend fun delete(userId: String)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()
}
