package com.fileapex.data.db

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * What this device knows about a peer that is not membership: names, addresses, versions, layout and the confirmed
 * tailnet name. Membership itself (active or removed, who acted when) lives in `members`; the TLS pin and port in `member_local`.
 */
@Entity(tableName = "member_profiles")
data class MemberProfileEntity(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val lastKnownIp: String,
    val port: Int,
    val publicKeyHash: String,
    val publicKey: String,
    val e2eeEnabled: Boolean,
    val rootPath: String,
    val clientVersion: String,
    val clientVersionCode: Int,
    val platform: String,
    val os: String,
    val deviceMake: String,
    val deviceModel: String,
    val supportedProtocolsJson: String,
    val lastSeenEpochMs: Long,
    val cardPosX: Float?,
    val cardPosY: Float?,
    val cardSortOrder: Int,
    val cardMenuOrder: String,
    val tilePosX: Float?,
    val tilePosY: Float?,
    val tileSortOrder: Int,
    val tileMenuOrder: String,
    val tailnetHostname: String,
    val tailnetIpv4: String,
    val hardwareHash: String
) {
    companion object {
        /** A member the chain admitted that nothing else has described yet: listed under a placeholder name until its profile arrives. */
        fun stub(deviceId: String, name: String) = MemberProfileEntity(
            deviceId = deviceId, deviceName = name, lastKnownIp = "", port = DEFAULT_SHARE_PORT, publicKeyHash = "", publicKey = "", e2eeEnabled = false,
            rootPath = "/", clientVersion = "", clientVersionCode = 0, platform = "", os = "", deviceMake = "", deviceModel = "",
            supportedProtocolsJson = "[]", lastSeenEpochMs = 0L, cardPosX = null, cardPosY = null, cardSortOrder = 0, cardMenuOrder = "",
            tilePosX = null, tilePosY = null, tileSortOrder = 0, tileMenuOrder = "", tailnetHostname = "", tailnetIpv4 = "", hardwareHash = ""
        )
    }
}

private const val DEFAULT_SHARE_PORT = 8080
