package com.fileapex.data.db

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

/** The view as schema 27 had it, frozen so the 26 to 27 step does not change when the view does. */
private const val PAIRED_DEVICES_VIEW_V27 =
    "SELECT p.deviceId AS deviceId, p.deviceName AS deviceName, p.lastKnownIp AS lastKnownIp, p.port AS port, p.pub" +
    "licKeyHash AS publicKeyHash, p.publicKey AS publicKey, p.e2eeEnabled AS e2eeEnabled, p.rootPath AS rootPath, p" +
    ".clientVersion AS clientVersion, p.clientVersionCode AS clientVersionCode, p.platform AS platform, p.os AS os," +
    " p.deviceMake AS deviceMake, p.deviceModel AS deviceModel, p.supportedProtocolsJson AS supportedProtocolsJson," +
    " p.lastSeenEpochMs AS lastSeenEpochMs, p.cardPosX AS cardPosX, p.cardPosY AS cardPosY, p.cardSortOrder AS card" +
    "SortOrder, p.cardMenuOrder AS cardMenuOrder, p.tilePosX AS tilePosX, p.tilePosY AS tilePosY, p.tileSortOrder A" +
    "S tileSortOrder, p.tileMenuOrder AS tileMenuOrder, 0 AS clusterVersion, CASE WHEN m.state IS NULL OR m.state =" +
    " 'ACTIVE' THEN 0 ELSE 1 END AS isRemoved, NULL AS removedAt, p.tailnetHostname AS tailnetHostname, p.tailnetIp" +
    "v4 AS tailnetIpv4, COALESCE(m.tlsPin, '') AS tlsPin, COALESCE(m.tlsPinAlt, '') AS tlsPinAlt, COALESCE(m.tlsPor" +
    "t, 0) AS tlsPort, p.hardwareHash AS hardwareHash FROM member_profiles AS p LEFT JOIN members AS m ON m.deviceI" +
    "d = p.deviceId"

/** The view as schema 27 had it, frozen so the 26 to 27 step does not change when the view does. */
private const val REMOVED_DEVICES_VIEW_V27 =
    "SELECT m.deviceId AS deviceId, COALESCE(NULLIF(m.keyHash, ''), p.publicKeyHash, '') AS publicKeyHash, COALESCE" +
    "(p.lastKnownIp, '') AS lastKnownIp, COALESCE(p.port, 0) AS port, 0 AS removedAtEpochMs, CASE WHEN m.eventReaso" +
    "n = 'legacy' THEN '' ELSE m.eventReason END AS reason, m.eventActor AS removedBy FROM members AS m LEFT JOIN m" +
    "ember_profiles AS p ON p.deviceId = m.deviceId WHERE m.state != 'ACTIVE'"

/** The membership chain: signed, hash-linked records. Membership v2 (the compatibility-breaking build). */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override suspend fun migrate(connection: SQLiteConnection) {
        fun run(sql: String) = connection.prepare(sql).use { it.step() }
        run(
            "CREATE TABLE IF NOT EXISTS `membership_chain` (`hash` TEXT NOT NULL, `seq` INTEGER NOT NULL, `parentHash` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, `actor` TEXT NOT NULL, `actorKey` TEXT NOT NULL, `subject` TEXT NOT NULL, `subjectKey` TEXT NOT NULL, " +
                "`tlsPin` TEXT NOT NULL, `reason` TEXT NOT NULL, `signature` TEXT NOT NULL, PRIMARY KEY(`hash`))"
        )
        run("CREATE INDEX IF NOT EXISTS `index_membership_chain_seq` ON `membership_chain` (`seq`)")
        run("DROP TRIGGER IF EXISTS members_check_insert")
        run("DROP TRIGGER IF EXISTS members_check_update")
        run(
            "CREATE TABLE `members_new` (`deviceId` TEXT NOT NULL, `state` TEXT NOT NULL, `publicKey` TEXT NOT NULL, `tlsPin` TEXT NOT NULL, " +
                "`eventActor` TEXT NOT NULL, `eventReason` TEXT NOT NULL, `tlsPinAlt` TEXT NOT NULL DEFAULT '', `tlsPort` INTEGER NOT NULL DEFAULT 0, " +
                "`keyHash` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`deviceId`))"
        )
        run(
            "INSERT INTO members_new (deviceId, state, publicKey, tlsPin, eventActor, eventReason, tlsPinAlt, tlsPort, keyHash) " +
                "SELECT deviceId, state, publicKey, tlsPin, eventActor, eventReason, tlsPinAlt, tlsPort, '' FROM members"
        )
        run("DROP TABLE `members`")
        run("ALTER TABLE `members_new` RENAME TO `members`")
        run("CREATE INDEX IF NOT EXISTS `index_members_state` ON `members` (`state`)")
        run(
            "CREATE TABLE IF NOT EXISTS `member_profiles` (`deviceId` TEXT NOT NULL, `deviceName` TEXT NOT NULL, `lastKnownIp` TEXT NOT NULL, `port` INTEGER NOT NULL, " +
                "`publicKeyHash` TEXT NOT NULL, `publicKey` TEXT NOT NULL, `e2eeEnabled` INTEGER NOT NULL, `rootPath` TEXT NOT NULL, `clientVersion` TEXT NOT NULL, " +
                "`clientVersionCode` INTEGER NOT NULL, `platform` TEXT NOT NULL, `os` TEXT NOT NULL, `deviceMake` TEXT NOT NULL, `deviceModel` TEXT NOT NULL, " +
                "`supportedProtocolsJson` TEXT NOT NULL, `lastSeenEpochMs` INTEGER NOT NULL, `cardPosX` REAL, `cardPosY` REAL, `cardSortOrder` INTEGER NOT NULL, " +
                "`cardMenuOrder` TEXT NOT NULL, `tilePosX` REAL, `tilePosY` REAL, `tileSortOrder` INTEGER NOT NULL, `tileMenuOrder` TEXT NOT NULL, " +
                "`tailnetHostname` TEXT NOT NULL, `tailnetIpv4` TEXT NOT NULL, `hardwareHash` TEXT NOT NULL, PRIMARY KEY(`deviceId`))"
        )
        run(
            "INSERT OR IGNORE INTO member_profiles SELECT deviceId, deviceName, lastKnownIp, port, publicKeyHash, publicKey, e2eeEnabled, rootPath, clientVersion, " +
                "clientVersionCode, platform, os, deviceMake, deviceModel, supportedProtocolsJson, lastSeenEpochMs, cardPosX, cardPosY, cardSortOrder, cardMenuOrder, " +
                "tilePosX, tilePosY, tileSortOrder, tileMenuOrder, tailnetHostname, tailnetIpv4, hardwareHash FROM paired_devices"
        )
        run(
            "INSERT OR IGNORE INTO members (deviceId, state, publicKey, tlsPin, eventActor, eventReason, tlsPinAlt, tlsPort, keyHash) " +
                "SELECT p.deviceId, 'REMOVED', '', CASE WHEN length(p.tlsPin) = 64 THEN p.tlsPin ELSE '' END, '', 'legacy', '', 0, '' " +
                "FROM paired_devices p WHERE p.isRemoved = 1 AND NOT EXISTS (SELECT 1 FROM members m WHERE m.deviceId = p.deviceId)"
        )
        run(
            "INSERT OR IGNORE INTO members (deviceId, state, publicKey, tlsPin, eventActor, eventReason, tlsPinAlt, tlsPort, keyHash) " +
                "SELECT r.deviceId, 'REMOVED', '', '', r.removedBy, r.reason, '', 0, '' " +
                "FROM removed_devices r WHERE NOT EXISTS (SELECT 1 FROM members m WHERE m.deviceId = r.deviceId)"
        )
        run(
            "UPDATE members SET keyHash = COALESCE((SELECT r.publicKeyHash FROM removed_devices r WHERE r.deviceId = members.deviceId), '') " +
                "WHERE state != 'ACTIVE' AND keyHash = ''"
        )
        run(
            "UPDATE members SET keyHash = COALESCE((SELECT p.publicKeyHash FROM paired_devices p WHERE p.deviceId = members.deviceId AND p.isRemoved = 1), '') " +
                "WHERE state != 'ACTIVE' AND keyHash = ''"
        )
        run("CREATE VIEW `${PairedDevicesView.NAME}` AS $PAIRED_DEVICES_VIEW_V27")
        run("CREATE VIEW `${RemovedDevicesView.NAME}` AS $REMOVED_DEVICES_VIEW_V27")
        run("DROP TABLE IF EXISTS `removed_devices`")
        run("DROP TABLE IF EXISTS `paired_devices`")
        run("DROP TABLE IF EXISTS `peer_candidates`")
        run("DROP TABLE IF EXISTS `member_anchors`")
        run("DELETE FROM membership_meta WHERE `key` IN ('clock', 'clock_check_at', 'clock_check_version')")
    }
}

/**
 * `members` becomes the chain's projection: it keeps the state, the identity key and who removed the device. The pin, the
 * alternate pin, the TLS port and the key hash move to `member_local`. A key-less member the chain never named stays
 * active as a pending admit; a removed one the chain never named moves to `legacy_removed`.
 */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override suspend fun migrate(connection: SQLiteConnection) {
        fun run(sql: String) = connection.prepare(sql).use { it.step() }
        run("DROP VIEW IF EXISTS `${PairedDevicesView.NAME}`")
        run("DROP VIEW IF EXISTS `${RemovedDevicesView.NAME}`")
        run(
            "CREATE TABLE IF NOT EXISTS `member_local` (`deviceId` TEXT NOT NULL, `tlsPin` TEXT NOT NULL DEFAULT '', `tlsPinAlt` TEXT NOT NULL DEFAULT '', " +
                "`tlsPort` INTEGER NOT NULL DEFAULT 0, `keyHash` TEXT NOT NULL DEFAULT '', `pendingAdmitAt` INTEGER NOT NULL DEFAULT 0, " +
                "`pairedAtEpochMs` INTEGER NOT NULL DEFAULT 0, `pairedChainSeq` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`deviceId`))"
        )
        run(
            "CREATE TABLE IF NOT EXISTS `legacy_removed` (`deviceId` TEXT NOT NULL, `keyHash` TEXT NOT NULL DEFAULT '', " +
                "`reason` TEXT NOT NULL DEFAULT '', `removedBy` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`deviceId`))"
        )
        run("INSERT OR IGNORE INTO member_local (deviceId, tlsPin, tlsPinAlt, tlsPort, keyHash) SELECT deviceId, tlsPin, tlsPinAlt, tlsPort, keyHash FROM members")
        run(
            "UPDATE member_local SET pendingAdmitAt = CAST(strftime('%s', 'now') AS INTEGER) * 1000 WHERE deviceId IN " +
                "(SELECT deviceId FROM members WHERE state = 'ACTIVE' AND deviceId NOT IN (SELECT subject FROM membership_chain))"
        )
        run(
            "INSERT OR IGNORE INTO legacy_removed (deviceId, keyHash, reason, removedBy) " +
                "SELECT deviceId, keyHash, CASE WHEN eventReason = 'legacy' THEN '' ELSE eventReason END, eventActor FROM members " +
                "WHERE state != 'ACTIVE' AND deviceId NOT IN (SELECT subject FROM membership_chain)"
        )
        run(
            "CREATE TABLE `members_new` (`deviceId` TEXT NOT NULL, `state` TEXT NOT NULL, `publicKey` TEXT NOT NULL, " +
                "`eventActor` TEXT NOT NULL, `eventReason` TEXT NOT NULL, PRIMARY KEY(`deviceId`))"
        )
        run("INSERT INTO members_new (deviceId, state, publicKey, eventActor, eventReason) SELECT deviceId, state, publicKey, eventActor, eventReason FROM members")
        run("DROP TABLE `members`")
        run("ALTER TABLE `members_new` RENAME TO `members`")
        run("CREATE INDEX IF NOT EXISTS `index_members_state` ON `members` (`state`)")
        run("CREATE VIEW `${PairedDevicesView.NAME}` AS ${PairedDevicesView.SQL}")
        run("CREATE VIEW `${RemovedDevicesView.NAME}` AS ${RemovedDevicesView.SQL}")
        run("CREATE VIEW `${MemberEffectiveView.NAME}` AS ${MemberEffectiveView.SQL}")
    }
}
