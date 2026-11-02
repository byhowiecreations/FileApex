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

val MIGRATION_10_11 = object : Migration(10, 11) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.prepare(
            "ALTER TABLE `paired_devices` ADD COLUMN `cardPosX` REAL"
        ).use { statement -> statement.step() }
        connection.prepare(
            "ALTER TABLE `paired_devices` ADD COLUMN `cardPosY` REAL"
        ).use { statement -> statement.step() }
        connection.prepare(
            "ALTER TABLE `paired_devices` ADD COLUMN `cardSortOrder` INTEGER NOT NULL DEFAULT 0"
        ).use { statement -> statement.step() }
        connection.prepare(
            "ALTER TABLE `paired_devices` ADD COLUMN `cardMenuOrder` TEXT NOT NULL DEFAULT ''"
        ).use { statement -> statement.step() }
        connection.prepare(
            "ALTER TABLE `paired_devices` ADD COLUMN `tilePosX` REAL"
        ).use { statement -> statement.step() }
        connection.prepare(
            "ALTER TABLE `paired_devices` ADD COLUMN `tilePosY` REAL"
        ).use { statement -> statement.step() }
        connection.prepare(
            "ALTER TABLE `paired_devices` ADD COLUMN `tileSortOrder` INTEGER NOT NULL DEFAULT 0"
        ).use { statement -> statement.step() }
        connection.prepare(
            "ALTER TABLE `paired_devices` ADD COLUMN `tileMenuOrder` TEXT NOT NULL DEFAULT ''"
        ).use { statement -> statement.step() }
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override suspend fun migrate(connection: SQLiteConnection) {
        listOf(
            "ALTER TABLE `paired_devices` ADD COLUMN `clusterVersion` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `paired_devices` ADD COLUMN `isRemoved` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `paired_devices` ADD COLUMN `removedAt` INTEGER"
        ).forEach { sql ->
            connection.prepare(sql).use { statement ->
                statement.step()
            }
        }
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override suspend fun migrate(connection: SQLiteConnection) {
        listOf(
            "ALTER TABLE `paired_devices` ADD COLUMN `tailnetHostname` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `paired_devices` ADD COLUMN `tailnetIpv4` TEXT NOT NULL DEFAULT ''"
        ).forEach { sql ->
            connection.prepare(sql).use { statement ->
                statement.step()
            }
        }
    }
}


val MIGRATION_13_14 = object : Migration(13, 14) {
    override suspend fun migrate(connection: SQLiteConnection) {
        listOf(
            "ALTER TABLE `paired_devices` ADD COLUMN `tlsPin` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `paired_devices` ADD COLUMN `tlsPinAlt` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `paired_devices` ADD COLUMN `tlsPort` INTEGER NOT NULL DEFAULT 0"
        ).forEach { sql ->
            connection.prepare(sql).use { statement ->
                statement.step()
            }
        }
    }
}

val MIGRATION_14_15 = object : Migration(14, 15) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.prepare(
            """
            CREATE TABLE IF NOT EXISTS `peer_candidates` (
                `deviceId` TEXT NOT NULL,
                `introducedBy` TEXT NOT NULL,
                `origin` TEXT NOT NULL,
                `introducedAtEpochMs` INTEGER NOT NULL,
                `expiresAtEpochMs` INTEGER NOT NULL,
                `attempts` INTEGER NOT NULL,
                `lastAttemptEpochMs` INTEGER NOT NULL,
                `deviceJson` TEXT NOT NULL,
                PRIMARY KEY(`deviceId`)
            )
            """.trimIndent()
        ).use { statement ->
            statement.step()
        }
    }
}

val MIGRATION_15_16 = object : Migration(15, 16) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.prepare("ALTER TABLE `paired_devices` ADD COLUMN `hardwareHash` TEXT NOT NULL DEFAULT ''").use { statement ->
            statement.step()
        }
    }
}

/** The Bulletin Board tables move into this database (copy of their version 3 shapes); rows arrive from bulletin_board.db in [com.fileapex.data.bulletin.BulletinBoardMerge]. */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override suspend fun migrate(connection: SQLiteConnection) {
        listOf(
            "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `originDeviceId` TEXT NOT NULL, `senderName` TEXT NOT NULL, `content` TEXT NOT NULL, `contentType` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `isDeleted` INTEGER NOT NULL, `isPinned` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `tombstones` (`id` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, `originDeviceId` TEXT NOT NULL, `remotePurge` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `outbox` (`outboxId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `targetDeviceId` TEXT NOT NULL, `payloadType` INTEGER NOT NULL, `payloadId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `retryCount` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `processed_packets` (`packetId` TEXT NOT NULL, `processedAt` INTEGER NOT NULL, PRIMARY KEY(`packetId`))"
        ).forEach { sql ->
            connection.prepare(sql).use { statement -> statement.step() }
        }
    }
}

/** Friends (0.19.6a): three tables used only by the friends handler. */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override suspend fun migrate(connection: SQLiteConnection) {
        listOf(
            "CREATE TABLE IF NOT EXISTS `friend_links` (`friendKey` TEXT NOT NULL, `label` TEXT NOT NULL, `groupName` TEXT NOT NULL, `addedAtMs` INTEGER NOT NULL, `folderName` TEXT NOT NULL, `blocked` INTEGER NOT NULL, PRIMARY KEY(`friendKey`))",
            "CREATE TABLE IF NOT EXISTS `friend_invites` (`inviteId` TEXT NOT NULL, `secretHash` TEXT NOT NULL, `groupName` TEXT NOT NULL, `expiresAtMs` INTEGER NOT NULL, `failedAttempts` INTEGER NOT NULL, `used` INTEGER NOT NULL, PRIMARY KEY(`inviteId`))",
            "CREATE TABLE IF NOT EXISTS `friend_incoming` (`transferId` TEXT NOT NULL, `friendKey` TEXT NOT NULL, `fileName` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `sha256` TEXT NOT NULL, `state` TEXT NOT NULL, `receivedBytes` INTEGER NOT NULL, `quarantinePath` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, PRIMARY KEY(`transferId`))"
        ).forEach { sql -> connection.prepare(sql).use { it.step() } }
    }
}

/**
 * Drops the pre-Bulletin notes table and the unused control-delivery table.
 * A leftover note is copied into messages only when the Bulletin Board is still empty, which is the same
 * rule the old startup copy used. Devices that already moved their notes keep those rows and lose the old table.
 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override suspend fun migrate(connection: SQLiteConnection) {
        val bulletinEmpty = connection.prepare("SELECT COUNT(*) FROM messages").use { count ->
            count.step() && count.getLong(0) == 0L
        }
        if (bulletinEmpty) {
            connection.prepare(
                """
                INSERT INTO messages (id, originDeviceId, senderName, content, contentType, timestamp, isDeleted, isPinned, updatedAt)
                SELECT noteId, sourceDeviceId, sourceDeviceName, content, 0, epochMs, 0, attachmentPinned, epochMs
                FROM note_records
                """.trimIndent()
            ).use { it.step() }
        }
        connection.prepare("DROP TABLE IF EXISTS note_records").use { it.step() }
        connection.prepare("DROP TABLE IF EXISTS pending_control_deliveries").use { it.step() }
    }
}

/** Friends drop Block (Remove is the only way out) and gain a per friend auto-accept flag. Blocked rows are removed. */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override suspend fun migrate(connection: SQLiteConnection) {
        listOf(
            "CREATE TABLE IF NOT EXISTS `friend_links_new` (`friendKey` TEXT NOT NULL, `label` TEXT NOT NULL, `groupName` TEXT NOT NULL, `addedAtMs` INTEGER NOT NULL, `folderName` TEXT NOT NULL, `autoAccept` INTEGER NOT NULL, PRIMARY KEY(`friendKey`))",
            "INSERT INTO `friend_links_new` (`friendKey`, `label`, `groupName`, `addedAtMs`, `folderName`, `autoAccept`) SELECT `friendKey`, `label`, `groupName`, `addedAtMs`, `folderName`, 0 FROM `friend_links` WHERE `blocked` = 0",
            "DROP TABLE `friend_links`",
            "ALTER TABLE `friend_links_new` RENAME TO `friend_links`"
        ).forEach { sql -> connection.prepare(sql).use { it.step() } }
    }
}

/**
 * Message flags (0.19.8): the Locked state of a message moves into its own table with a change stamp. A message that
 * was locked stays locked: its flag starts with the stamp of its last change, so a later unlock on any device wins.
 */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override suspend fun migrate(connection: SQLiteConnection) {
        listOf(
            "CREATE TABLE IF NOT EXISTS `message_flags` (`flagId` TEXT NOT NULL, `messageId` TEXT NOT NULL, `kind` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `stamp` INTEGER NOT NULL, `setBy` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`flagId`))",
            "INSERT OR IGNORE INTO message_flags (flagId, messageId, kind, enabled, stamp, setBy, updatedAt) SELECT 'flag:locked:' || id, id, 'locked', 1, updatedAt, '', updatedAt FROM messages WHERE isPinned = 1"
        ).forEach { sql -> connection.prepare(sql).use { it.step() } }
    }
}

/** Checklist posts (0.19.8): one row per line, so every line is changed and synced on its own. */
val MIGRATION_21_22 = object : Migration(21, 22) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.prepare(
            "CREATE TABLE IF NOT EXISTS `checklist_items` (`itemId` TEXT NOT NULL, `messageId` TEXT NOT NULL, `text` TEXT NOT NULL, `done` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, `position` INTEGER NOT NULL, `stamp` INTEGER NOT NULL, `setBy` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`itemId`))"
        ).use { it.step() }
    }
}

/**
 * Sensitive text columns are sealed on disk from this version (see SealedColumns). Nothing changes in the schema; the new
 * version only makes an older build refuse the file instead of reading sealed text.
 */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override suspend fun migrate(connection: SQLiteConnection) = Unit
}

/** A removal keeps why it was made and by which device, so a removed device that comes back online can be told. */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.prepare("ALTER TABLE `removed_devices` ADD COLUMN `reason` TEXT NOT NULL DEFAULT ''").use { it.step() }
        connection.prepare("ALTER TABLE `removed_devices` ADD COLUMN `removedBy` TEXT NOT NULL DEFAULT ''").use { it.step() }
    }
}

/** The master roster tables (see MembershipTables). Only what Room expects: the partial unique index and the row checks are added by MembershipSchemaCallback when the database opens, because Room rejects an index its entities do not declare if it is there while it validates. */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.prepare(
            "CREATE TABLE IF NOT EXISTS `members` (`deviceId` TEXT NOT NULL, `state` TEXT NOT NULL, `publicKey` TEXT NOT NULL, `tlsPin` TEXT NOT NULL, " +
                "`eventStamp` INTEGER NOT NULL, `eventActor` TEXT NOT NULL, `eventActorKey` TEXT NOT NULL, `eventReason` TEXT NOT NULL, " +
                "`eventSignature` TEXT NOT NULL, PRIMARY KEY(`deviceId`))"
        ).use { it.step() }
        connection.prepare("CREATE INDEX IF NOT EXISTS `index_members_state` ON `members` (`state`)").use { it.step() }
        connection.prepare("CREATE TABLE IF NOT EXISTS `member_anchors` (`deviceId` TEXT NOT NULL, `publicKey` TEXT NOT NULL, PRIMARY KEY(`deviceId`))").use { it.step() }
        connection.prepare("CREATE TABLE IF NOT EXISTS `membership_meta` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))").use { it.step() }
        connection.prepare(
            "CREATE TABLE IF NOT EXISTS `membership_outbox` (`peerId` TEXT NOT NULL, `signature` TEXT NOT NULL, `eventJson` TEXT NOT NULL, " +
                "`enqueuedAt` INTEGER NOT NULL, PRIMARY KEY(`peerId`, `signature`))"
        ).use { it.step() }
    }
}

/** The roster carries the TLS facts of every device (alt pin, port, and the pins of removed devices) and takes over what the old pin columns held. */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override suspend fun migrate(connection: SQLiteConnection) {
        fun run(sql: String) = connection.prepare(sql).use { it.step() }
        run("ALTER TABLE `members` ADD COLUMN `tlsPinAlt` TEXT NOT NULL DEFAULT ''")
        run("ALTER TABLE `members` ADD COLUMN `tlsPort` INTEGER NOT NULL DEFAULT 0")
        run(
            "UPDATE members SET " +
                "tlsPinAlt = COALESCE((SELECT p.tlsPinAlt FROM paired_devices p WHERE p.deviceId = members.deviceId AND p.isRemoved = 0 AND length(p.tlsPinAlt) = 64), ''), " +
                "tlsPort = COALESCE((SELECT p.tlsPort FROM paired_devices p WHERE p.deviceId = members.deviceId AND p.isRemoved = 0), 0) " +
                "WHERE state = 'ACTIVE'"
        )
        run(
            "UPDATE members SET tlsPin = (SELECT p.tlsPin FROM paired_devices p WHERE p.deviceId = members.deviceId AND length(p.tlsPin) = 64) " +
                "WHERE state != 'ACTIVE' AND length(tlsPin) = 0 AND EXISTS (SELECT 1 FROM paired_devices p WHERE p.deviceId = members.deviceId AND length(p.tlsPin) = 64)"
        )
        run(
            "INSERT OR IGNORE INTO members (deviceId, state, publicKey, tlsPin, eventStamp, eventActor, eventActorKey, eventReason, eventSignature, tlsPinAlt, tlsPort) " +
                "SELECT p.deviceId, 'ACTIVE', '', p.tlsPin, MAX(p.clusterVersion, 1), '', '', 'legacy', '', " +
                "CASE WHEN length(p.tlsPinAlt) = 64 THEN p.tlsPinAlt ELSE '' END, p.tlsPort " +
                "FROM paired_devices p WHERE p.isRemoved = 0 AND length(p.tlsPin) = 64 " +
                "AND NOT EXISTS (SELECT 1 FROM members m WHERE m.deviceId = p.deviceId) " +
                "AND NOT EXISTS (SELECT 1 FROM members m WHERE m.state = 'ACTIVE' AND m.tlsPin = p.tlsPin) " +
                "AND p.deviceId = (SELECT MIN(q.deviceId) FROM paired_devices q WHERE q.isRemoved = 0 AND q.tlsPin = p.tlsPin)"
        )
        run(
            "INSERT OR IGNORE INTO members (deviceId, state, publicKey, tlsPin, eventStamp, eventActor, eventActorKey, eventReason, eventSignature, tlsPinAlt, tlsPort) " +
                "SELECT p.deviceId, 'REMOVED', '', p.tlsPin, MAX(p.clusterVersion, COALESCE(p.removedAt, 0), 1), '', '', 'legacy', '', '', 0 " +
                "FROM paired_devices p WHERE p.isRemoved = 1 AND length(p.tlsPin) = 64 " +
                "AND NOT EXISTS (SELECT 1 FROM members m WHERE m.deviceId = p.deviceId)"
        )
    }
}

private val STEPS: List<Migration> = listOf(
    MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
    MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
    MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24,
    MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28
)

/**
 * Room rejects an index its entities do not declare while it validates after a migration, and it validates every table.
 * The roster's partial unique index stays in the file for good (Room cannot declare it), so each migration starts by
 * removing it; [MembershipSchemaCallback] puts it back when the database opens.
 */
fun rosterSafe(inner: Migration): Migration = object : Migration(inner.startVersion, inner.endVersion) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.prepare("DROP INDEX IF EXISTS `members_active_pin`").use { it.step() }
        inner.migrate(connection)
    }
}

/** Every migration the app registers; the database builders of both platforms use this list. */
fun rosterSafeMigrations(): Array<Migration> = STEPS.map(::rosterSafe).toTypedArray()
