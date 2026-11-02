package com.fileapex.data.db

/**
 * The device list: a profile row joined with its membership row. A profile without a `members` row is an active device
 * that has no TLS pin yet. Reads go through this view; writes go to `member_profiles` (see [DeviceDao.upsertDevice]).
 * The migration builds its `CREATE VIEW` from the same text, because Room compares it when the file opens.
 */
object PairedDevicesView {
    const val NAME = "paired_devices_view"

    const val SQL = "SELECT p.deviceId AS deviceId, p.deviceName AS deviceName, p.lastKnownIp AS lastKnownIp, p.port AS port, " +
        "p.publicKeyHash AS publicKeyHash, p.publicKey AS publicKey, p.e2eeEnabled AS e2eeEnabled, p.rootPath AS rootPath, " +
        "p.clientVersion AS clientVersion, p.clientVersionCode AS clientVersionCode, p.platform AS platform, p.os AS os, " +
        "p.deviceMake AS deviceMake, p.deviceModel AS deviceModel, p.supportedProtocolsJson AS supportedProtocolsJson, " +
        "p.lastSeenEpochMs AS lastSeenEpochMs, p.cardPosX AS cardPosX, p.cardPosY AS cardPosY, p.cardSortOrder AS cardSortOrder, " +
        "p.cardMenuOrder AS cardMenuOrder, p.tilePosX AS tilePosX, p.tilePosY AS tilePosY, p.tileSortOrder AS tileSortOrder, " +
        "p.tileMenuOrder AS tileMenuOrder, 0 AS clusterVersion, " +
        "CASE WHEN m.state IS NULL OR m.state = 'ACTIVE' THEN 0 ELSE 1 END AS isRemoved, " +
        "NULL AS removedAt, " +
        "p.tailnetHostname AS tailnetHostname, p.tailnetIpv4 AS tailnetIpv4, COALESCE(l.tlsPin, '') AS tlsPin, " +
        "COALESCE(l.tlsPinAlt, '') AS tlsPinAlt, COALESCE(l.tlsPort, 0) AS tlsPort, p.hardwareHash AS hardwareHash " +
        "FROM member_profiles AS p LEFT JOIN members AS m ON m.deviceId = p.deviceId LEFT JOIN member_local AS l ON l.deviceId = p.deviceId"
}

/** Every removed device (a `members` row that is not ACTIVE, with its local key hash) in the shape of the old `removed_devices` table. */
object RemovedDevicesView {
    const val NAME = "removed_devices_view"

    const val SQL = "SELECT m.deviceId AS deviceId, COALESCE(NULLIF(l.keyHash, ''), NULLIF(r.keyHash, ''), p.publicKeyHash, '') AS publicKeyHash, " +
        "COALESCE(p.lastKnownIp, '') AS lastKnownIp, COALESCE(p.port, 0) AS port, 0 AS removedAtEpochMs, " +
        "CASE WHEN m.eventReason = 'legacy' THEN '' ELSE m.eventReason END AS reason, m.eventActor AS removedBy " +
        "FROM members AS m LEFT JOIN member_local AS l ON l.deviceId = m.deviceId LEFT JOIN legacy_removed AS r ON r.deviceId = m.deviceId " +
        "LEFT JOIN member_profiles AS p ON p.deviceId = m.deviceId WHERE m.state != 'ACTIVE'"
}

internal fun PairedDeviceEntity.toProfile() = MemberProfileEntity(
    deviceId = deviceId, deviceName = deviceName, lastKnownIp = lastKnownIp, port = port, publicKeyHash = publicKeyHash, publicKey = publicKey,
    e2eeEnabled = e2eeEnabled, rootPath = rootPath, clientVersion = clientVersion, clientVersionCode = clientVersionCode, platform = platform,
    os = os, deviceMake = deviceMake, deviceModel = deviceModel, supportedProtocolsJson = supportedProtocolsJson, lastSeenEpochMs = lastSeenEpochMs,
    cardPosX = cardPosX, cardPosY = cardPosY, cardSortOrder = cardSortOrder, cardMenuOrder = cardMenuOrder, tilePosX = tilePosX, tilePosY = tilePosY,
    tileSortOrder = tileSortOrder, tileMenuOrder = tileMenuOrder, tailnetHostname = tailnetHostname,
    tailnetIpv4 = tailnetIpv4, hardwareHash = hardwareHash
)
