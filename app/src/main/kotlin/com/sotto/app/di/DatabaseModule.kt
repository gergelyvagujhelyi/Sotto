package com.sotto.app.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.sotto.app.BuildConfig
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sotto.app.crypto.KeyManager
import com.sotto.app.data.local.AppDatabase
import com.sotto.app.data.local.dao.ContactDao
import com.sotto.app.data.local.dao.ConversationDao
import com.sotto.app.data.local.dao.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val DB_NAME = "sotto.db"

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keyManager: KeyManager,
    ): AppDatabase {
        val dbKey = keyManager.getDatabaseKey()
        val passphrase = dbKey.joinToString("") { "%02x".format(it) }.toByteArray()
        dbKey.fill(0)

        val factory = SupportOpenHelperFactory(passphrase)

        // NOTE: do NOT zeroize `passphrase` — SupportOpenHelperFactory stores a
        // reference (not a copy) and reuses it every time Room opens a new
        // SQLite connection (e.g. for concurrent queries on different threads).
        // Zeroizing it causes "file is not a database" crashes on later queries.
        // The raw key material (`dbKey`) is already zeroized above.
        return try {
            buildDatabase(context, factory).also {
                // Force open to detect SQLCipher errors early
                it.openHelper.writableDatabase
            }
        } catch (e: Exception) {
            if (!isUnreadableDatabase(e)) {
                // Everything else keeps every chat on disk and stops here: a migration that
                // failed or is missing (an update with the fix can still open the file), a
                // full or locked disk (it will be readable again), or an error we don't
                // recognise. Deleting the database would lose all chats for good.
                Log.e("DatabaseModule", "Database couldn't be opened; keeping it", e)
                throw e
            }
            Log.e("DatabaseModule", "Database unreadable (key mismatch or corruption), deleting and recreating", e)
            context.deleteDatabase(DB_NAME)
            databaseWasReset = true
            buildDatabase(context, factory)
        }
    }

    /**
     * Whether [error] means the database file itself can't be read, so starting over is the
     * only way on: its key doesn't match ("file is not a database") or the file is corrupt.
     * Never for a migration that failed or is missing, or a temporary storage problem.
     */
    internal fun isUnreadableDatabase(error: Throwable): Boolean {
        if (error.isTransientStorageError()) return false
        return generateSequence(error) { it.cause }.take(8).any {
            it.javaClass.simpleName == "SQLiteDatabaseCorruptException" ||
                it.message.orEmpty().contains("file is not a database", ignoreCase = true)
        }
    }

    private fun Throwable.isTransientStorageError(): Boolean =
        generateSequence(this) { it.cause }.take(8).any {
            it is android.database.sqlite.SQLiteFullException ||
                it is android.database.sqlite.SQLiteDiskIOException ||
                it is android.database.sqlite.SQLiteDatabaseLockedException ||
                it is android.database.sqlite.SQLiteTableLockedException ||
                it is android.database.sqlite.SQLiteCantOpenDatabaseException ||
                it is android.database.sqlite.SQLiteOutOfMemoryException ||
                it is android.database.sqlite.SQLiteReadOnlyDatabaseException ||
                it is android.database.sqlite.SQLiteAccessPermException ||
                it is OutOfMemoryError
        }

    /** Set to true when the DB had to be deleted due to encryption key mismatch. */
    @Volatile
    var databaseWasReset: Boolean = false
        private set

    /** v1→v2: add pqcEstablished and signatureRequired columns to contacts. */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE contacts ADD COLUMN pqcEstablished INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE contacts ADD COLUMN signatureRequired INTEGER NOT NULL DEFAULT 1")
        }
    }

    /** v2→v3: add isApproved column to conversations for message requests. */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN isApproved INTEGER NOT NULL DEFAULT 1")
        }
    }

    /** v3→v4: add needsSessionReset column to conversations for key rotation flow. */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN needsSessionReset INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** v4→v5: add peerDeleted column to conversations for account deletion flow. */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN peerDeleted INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** v6→v7: add pqcSigningKey column to contacts for ML-DSA-65 post-quantum authentication. */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val cursor = db.query("PRAGMA table_info(contacts)")
            var hasColumn = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "pqcSigningKey") {
                    hasColumn = true
                    break
                }
            }
            cursor.close()
            if (!hasColumn) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN pqcSigningKey BLOB NOT NULL DEFAULT x''")
            }
        }
    }

    /** v7→v8: key fingerprints for contacts. */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE contacts ADD COLUMN keyFingerprint TEXT")
        }
    }

    /** v8→v9: blocked users (messages and calls from them are dropped). */
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `blocked_users` (`userId` TEXT NOT NULL, " +
                    "`displayName` TEXT NOT NULL, `blockedAt` INTEGER NOT NULL, PRIMARY KEY(`userId`))"
            )
        }
    }

    /** v9→v10: contact names (one given on this phone, one the contact chose). */
    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE contacts ADD COLUMN nickname TEXT")
            db.execSQL("ALTER TABLE contacts ADD COLUMN profileName TEXT")
        }
    }

    /** v10→v11: Louder, remembered for each contact's calls. */
    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE contacts ADD COLUMN louderVoice INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN replyToId TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN replyQuote TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN replyToOwn INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN myReaction TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN theirReaction TEXT")
        }
    }

    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaKind TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaMime TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaKey BLOB")
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaDurationMs INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaWidth INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaHeight INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaName TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaSize INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN mediaState TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN attachmentId TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN hdKey BLOB")
            db.execSQL("ALTER TABLE messages ADD COLUMN thumbnail BLOB")
        }
    }

    /** v14→v15: verified in person (QR or NFC face to face), and the one-time warning before it. */
    private val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE contacts ADD COLUMN verifiedInPerson INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE contacts ADD COLUMN inPersonWarned INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** v15→v16: verified by call (a code compared in a Sotto call; weaker than in person). */
    private val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE contacts ADD COLUMN verifiedByCall INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v16→v17: group chats (ADR-32). New tables only, and nullable or defaulted columns: every
     * existing conversation, message and contact stays as it was.
     */
    private val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE contacts ADD COLUMN groupOnly INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN groupId TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN groupMessageId TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN relayId TEXT")
            for (sql in GROUP_TABLES_17) db.execSQL(sql)
        }
    }

    /** v17→v18: one-to-one unsends waiting for the relay (ADR-36), a new table only. */
    private val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `pending_unsends` (`messageId` TEXT NOT NULL, `recipientId` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`messageId`))",
            )
        }
    }

    /** The group tables as Room creates them at version 17 (schemas/17.json). */
    internal val GROUP_TABLES_17 = listOf(
        "CREATE TABLE IF NOT EXISTS `groups` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `adminId` TEXT NOT NULL, " +
            "`epoch` INTEGER NOT NULL, `timerMs` INTEGER NOT NULL, `status` TEXT NOT NULL, `roster` TEXT NOT NULL, " +
            "`invitedBy` TEXT, `frozen` INTEGER NOT NULL, `lastMessagePreview` TEXT, `lastMessageTimestamp` INTEGER, " +
            "`unreadCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `group_members` (`groupId` TEXT NOT NULL, `userId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`role` TEXT NOT NULL, `status` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `vouchedTrust` TEXT NOT NULL, " +
            "`keyState` TEXT NOT NULL, PRIMARY KEY(`groupId`, `userId`))",
        "CREATE INDEX IF NOT EXISTS `index_group_members_userId` ON `group_members` (`userId`)",
        "CREATE TABLE IF NOT EXISTS `group_outbox` (`id` TEXT NOT NULL, `groupId` TEXT NOT NULL, `messageId` TEXT, " +
            "`recipientId` TEXT NOT NULL, `kind` TEXT NOT NULL, `packet` TEXT, `ciphertext` BLOB, `nonce` BLOB, " +
            "`signature` TEXT, `pqcSignature` TEXT, `relayId` TEXT NOT NULL, `state` TEXT NOT NULL, " +
            "`attempts` INTEGER NOT NULL, `lastError` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_group_outbox_messageId` ON `group_outbox` (`messageId`)",
        "CREATE INDEX IF NOT EXISTS `index_group_outbox_relayId` ON `group_outbox` (`relayId`)",
        "CREATE INDEX IF NOT EXISTS `index_group_outbox_state` ON `group_outbox` (`state`)",
        "CREATE INDEX IF NOT EXISTS `index_group_outbox_groupId` ON `group_outbox` (`groupId`)",
        "CREATE TABLE IF NOT EXISTS `group_reactions` (`messageId` TEXT NOT NULL, `reactorId` TEXT NOT NULL, " +
            "`emoji` TEXT NOT NULL, PRIMARY KEY(`messageId`, `reactorId`))",
        "CREATE TABLE IF NOT EXISTS `group_held` (`relayId` TEXT NOT NULL, `groupId` TEXT NOT NULL, `senderId` TEXT NOT NULL, " +
            "`senderFingerprint` TEXT NOT NULL, `packet` TEXT NOT NULL, `epoch` INTEGER NOT NULL, `receivedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`relayId`))",
        "CREATE INDEX IF NOT EXISTS `index_group_held_groupId` ON `group_held` (`groupId`)",
    )

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Remove any duplicate contactId rows, keeping the one with the latest message.
            // SQLite guarantees non-aggregated columns come from the MAX() row.
            db.execSQL("""
                DELETE FROM conversations WHERE id NOT IN (
                    SELECT id FROM (
                        SELECT id, MAX(COALESCE(lastMessageTimestamp, 0))
                        FROM conversations
                        GROUP BY contactId
                    )
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_conversations_contactId ON conversations (contactId)")
        }
    }

    private fun buildDatabase(context: Context, factory: SupportOpenHelperFactory): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            DB_NAME,
        )
            .openHelperFactory(factory)
            .addMigrations(*MIGRATIONS)
            // No fallbackToDestructiveMigration(): a migration that is missing or fails
            // throws, and provideDatabase() keeps the file (see isUnreadableDatabase).
            .build()
    }

    /** Every schema step, oldest first; a test checks they lead from version 1 to the newest. */
    internal val MIGRATIONS: Array<Migration>
        get() = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
            MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
            MIGRATION_17_18,
        )

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideConversationDao(db: AppDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideContactDao(db: AppDatabase): ContactDao = db.contactDao()

    @Provides
    fun provideGroupDao(db: AppDatabase): com.sotto.app.data.local.dao.GroupDao = db.groupDao()

    @Provides
    fun provideBlockedUserDao(db: AppDatabase): com.sotto.app.data.local.dao.BlockedUserDao =
        db.blockedUserDao()
}
