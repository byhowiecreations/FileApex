package com.fileapex.data.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedDeviceTlsMigrationTest {
    @Test
    fun migration13to14AddsTlsColumnsEmptyAndKeepsExistingRow() = runBlocking {
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
                `tailnetIpv4` TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(`deviceId`)
            )
            """.trimIndent()
        ).use { it.step() }
        connection.prepare(
            "INSERT INTO `paired_devices` (`deviceId`, `deviceName`, `lastKnownIp`, `port`) " +
                "VALUES ('abc', 'Desk', '192.168.1.9', 8080)"
        ).use { it.step() }
        MIGRATION_13_14.migrate(connection)
        connection.prepare(
            "SELECT `deviceName`, `lastKnownIp`, `port`, `tlsPin`, `tlsPinAlt`, `tlsPort` " +
                "FROM `paired_devices` WHERE `deviceId` = 'abc'"
        ).use { statement ->
            assertTrue(statement.step())
            assertEquals("Desk", statement.getText(0))
            assertEquals("192.168.1.9", statement.getText(1))
            assertEquals(8080L, statement.getLong(2))
            assertEquals("", statement.getText(3))
            assertEquals("", statement.getText(4))
            assertEquals(0L, statement.getLong(5))
        }
        connection.close()
    }
}
