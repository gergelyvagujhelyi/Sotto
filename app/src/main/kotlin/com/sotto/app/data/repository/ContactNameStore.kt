package com.sotto.app.data.repository

import androidx.room.withTransaction
import com.sotto.app.data.local.AppDatabase
import com.sotto.app.data.local.dao.BlockedUserDao
import com.sotto.app.data.local.dao.ContactDao
import com.sotto.app.data.local.dao.ConversationDao
import com.sotto.app.domain.model.DisplayNames
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Changes a contact's names: the nickname the user gives them and the name they chose and
 * sent. The contact's shown name follows (nickname first), and so do the copies the
 * conversation and the blocked list keep, in one transaction so no screen shows a mix.
 */
@Singleton
class ContactNameStore @Inject constructor(
    private val database: AppDatabase,
    private val contactDao: ContactDao,
    private val conversationDao: ConversationDao,
    private val blockedUserDao: BlockedUserDao,
) {
    /** Null or blank clears the nickname. */
    suspend fun setNickname(userId: String, nickname: String?) {
        val name = DisplayNames.sanitize(nickname)
        update(userId) { contactDao.setNickname(userId, name) }
    }

    /** A name the contact sent; ignored when nothing is left after sanitising it. */
    suspend fun setProfileName(userId: String, name: String) {
        val clean = DisplayNames.sanitize(name) ?: return
        update(userId) { contactDao.setProfileName(userId, clean) }
    }

    private suspend fun update(userId: String, change: suspend () -> Int) {
        database.withTransaction {
            if (change() == 0) return@withTransaction
            val displayName = contactDao.getByUserId(userId)?.displayName ?: return@withTransaction
            conversationDao.setContactDisplayName(userId, displayName)
            blockedUserDao.setDisplayName(userId, displayName)
        }
    }
}
