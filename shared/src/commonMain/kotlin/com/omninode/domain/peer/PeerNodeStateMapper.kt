package com.omninode.domain.peer

import com.omninode.data.db.PairedDeviceEntity
import com.omninode.data.identity.LocalDeviceNameStore
import com.omninode.data.identity.LocalIdentity
import com.omninode.cloud.currentPlatformLabel
import com.omninode.platform.defaultDownloadsDir
import com.omninode.update.currentAppVersionCode
import com.omninode.update.currentAppVersionName
import com.omninode.util.DeviceIdentityMarkers
import com.omninode.util.NetworkUtils
import com.omninode.util.TimeUtils
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Maps between Room storage and the atomic [PeerNodeState] wire model.
 */
object PeerNodeStateMapper {
    private val protocolsJson = Json { encodeDefaults = true }

    fun selfState(
        identity: LocalIdentity,
        deviceName: String = LocalDeviceNameStore.current().ifBlank { identity.deviceName },
        pinRequired: Boolean = false,
        lastSeenTimestamp: Long = TimeUtils.now()
    ): PeerNodeState {
        return PeerNodeState(
            deviceId = identity.deviceId,
            deviceName = deviceName.trim(),
            ipAddress = NetworkUtils.preferredLanIpv4(),
            port = identity.sharePort,
            clientVersion = currentAppVersionName(),
            clientVersionCode = currentAppVersionCode(),
            platform = currentPlatformLabel(),
            supportedProtocols = PeerNodeProtocols.DEFAULT,
            lastSeenTimestamp = lastSeenTimestamp,
            rootPath = identity.rootPath,
            publicKeyHash = DeviceIdentityMarkers.fingerprint(identity.deviceId),
            pinRequired = pinRequired,
            downloadsPath = defaultDownloadsDir()
        )
    }

    fun toEntity(state: PeerNodeState, existing: PairedDeviceEntity? = null): PairedDeviceEntity {
        val deviceId = state.deviceId.trim()
        require(deviceId.isNotEmpty()) { "PeerNodeState.deviceId cannot be empty" }
        val name = state.deviceName.trim().ifBlank { existing?.deviceName.orEmpty() }
        return PairedDeviceEntity(
            deviceId = deviceId,
            deviceName = name.ifBlank { "Paired device" },
            lastKnownIp = state.resolvedIpAddress.ifBlank { existing?.lastKnownIp.orEmpty() },
            port = state.port.takeIf { it > 0 } ?: existing?.port ?: 0,
            publicKeyHash = state.publicKeyHash.trim().ifBlank { existing?.publicKeyHash.orEmpty() },
            rootPath = state.rootPath.ifBlank { existing?.rootPath?.ifBlank { "/" } ?: "/" },
            clientVersion = state.resolvedClientVersion.ifBlank { existing?.clientVersion.orEmpty() },
            clientVersionCode = state.resolvedClientVersionCode.takeIf { it > 0 }
                ?: existing?.clientVersionCode
                ?: 0,
            platform = state.platform.trim().ifBlank { existing?.platform.orEmpty() },
            supportedProtocolsJson = encodeProtocols(
                state.supportedProtocols.ifEmpty { PeerNodeProtocols.DEFAULT }
            ),
            lastSeenEpochMs = state.lastSeenTimestamp.takeIf { it > 0L }
                ?: existing?.lastSeenEpochMs
                ?: 0L
        )
    }

    fun fromEntity(entity: PairedDeviceEntity): PeerNodeState {
        return PeerNodeState(
            deviceId = entity.deviceId,
            deviceName = entity.deviceName,
            ipAddress = entity.lastKnownIp,
            port = entity.port,
            clientVersion = entity.clientVersion,
            clientVersionCode = entity.clientVersionCode,
            platform = entity.platform,
            supportedProtocols = decodeProtocols(entity.supportedProtocolsJson),
            lastSeenTimestamp = entity.lastSeenEpochMs,
            rootPath = entity.rootPath,
            publicKeyHash = entity.publicKeyHash
        )
    }

    fun encodeProtocols(protocols: List<String>): String =
        protocolsJson.encodeToString(protocols.distinct())

    fun decodeProtocols(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return PeerNodeProtocols.DEFAULT
        return runCatching { protocolsJson.decodeFromString<List<String>>(trimmed) }
            .getOrDefault(PeerNodeProtocols.DEFAULT)
    }
}
