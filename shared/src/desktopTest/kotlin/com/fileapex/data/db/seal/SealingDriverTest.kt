package com.fileapex.data.db.seal

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.security.SecureRandom
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SealingDriverTest {
    private val file = File.createTempFile("sealing", ".db").also { it.deleteOnExit() }
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val sealer = FieldSealer(key)
    private val columns = mapOf("messages" to setOf("senderName", "content"), "friend_links" to setOf("label"))
    private var keyAvailable = true
    private val driver = SealingSqliteDriver(BundledSQLiteDriver(), { if (keyAvailable) sealer else null }, columns)
    private val raw: SQLiteConnection = BundledSQLiteDriver().open(file.absolutePath)
    private val sealed: SQLiteConnection by lazy { driver.open(file.absolutePath) }

    init {
        raw.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY, senderName TEXT NOT NULL, content TEXT NOT NULL, timestamp INTEGER NOT NULL, note TEXT)")
        raw.execSQL("CREATE TABLE friend_links (friendKey TEXT NOT NULL PRIMARY KEY, label TEXT NOT NULL)")
    }

    @After fun close() { runCatching { sealed.close() }; raw.close() }

    private fun rawText(sql: String, vararg args: String): String? = raw.prepare(sql).use { s -> args.forEachIndexed { i, a -> s.bindText(i + 1, a) }; if (s.step()) s.getText(0) else null }

    private fun insertMessage(id: String, sender: String, content: String) {
        sealed.prepare("INSERT OR REPLACE INTO `messages` (`id`,`senderName`,`content`,`timestamp`,`note`) VALUES (?,?,?,?,?)").use {
            it.bindText(1, id); it.bindText(2, sender); it.bindText(3, content); it.bindLong(4, 5L); it.bindNull(5); it.step()
        }
    }

    @Test fun insertSealsOnlyTheListedColumns() {
        insertMessage("m1", "Ann", "secret text")
        assertTrue(rawText("SELECT content FROM messages WHERE id = ?", "m1")!!.startsWith(FieldSealer.PREFIX))
        assertTrue(rawText("SELECT senderName FROM messages WHERE id = ?", "m1")!!.startsWith(FieldSealer.PREFIX))
        assertEquals("m1", rawText("SELECT id FROM messages WHERE id = ?", "m1"))
    }

    @Test fun selectOpensWhatWasSealed() {
        insertMessage("m1", "Ann", "secret text")
        sealed.prepare("SELECT * FROM messages WHERE id = ?").use {
            it.bindText(1, "m1"); assertTrue(it.step())
            val names = (0 until it.getColumnCount()).map { i -> it.getColumnName(i) }
            assertEquals("Ann", it.getText(names.indexOf("senderName")))
            assertEquals("secret text", it.getText(names.indexOf("content")))
            assertTrue(it.isNull(names.indexOf("note")))
        }
    }

    @Test fun updateSealsTheBoundAssignment() {
        insertMessage("m1", "Ann", "old")
        sealed.prepare("UPDATE messages SET timestamp = ?, content = ? WHERE id = ?").use {
            it.bindLong(1, 9L); it.bindText(2, "new text"); it.bindText(3, "m1"); it.step()
        }
        assertTrue(rawText("SELECT content FROM messages WHERE id = ?", "m1")!!.startsWith(FieldSealer.PREFIX))
        sealed.prepare("SELECT content FROM messages WHERE id = ?").use { it.bindText(1, "m1"); it.step(); assertEquals("new text", it.getText(0)) }
    }

    @Test fun whereParametersAreNeverSealed() {
        insertMessage("m1", "Ann", "x")
        sealed.prepare("SELECT COUNT(*) FROM messages WHERE id = ? AND timestamp = ?").use {
            it.bindText(1, "m1"); it.bindLong(2, 5L); it.step(); assertEquals(1L, it.getLong(0))
        }
    }

    @Test fun otherTablesAndAliasesAreUntouched() {
        sealed.prepare("INSERT INTO friend_links (friendKey, label) VALUES (?, ?)").use { it.bindText(1, "k"); it.bindText(2, "Bob"); it.step() }
        assertTrue(rawText("SELECT label FROM friend_links", )!!.startsWith(FieldSealer.PREFIX))
        sealed.prepare("SELECT COUNT(*) FROM friend_links").use { it.step(); assertEquals(1L, it.getLong(0)) }
    }

    @Test fun legacyPlainRowsReadBack() {
        raw.prepare("INSERT INTO messages VALUES ('p', 'Cy', 'plain body', 1, NULL)").use { it.step() }
        sealed.prepare("SELECT content FROM messages WHERE id = 'p'").use { it.step(); assertEquals("plain body", it.getText(0)) }
    }

    @Test fun withoutAKeyASealedColumnIsNotWrittenAndSealedRowsReadEmpty() {
        insertMessage("m1", "Ann", "sealed one")
        keyAvailable = false
        assertThrows(SealedStoreLockedException::class.java) { insertMessage("m2", "Bo", "would be plain") }
        assertNull(rawText("SELECT content FROM messages WHERE id = ?", "m2"))
        sealed.prepare("SELECT content FROM messages WHERE id = ?").use { it.bindText(1, "m1"); it.step(); assertEquals("", it.getText(0)) }
        // Empty text is not sensitive and not sealed, so a row with blank sealed columns can still be written.
        insertMessage("m3", "", "")
        assertEquals("", rawText("SELECT content FROM messages WHERE id = ?", "m3"))
    }

    @Test fun legacyPlainRowsStillReadWithoutAKey() {
        raw.prepare("INSERT INTO messages VALUES ('p', 'Cy', 'plain body', 1, NULL)").use { it.step() }
        keyAvailable = false
        sealed.prepare("SELECT content FROM messages WHERE id = 'p'").use { it.step(); assertEquals("plain body", it.getText(0)) }
    }

    @Test fun aSealedColumnInAnUnreadableStatementShapeFailsLoudly() {
        assertThrows(IllegalStateException::class.java) {
            sealed.prepare("UPDATE messages SET content = ?1 WHERE id = ?2").close()
        }
        assertThrows(IllegalStateException::class.java) {
            sealed.prepare("INSERT INTO messages (id, content) VALUES (?, upper(?))").close()
        }
    }

    @Test fun resetAndRebindReusesTheStatement() {
        sealed.prepare("INSERT OR REPLACE INTO messages (id, senderName, content, timestamp) VALUES (?,?,?,?)").use {
            for (n in 1..3) { it.bindText(1, "m$n"); it.bindText(2, "s$n"); it.bindText(3, "c$n"); it.bindLong(4, n.toLong()); it.step(); it.reset() }
        }
        sealed.prepare("SELECT content FROM messages ORDER BY id").use { s ->
            val got = mutableListOf<String>(); while (s.step()) got += s.getText(0); assertEquals(listOf("c1", "c2", "c3"), got)
        }
        assertTrue(rawText("SELECT content FROM messages WHERE id = ?", "m2")!!.startsWith(FieldSealer.PREFIX))
    }
}
