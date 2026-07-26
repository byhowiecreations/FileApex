package com.fileapex.data.db

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

/**
 * Adds [RemovedDeviceEntity] without touching existing paired-device rows.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.prepare(
            """
            CREATE TABLE IF NOT EXISTS `removed_devices` (
                `deviceId` TEXT NOT NULL,
                `publicKeyHash` TEXT NOT NULL,
                `lastKnownIp` TEXT NOT NULL,
                `port` INTEGER NOT NULL,
                `removedAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`deviceId`)
            )
            """.trimIndent()
        ).use { statement ->
            statement.step()
        }
    }
}

/** Adds atomic peer metadata columns to [PairedDeviceEntity]. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        listOf(
            "ALTER TABLE `paired_devices` ADD COLUMN `clientVersion` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `paired_devices` ADD COLUMN `clientVersionCode` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `paired_devices` ADD COLUMN `platform` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `paired_devices` ADD COLUMN `supportedProtocolsJson` TEXT NOT NULL DEFAULT '[]'",
            "ALTER TABLE `paired_devices` ADD COLUMN `lastSeenEpochMs` INTEGER NOT NULL DEFAULT 0"
        ).forEach { sql ->
            connection.prepare(sql).use { statement ->
                statement.step()
            }
        }
    }
}

/** Adds hardware identity columns to [PairedDeviceEntity]. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override suspend fun migrate(connection: SQLiteConnection) {
        listOf(
            "ALTER TABLE `paired_devices` ADD COLUMN `os` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `paired_devices` ADD COLUMN `deviceMake` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `paired_devices` ADD COLUMN `deviceModel` TEXT NOT NULL DEFAULT ''"
        ).forEach { sql ->
            connection.prepare(sql).use { statement ->
                statement.step()
            }
        }
    }
}
