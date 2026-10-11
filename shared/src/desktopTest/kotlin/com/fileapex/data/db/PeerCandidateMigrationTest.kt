package com.fileapex.data.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerCandidateMigrationTest {
    @Test
    fun migration14to15AddsTheCandidateTableAndLeavesPairedRowsAlone() = runBlocking {
        val file = File.createTempFile("fileapex-migration", ".db")
        file.deleteOnExit()
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        connection.prepare(
            """
            CREATE TABLE `paired_devices` (
                `deviceId` TEXT NOT NULL,
                `deviceName` TEXT NOT NULL,
                PRIMARY KEY(`deviceId`)
            )
            """.trimIndent()
        ).use { it.step() }
        connection.prepare("INSERT INTO `paired_devices` VALUES ('abc', 'Desk')").use { it.step() }

        MIGRATION_14_15.migrate(connection)

        connection.prepare(
            "INSERT INTO `peer_candidates` VALUES ('x', 'intro', 'INTRODUCED', 1, 2, 0, 0, '{}')"
        ).use { it.step() }
        connection.prepare("SELECT `origin`, `expiresAtEpochMs` FROM `peer_candidates` WHERE `deviceId` = 'x'").use {
            assertTrue(it.step())
            assertEquals("INTRODUCED", it.getText(0))
            assertEquals(2L, it.getLong(1))
        }
        connection.prepare("SELECT `deviceName` FROM `paired_devices`").use {
            assertTrue(it.step())
            assertEquals("Desk", it.getText(0))
        }
        connection.close()
    }
}
