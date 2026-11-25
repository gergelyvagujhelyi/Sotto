package com.sotto.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sotto.app.data.local.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {

    /** The user's contacts: not the group members only vouched for by a group's admin. */
    @Query("SELECT * FROM contacts WHERE groupOnly = 0 ORDER BY displayName ASC")
    fun getAll(): Flow<List<ContactEntity>>

    /** Everyone whose keys are pinned, group members included: those told of a key change or account deletion. */
    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    suspend fun getAllWithKeys(): List<ContactEntity>

    /** The user now knows them as a contact (added them, or accepted their request). */
    @Query("UPDATE contacts SET groupOnly = 0 WHERE userId = :userId")
    suspend fun clearGroupOnly(userId: String)

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

    /**
     * Marks the contact verified in person, only while [fingerprint] is still the one it's
     * pinned to; returns the rows changed (0 when the keys moved on meanwhile).
     */
    @Query("UPDATE contacts SET verifiedInPerson = 1 WHERE userId = :userId AND keyFingerprint = :fingerprint")
    suspend fun markVerifiedInPerson(userId: String, fingerprint: String): Int

    /** Like [markVerifiedInPerson], for a code compared in a call. */
    @Query("UPDATE contacts SET verifiedByCall = 1 WHERE userId = :userId AND keyFingerprint = :fingerprint")
    suspend fun markVerifiedByCall(userId: String, fingerprint: String): Int

    @Query("UPDATE contacts SET inPersonWarned = 1 WHERE userId = :userId")
    suspend fun setInPersonWarned(userId: String): Int

    @Query("DELETE FROM contacts WHERE userId = :userId")
    suspend fun delete(userId: String)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()
}
