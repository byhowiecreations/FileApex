package com.fileapex.data.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyTableDropTest {
    @Test
    fun migrationCopiesALeftoverNoteThenDropsTheOldTables() {
        val file = File.createTempFile("legacy-drop", ".db")
        file.deleteOnExit()
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        connection.prepare(
            """
            CREATE TABLE messages (
                id TEXT NOT NULL, originDeviceId TEXT NOT NULL, senderName TEXT NOT NULL,
                content TEXT NOT NULL, contentType INTEGER NOT NULL, timestamp INTEGER NOT NULL,
                isDeleted INTEGER NOT NULL, isPinned INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        ).use { it.step() }
        connection.prepare(
            """
            CREATE TABLE note_records (
                noteId TEXT NOT NULL, sourceDeviceId TEXT NOT NULL, sourceDeviceName TEXT NOT NULL,
                content TEXT NOT NULL, driveFileId TEXT, checksum TEXT, epochMs INTEGER NOT NULL,
                isMine INTEGER NOT NULL, attachmentFileName TEXT, attachmentSizeBytes INTEGER NOT NULL,
                attachmentPinned INTEGER NOT NULL, attachmentLocalPath TEXT, PRIMARY KEY(noteId)
            )
            """.trimIndent()
        ).use { it.step() }
        connection.prepare(
            "CREATE TABLE pending_control_deliveries (id INTEGER PRIMARY KEY, targetDeviceId TEXT NOT NULL, payloadJson TEXT NOT NULL, preferPlaintextFirst INTEGER NOT NULL, createdAtEpochMs INTEGER NOT NULL)"
        ).use { it.step() }
        connection.prepare(
            "INSERT INTO note_records VALUES ('n1','dev','Phone','hello',NULL,NULL,10,1,NULL,0,0,NULL)"
        ).use { it.step() }
        connection.prepare(
            "INSERT INTO pending_control_deliveries VALUES (1,'dev','{}',0,10)"
        ).use { it.step() }
        runBlocking { MIGRATION_19_20.migrate(connection) }
        connection.prepare("SELECT id, content, contentType FROM messages").use {
            assertTrue(it.step())
            assertEquals("n1", it.getText(0))
            assertEquals("hello", it.getText(1))
            assertEquals(0L, it.getLong(2))
        }
        assertFalse(tableExists(connection, "note_records"))
        assertFalse(tableExists(connection, "pending_control_deliveries"))
        connection.close()
    }

    private fun tableExists(connection: androidx.sqlite.SQLiteConnection, name: String): Boolean {
        connection.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
            statement.bindText(1, name)
            return statement.step()
        }
    }
}
