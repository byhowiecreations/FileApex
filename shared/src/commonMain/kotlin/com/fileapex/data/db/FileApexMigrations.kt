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

/** Adds E2EE public key columns to [PairedDeviceEntity]. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override suspend fun migrate(connection: SQLiteConnection) {
        listOf(
            "ALTER TABLE `paired_devices` ADD COLUMN `publicKey` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `paired_devices` ADD COLUMN `e2eeEnabled` INTEGER NOT NULL DEFAULT 0"
        ).forEach { sql ->
            connection.prepare(sql).use { statement ->
                statement.step()
            }
        }
    }
}

/** Queues deferred LAN control-plane deliveries (schema retained for in-place upgrades). */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.prepare(
            """
            CREATE TABLE IF NOT EXISTS `pending_control_deliveries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `targetDeviceId` TEXT NOT NULL,
                `payloadJson` TEXT NOT NULL,
                `preferPlaintextFirst` INTEGER NOT NULL,
                `createdAtEpochMs` INTEGER NOT NULL,
                `lastAttemptEpochMs` INTEGER NOT NULL,
                `attemptCount` INTEGER NOT NULL
            )
            """.trimIndent()
        ).use { statement ->
            statement.step()
        }
    }
}

/** Deferred outbound file transfers — source paths only, drained when peer is LAN-reachable. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.prepare(
            """
            CREATE TABLE IF NOT EXISTS `pending_transfers` (
                `id` TEXT NOT NULL,
                `createdAtEpochMs` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `sourceKind` TEXT NOT NULL,
                `sourceJson` TEXT NOT NULL,
                `pendingDeviceIdsJson` TEXT NOT NULL,
                `displayLabel` TEXT NOT NULL,
                `lastAttemptEpochMs` INTEGER NOT NULL,
                `attemptCount` INTEGER NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        ).use { statement ->
            statement.step()
        }
    }
}

/** Adds [NoteEntity] table for persistent notes across app upgrades. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.prepare(
            """
            CREATE TABLE IF NOT EXISTS `note_records` (
                `noteId` TEXT NOT NULL,
                `sourceDeviceId` TEXT NOT NULL,
                `sourceDeviceName` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `driveFileId` TEXT,
                `checksum` TEXT,
                `epochMs` INTEGER NOT NULL,
                `isMine` INTEGER NOT NULL,
                PRIMARY KEY(`noteId`)
            )
            """.trimIndent()
        ).use { statement ->
            statement.step()
        }
    }
}

/** Adds Notes attachment metadata for Google Drive relay. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.prepare(
            "ALTER TABLE `note_records` ADD COLUMN `attachmentFileName` TEXT"
        ).use { statement -> statement.step() }
        connection.prepare(
            "ALTER TABLE `note_records` ADD COLUMN `attachmentSizeBytes` INTEGER NOT NULL DEFAULT 0"
        ).use { statement -> statement.step() }
        connection.prepare(
            "ALTER TABLE `note_records` ADD COLUMN `attachmentPinned` INTEGER NOT NULL DEFAULT 0"
        ).use { statement -> statement.step() }
        connection.prepare(
            "ALTER TABLE `note_records` ADD COLUMN `attachmentLocalPath` TEXT"
        ).use { statement -> statement.step() }
    }
}
