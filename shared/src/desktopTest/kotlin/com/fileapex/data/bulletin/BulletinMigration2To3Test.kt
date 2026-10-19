package com.fileapex.data.bulletin

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BulletinMigration2To3Test {
    @Test
    fun migrationAddsUpdatedAtAndBackfillsFromCreationTime() = runBlocking {
        val file = File.createTempFile("fileapex-bulletin-migration", ".db")
        file.deleteOnExit()
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        connection.prepare(
            "CREATE TABLE `messages` (`id` TEXT NOT NULL, `originDeviceId` TEXT NOT NULL, `senderName` TEXT NOT NULL, " +
                "`content` TEXT NOT NULL, `contentType` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, " +
                "`isDeleted` INTEGER NOT NULL, `isPinned` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        ).use { it.step() }
        connection.prepare(
            "CREATE TABLE `tombstones` (`id` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, " +
                "`originDeviceId` TEXT NOT NULL, `remotePurge` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        ).use { it.step() }
        connection.prepare(
            "INSERT INTO `messages` VALUES ('m1', 'dev', 'Desk', 'hello', 0, 1234, 0, 1)"
        ).use { it.step() }
        connection.prepare("INSERT INTO `tombstones` VALUES ('m0', 777, 'dev', 0)").use { it.step() }

        BULLETIN_MIGRATION_2_3.migrate(connection)

        connection.prepare("SELECT `content`, `isPinned`, `updatedAt` FROM `messages` WHERE `id` = 'm1'").use { s ->
            assertTrue(s.step())
            assertEquals("hello", s.getText(0))
            assertEquals(1L, s.getLong(1))
            assertEquals(1234L, s.getLong(2))
        }
        connection.prepare("SELECT `deletedAt`, `updatedAt` FROM `tombstones` WHERE `id` = 'm0'").use { s ->
            assertTrue(s.step())
            assertEquals(777L, s.getLong(0))
            assertEquals(777L, s.getLong(1))
        }
        connection.close()
    }
}
