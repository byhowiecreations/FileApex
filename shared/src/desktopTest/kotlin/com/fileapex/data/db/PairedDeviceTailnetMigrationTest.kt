package com.fileapex.data.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedDeviceTailnetMigrationTest {
    @Test
    fun migration12to13AddsTailnetColumnsWithoutChangingLanAddress() = runBlocking {
        val file = File.createTempFile("fileapex-migration", ".db")
        file.deleteOnExit()
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        connection.prepare(
            """
            CREATE TABLE `paired_devices` (
                `deviceId` TEXT NOT NULL,
                `deviceName` TEXT NOT NULL,
                `lastKnownIp` TEXT NOT NULL,
                `port` INTEGER NOT NULL,
                PRIMARY KEY(`deviceId`)
            )
            """.trimIndent()
        ).use { it.step() }
        connection.prepare(
            "INSERT INTO `paired_devices` (`deviceId`, `deviceName`, `lastKnownIp`, `port`) " +
                "VALUES ('abc', 'Desk', '192.168.1.9', 8080)"
        ).use { it.step() }
        MIGRATION_12_13.migrate(connection)
        connection.prepare(
            "SELECT `lastKnownIp`, `tailnetHostname`, `tailnetIpv4` FROM `paired_devices` WHERE `deviceId` = 'abc'"
        ).use { statement ->
            assertTrue(statement.step())
            assertEquals("192.168.1.9", statement.getText(0))
            assertEquals("", statement.getText(1))
            assertEquals("", statement.getText(2))
        }
        connection.close()
    }
}
