package com.fileapex.data.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareHashMigrationTest {
    @Test
    fun migration15to16AddsAnEmptyHardwareHashAndKeepsExistingRows() = runBlocking {
        val file = File.createTempFile("fileapex-migration", ".db")
        file.deleteOnExit()
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        connection.prepare(
            "CREATE TABLE `paired_devices` (`deviceId` TEXT NOT NULL, `deviceName` TEXT NOT NULL, PRIMARY KEY(`deviceId`))"
        ).use { it.step() }
        connection.prepare("INSERT INTO `paired_devices` VALUES ('abc', 'Desk')").use { it.step() }

        MIGRATION_15_16.migrate(connection)

        connection.prepare("SELECT `deviceName`, `hardwareHash` FROM `paired_devices` WHERE `deviceId` = 'abc'").use {
            assertTrue(it.step())
            assertEquals("Desk", it.getText(0))
            assertEquals("", it.getText(1))
        }
        connection.close()
    }
}
