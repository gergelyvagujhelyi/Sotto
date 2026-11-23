package com.sotto.app.domain.repository

import com.sotto.app.domain.model.Contact
import com.sotto.app.domain.model.Identity
import com.sotto.app.domain.model.KeyType
import kotlinx.coroutines.flow.Flow

interface IdentityRepository {
    /** [inviteCode]: a member's one-time invite, for a relay that takes new people only by invite. */
    suspend fun getOrCreateIdentity(keyType: KeyType = KeyType.CLASSICAL, inviteCode: String = ""): Identity
    suspend fun hasIdentity(): Boolean
    suspend fun getIdentity(): Identity?
    suspend fun addContact(shareCode: String): Contact
    fun getContacts(): Flow<List<Contact>>
    suspend fun getContact(userId: String): Contact?
    suspend fun rotateIdentityKeys()
    suspend fun fetchKeyBundle(userId: String): Contact?
    fun isPqcSession(peerId: String): Boolean
    fun observePqcSession(peerId: String): Flow<Boolean>
    fun observeContact(userId: String): Flow<Contact?>

    /**
     * Give a contact a name on this phone; null or blank clears it, falling back to the
     * name they chose or the start of their id.
     */
    suspend fun renameContact(userId: String, nickname: String?)

    /** Remembers that the user was warned once that [userId] isn't verified in person. */
    suspend fun markInPersonWarned(userId: String)

    /** Store the name a contact chose for themselves (received end-to-end encrypted). A nickname still wins. */
    suspend fun applyProfileName(userId: String, name: String)

    /** The user's own name, sent to new contacts; null when not set. */
    suspend fun getMyName(): String?

    /** Set or (with null or blank) clear the user's own name. */
    suspend fun setMyName(name: String?)
}
