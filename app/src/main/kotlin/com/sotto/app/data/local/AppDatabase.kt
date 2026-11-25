package com.sotto.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.sotto.app.data.local.converter.Converters
import com.sotto.app.data.local.dao.BlockedUserDao
import com.sotto.app.data.local.dao.ContactDao
import com.sotto.app.data.local.dao.ConversationDao
import com.sotto.app.data.local.dao.MessageDao
import com.sotto.app.data.local.entity.BlockedUserEntity
import com.sotto.app.data.local.entity.ContactEntity
import com.sotto.app.data.local.entity.ConversationEntity
import com.sotto.app.data.local.entity.MessageEntity

@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        ContactEntity::class,
        BlockedUserEntity::class,
        com.sotto.app.data.local.entity.GroupEntity::class,
        com.sotto.app.data.local.entity.GroupMemberEntity::class,
        com.sotto.app.data.local.entity.GroupOutboxEntity::class,
        com.sotto.app.data.local.entity.GroupReactionEntity::class,
        com.sotto.app.data.local.entity.GroupHeldEntity::class,
    ],
    version = 17,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun contactDao(): ContactDao
    abstract fun blockedUserDao(): BlockedUserDao
    abstract fun groupDao(): com.sotto.app.data.local.dao.GroupDao
}
