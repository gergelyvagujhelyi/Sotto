package com.sotto.app.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DatabaseModuleTest {
    // Named like Android's and SQLCipher's corruption error, which the stubbed android.jar
    // can't create in a JVM test.
    private class SQLiteDatabaseCorruptException(message: String) : RuntimeException(message)

    @Test
    fun `a key that doesn't match, or a corrupt file, starts over`() {
        assertTrue(DatabaseModule.isUnreadableDatabase(RuntimeException("file is not a database (code 26 SQLITE_NOTADB)")))
        assertTrue(
            DatabaseModule.isUnreadableDatabase(
                IllegalStateException("open failed", SQLiteDatabaseCorruptException("database disk image is malformed")),
            ),
        )
    }

    @Test
    fun `a migration that failed or is missing keeps every chat`() {
        assertFalse(DatabaseModule.isUnreadableDatabase(IllegalStateException("A migration from 10 to 11 was required but not found.")))
        assertFalse(DatabaseModule.isUnreadableDatabase(IllegalStateException("Migration didn't properly handle: messages")))
        assertFalse(DatabaseModule.isUnreadableDatabase(RuntimeException("duplicate column name: nickname (code 1 SQLITE_ERROR)")))
    }

    @Test
    fun `an error nobody recognises keeps every chat`() {
        assertFalse(DatabaseModule.isUnreadableDatabase(RuntimeException("something unexpected")))
        assertFalse(DatabaseModule.isUnreadableDatabase(IllegalStateException()))
    }

    @Test
    fun `migrations lead from every schema version to the newest, one step at a time`() {
        val schemas = File("schemas/com.sotto.app.data.local.AppDatabase")
        val newest = schemas.listFiles().orEmpty().mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max()
        val steps = DatabaseModule.MIGRATIONS.map { it.startVersion to it.endVersion }.sortedBy { it.first }
        assertEquals((1 until newest).map { it to it + 1 }, steps)
    }
}
