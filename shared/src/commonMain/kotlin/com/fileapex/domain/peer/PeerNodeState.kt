package com.fileapex.domain.peer

import kotlinx.serialization.Serializable

/**
 * Immutable atomic peer metadata payload — SSOT wire model for LAN identity broadcasts.
 *
 * [deviceId] is the immutable primary key. [deviceName] and network fields are mutable;
 * the latest payload for a given [deviceId] is strictly authoritative.
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
    val supportedProtocols: List<String> = PeerNodeProtocols.DEFAULT,
    val lastSeenTimestamp: Long = 0L,
    val rootPath: String = "/",
    val publicKeyHash: String = "",
    val pinRequired: Boolean = false,
    val downloadsPath: String = ""
) {
    val resolvedClientVersion: String
        get() = clientVersion.trim().ifBlank { appVersion.trim() }

    val resolvedClientVersionCode: Int
        get() = clientVersionCode.takeIf { it > 0 } ?: appVersionCode

    val resolvedIpAddress: String
        get() = ipAddress.trim().ifBlank { lastKnownIp.trim() }
}
