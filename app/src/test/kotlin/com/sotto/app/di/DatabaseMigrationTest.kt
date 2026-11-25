package com.sotto.app.di

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs the app's real migrations ([DatabaseModule.MIGRATIONS]) on SQLite (the JVM driver; the
 * app's SQLCipher runs the same SQL) from every exported schema version to the newest, and
 * checks the result is exactly the newest schema Room expects, with no row lost.
 */
class DatabaseMigrationTest {

    private val schemaDir = File("schemas/com.sotto.app.data.local.AppDatabase")
    private val newest = schemaDir.listFiles().orEmpty().mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max()

    private fun schema(version: Int): JsonObject =
        Json.parseToJsonElement(File(schemaDir, "$version.json").readText()).jsonObject["database"]!!.jsonObject

    private fun entities(version: Int): List<JsonObject> = schema(version)["entities"]!!.jsonArray.map { it.jsonObject }

    private fun createSql(entity: JsonObject): List<String> {
        val table = entity["tableName"]!!.jsonPrimitive.content
        val sql = mutableListOf(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
        (entity["indices"] as? JsonArray)?.forEach {
            sql += it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
        }
        return sql
    }

    /** A database at schema [version], as Room created it then, with one row in every table. */
    private fun databaseAt(version: Int): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            for (entity in entities(version)) for (sql in createSql(entity)) st.execute(sql)
            for (entity in entities(version)) st.execute(sampleInsert(entity))
        }
        return conn
    }

    /** An INSERT filling every column of [entity] with a value of its type. */
    private fun sampleInsert(entity: JsonObject): String {
        val table = entity["tableName"]!!.jsonPrimitive.content
        val fields = entity["fields"]!!.jsonArray.map { it.jsonObject }
        val columns = fields.joinToString(", ") { "`${it["columnName"]!!.jsonPrimitive.content}`" }
        val values = fields.joinToString(", ") {
            when (it["affinity"]!!.jsonPrimitive.content) {
                "INTEGER" -> "1"
                "REAL" -> "1.0"
                "BLOB" -> "x'01'"
                else -> "'v-${it["columnName"]!!.jsonPrimitive.content}'"
            }
        }
        return "INSERT INTO `$table` ($columns) VALUES ($values)"
    }

    private fun migrate(conn: Connection, from: Int) {
        val db = supportDatabase(conn)
        var version = from
        val steps = DatabaseModule.MIGRATIONS.associateBy { it.startVersion }
        while (version < newest) {
            val step = steps[version] ?: error("No migration from $version")
            step.migrate(db)
            version = step.endVersion
        }
    }

    private fun rowCount(conn: Connection, table: String): Int =
        conn.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM `$table`").use { it.next(); it.getInt(1) } }

    private data class Column(val name: String, val affinity: String, val notNull: Boolean, val pk: Int)

    private fun actualColumns(conn: Connection, table: String): Set<Column> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                buildSet {
                    while (rs.next()) {
                        add(Column(rs.getString("name"), rs.getString("type").uppercase(), rs.getInt("notnull") == 1, rs.getInt("pk")))
                    }
                }
            }
        }

    private fun expectedColumns(entity: JsonObject): Set<Column> {
        val pk = entity["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content }
        return entity["fields"]!!.jsonArray.map { it.jsonObject }.map {
            val name = it["columnName"]!!.jsonPrimitive.content
            Column(name, it["affinity"]!!.jsonPrimitive.content, it["notNull"]!!.jsonPrimitive.boolean, pk.indexOf(name) + 1)
        }.toSet()
    }

    private fun actualIndices(conn: Connection, table: String): Set<Pair<String, List<String>>> {
        val names = conn.createStatement().use { st ->
            st.executeQuery("PRAGMA index_list(`$table`)").use { rs ->
                buildList { while (rs.next()) { val n = rs.getString("name"); if (!n.startsWith("sqlite_autoindex")) add(n) } }
            }
        }
        return names.map { name ->
            val cols = conn.createStatement().use { st ->
                st.executeQuery("PRAGMA index_info(`$name`)").use { rs -> buildList { while (rs.next()) add(rs.getInt("seqno") to rs.getString("name")) } }
            }.sortedBy { it.first }.map { it.second }
            name to cols
        }.toSet()
    }

    private fun expectedIndices(entity: JsonObject): Set<Pair<String, List<String>>> =
        (entity["indices"] as? JsonArray)?.map { idx ->
            val o = idx.jsonObject
            o["name"]!!.jsonPrimitive.content to o["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content }
        }.orEmpty().toSet()

    @Test
    fun `every schema version migrates to exactly the newest schema without losing a row`() {
        val target = entities(newest)
        // From 2: version 1 (ChatControll's first builds) lacked messages.plaintext and
        // MIGRATION_1_2 never added it. Sotto started at version 11 (a new app id), so no
        // Sotto install has a version 1 database.
        for (from in 2 until newest) {
            val conn = databaseAt(from)
            val before = entities(from).associate { e ->
                val t = e["tableName"]!!.jsonPrimitive.content
                t to rowCount(conn, t)
            }
            migrate(conn, from)
            for (entity in target) {
                val table = entity["tableName"]!!.jsonPrimitive.content
                assertEquals("v$from → v$newest: columns of $table", expectedColumns(entity), actualColumns(conn, table))
                assertEquals("v$from → v$newest: indices of $table", expectedIndices(entity), actualIndices(conn, table))
            }
            for ((table, count) in before) {
                if (target.any { it["tableName"]!!.jsonPrimitive.content == table }) {
                    assertEquals("v$from → v$newest: rows of $table", count, rowCount(conn, table))
                }
            }
            conn.close()
        }
    }

    @Test
    fun `16 to 17 keeps chats, messages and contacts as they were, and starts with no groups`() {
        val conn = databaseAt(16)
        conn.createStatement().use { st ->
            st.execute("DELETE FROM contacts")
            st.execute("DELETE FROM messages")
            st.execute("DELETE FROM conversations")
            st.execute(
                "INSERT INTO contacts (userId, displayName, publicIdentityKey, publicSigningKey, pqcSigningKey, verified, " +
                    "pqcEstablished, signatureRequired, keyFingerprint, nickname, profileName, louderVoice, verifiedInPerson, " +
                    "inPersonWarned, verifiedByCall) VALUES ('astrid', 'Astrid', x'0102', x'0304', x'05', 1, 1, 1, 'ab', NULL, 'Astrid L', 0, 1, 1, 0)",
            )
            st.execute(
                "INSERT INTO conversations (id, contactId, contactDisplayName, lastMessagePreview, lastMessageTimestamp, unreadCount, " +
                    "isEncrypted, isApproved, needsSessionReset, peerDeleted) VALUES ('c1', 'astrid', 'Astrid', 'Hej', 42, 2, 1, 1, 0, 0)",
            )
            st.execute(
                "INSERT INTO messages (id, conversationId, senderId, recipientId, encryptedBody, nonce, plaintext, state, timestamp, " +
                    "expiresAt, isOutgoing, replyToOwn, mediaDurationMs, mediaWidth, mediaHeight, mediaSize, myReaction) " +
                    "VALUES ('m1', 'c1', 'astrid', 'me', x'aa', x'bb', 'Hej', 'DELIVERED', 42, NULL, 0, 0, 0, 0, 0, 0, '❤️')",
            )
        }
        migrate(conn, 16)
        conn.createStatement().use { st ->
            st.executeQuery("SELECT displayName, verifiedInPerson, groupOnly, publicSigningKey FROM contacts WHERE userId = 'astrid'").use {
                assertTrue(it.next())
                assertEquals("Astrid", it.getString(1))
                assertEquals(1, it.getInt(2))
                assertEquals(0, it.getInt(3))
                assertArrayEquals(byteArrayOf(3, 4), it.getBytes(4))
            }
            st.executeQuery("SELECT plaintext, state, myReaction, groupId, relayId, groupMessageId FROM messages WHERE id = 'm1'").use {
                assertTrue(it.next())
                assertEquals("Hej", it.getString(1))
                assertEquals("DELIVERED", it.getString(2))
                assertEquals("❤️", it.getString(3))
                assertNull(it.getString(4))
                assertNull(it.getString(5))
                assertNull(it.getString(6))
            }
            st.executeQuery("SELECT unreadCount, lastMessagePreview FROM conversations WHERE id = 'c1'").use {
                assertTrue(it.next())
                assertEquals(2, it.getInt(1))
                assertEquals("Hej", it.getString(2))
            }
            for (t in listOf("groups", "group_members", "group_outbox", "group_reactions", "group_held")) {
                assertEquals(0, rowCount(conn, t))
            }
        }
        conn.close()
    }

    @Test
    fun `the migration creates the group tables exactly as Room declares them`() {
        val declared = entities(17).filter { it["tableName"]!!.jsonPrimitive.content.startsWith("group") }.flatMap { createSql(it) }
        assertEquals(declared.toSet(), DatabaseModule.GROUP_TABLES_17.toSet())
        assertEquals(17, schema(17)["version"]!!.jsonPrimitive.int)
    }

    /** Just enough of [SupportSQLiteDatabase] for migrations: execSQL and query, over JDBC. */
    private fun supportDatabase(conn: Connection): SupportSQLiteDatabase =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    conn.createStatement().use { it.execute(args!![0] as String) }
                    null
                }
                "query" -> cursorOf(conn, args!![0] as String)
                else -> throw UnsupportedOperationException("Migration called ${method.name}")
            }
        } as SupportSQLiteDatabase

    private fun cursorOf(conn: Connection, sql: String): android.database.Cursor {
        val rows = mutableListOf<List<Any?>>()
        val names = mutableListOf<String>()
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val meta = rs.metaData
                for (i in 1..meta.columnCount) names += meta.getColumnName(i)
                while (rs.next()) rows += (1..meta.columnCount).map { rs.getObject(it) }
            }
        }
        var position = -1
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(android.database.Cursor::class.java)) { _, method, args ->
            when (method.name) {
                "moveToNext" -> { position++; position < rows.size }
                "getColumnIndexOrThrow", "getColumnIndex" -> names.indexOf(args!![0] as String).also { require(it >= 0 || method.name == "getColumnIndex") }
                "getString" -> rows[position][args!![0] as Int]?.toString()
                "getInt" -> (rows[position][args!![0] as Int] as Number).toInt()
                "getCount" -> rows.size
                "close" -> null
                else -> throw UnsupportedOperationException("Migration called Cursor.${method.name}")
            }
        } as android.database.Cursor
    }
}
