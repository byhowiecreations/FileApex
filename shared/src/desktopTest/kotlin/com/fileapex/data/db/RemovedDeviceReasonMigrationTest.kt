package com.fileapex.data.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovedDeviceReasonMigrationTest {
    @Test
    fun migration23to24AddsReasonAndRemoverEmptyAndKeepsExistingRows() = runBlocking {
        val file = File.createTempFile("fileapex-migration", ".db")
        file.deleteOnExit()
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        connection.prepare(
            "CREATE TABLE `removed_devices` (`deviceId` TEXT NOT NULL, `publicKeyHash` TEXT NOT NULL, " +
                "`lastKnownIp` TEXT NOT NULL, `port` INTEGER NOT NULL, `removedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`deviceId`))"
        ).use { it.step() }
        connection.prepare(
            "INSERT INTO `removed_devices` VALUES ('honor', 'hh', '192.168.1.60', 8080, 1234)"
        ).use { it.step() }
        MIGRATION_23_24.migrate(connection)
        connection.prepare(
            "SELECT `publicKeyHash`, `removedAtEpochMs`, `reason`, `removedBy` FROM `removed_devices` WHERE `deviceId` = 'honor'"
        ).use { statement ->
            assertTrue(statement.step())
            assertEquals("hh", statement.getText(0))
            assertEquals(1234L, statement.getLong(1))
            assertEquals("", statement.getText(2))
            assertEquals("", statement.getText(3))
        }
        connection.close()
    }
}
