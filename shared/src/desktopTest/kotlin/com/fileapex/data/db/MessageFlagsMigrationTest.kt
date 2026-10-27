package com.fileapex.data.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MessageFlagsMigrationTest {
    @Test
    fun everyLockedMessageGetsALockedFlagAndNothingElseDoes() {
        val file = File.createTempFile("flags-migration", ".db").also { it.deleteOnExit() }
        val c = BundledSQLiteDriver().open(file.absolutePath)
        c.prepare(
            "CREATE TABLE messages (id TEXT NOT NULL, originDeviceId TEXT NOT NULL, senderName TEXT NOT NULL, content TEXT NOT NULL, contentType INTEGER NOT NULL, timestamp INTEGER NOT NULL, isDeleted INTEGER NOT NULL, isPinned INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))"
        ).use { it.step() }
        c.prepare("INSERT INTO messages VALUES ('a','d','n','x',0,1,0,1,500)").use { it.step() }
        c.prepare("INSERT INTO messages VALUES ('b','d','n','x',0,1,0,0,600)").use { it.step() }
        runBlocking { MIGRATION_20_21.migrate(c) }
        c.prepare("SELECT flagId, messageId, kind, enabled, stamp FROM message_flags").use {
            assertEquals(true, it.step())
            assertEquals("flag:locked:a", it.getText(0))
            assertEquals("a", it.getText(1))
            assertEquals("locked", it.getText(2))
            assertEquals(1L, it.getLong(3))
            assertEquals(500L, it.getLong(4))
            assertFalse(it.step())
        }
        c.close()
    }
}
