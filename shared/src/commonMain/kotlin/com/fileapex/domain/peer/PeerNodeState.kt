package com.fileapex.domain.peer

import kotlinx.serialization.Serializable

/**
 * Peer metadata payload for LAN identity broadcasts.
 *
 * [deviceId] is the immutable primary key. [deviceName] and network fields are mutable;
 * the latest payload for a given [deviceId] wins.
 */
@Serializable
data class PeerNodeState(
    val deviceId: String,
    val deviceName: String,
    val ipAddress: String = "",
    /** Legacy wire field — read only for backward compatibility. */
    val lastKnownIp: String = "",
    val port: Int,
    val clientVersion: String = "",
    /** Legacy wire field — read only for backward compatibility. */
    val appVersion: String = "",
    val clientVersionCode: Int = 0,
    /** Legacy wire field — read only for backward compatibility. */
    val appVersionCode: Int = 0,
    val platform: String = "",
    /** OS slug: android, macos, windows, linux. */
    val os: String = "",
    val deviceMake: String = "",
    val deviceModel: String = "",
    val supportedProtocols: List<String> = PeerNodeProtocols.DEFAULT,
    val lastSeenTimestamp: Long = 0L,
    val rootPath: String = "/",
    val publicKeyHash: String = "",
    val publicKey: String = "",
    val pinRequired: Boolean = false,
    val downloadsPath: String = "",
    /** Legacy wall-clock version kept for builds before 0.14.3a; ignored when [membershipProtocol] is set. */
    val clusterVersion: Long = 0L,
    val isRemoved: Boolean = false,
    val removedAt: Long? = null,
    /** [ClusterClock] stamp of this device's latest pairing. */
    val membershipVersion: Long = 0L,
    val membershipProtocol: Int = 0
) {
    val hasMembershipProtocol: Boolean
        get() = membershipProtocol >= ClusterClock.MEMBERSHIP_PROTOCOL

    val resolvedClientVersion: String
        get() = clientVersion.trim().ifBlank { appVersion.trim() }

    val resolvedClientVersionCode: Int
        get() = clientVersionCode.takeIf { it > 0 } ?: appVersionCode

    val resolvedIpAddress: String
        get() = ipAddress.trim().ifBlank { lastKnownIp.trim() }
}
