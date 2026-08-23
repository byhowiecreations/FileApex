package com.fileapex.domain.peer

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.DeviceDisplayNames
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.platform.defaultDownloadsDir
import com.fileapex.platform.localDeviceHardwareProfile
import com.fileapex.update.currentAppVersionCode
import com.fileapex.update.currentAppVersionName
import com.fileapex.util.DeviceIdentityMarkers
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
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
        val advertiseIp = NetworkUtils.lanBindCandidates().firstOrNull()
            ?: NetworkUtils.preferredLanIpv4()
        val hardware = localDeviceHardwareProfile()
        return PeerNodeState(
            deviceId = identity.deviceId,
            deviceName = deviceName.trim(),
            ipAddress = advertiseIp,
            port = identity.sharePort,
            clientVersion = currentAppVersionName(),
            clientVersionCode = currentAppVersionCode(),
            platform = hardware.platform.ifBlank { currentPlatformLabel() },
            os = hardware.os,
            deviceMake = hardware.deviceMake,
            deviceModel = hardware.deviceModel,
            supportedProtocols = PeerNodeProtocols.DEFAULT,
            lastSeenTimestamp = lastSeenTimestamp,
            rootPath = identity.rootPath,
            publicKeyHash = DeviceIdentityMarkers.fingerprint(identity.deviceId),
            publicKey = com.fileapex.domain.clipboard.ClipboardE2ee.publicKeyBase64(),
            pinRequired = pinRequired,
            downloadsPath = defaultDownloadsDir()
        )
    }

    fun toEntity(state: PeerNodeState, existing: PairedDeviceEntity? = null): PairedDeviceEntity {
        val deviceId = state.deviceId.trim()
        require(deviceId.isNotEmpty()) { "PeerNodeState.deviceId cannot be empty" }
        val make = state.deviceMake.trim().ifBlank { existing?.deviceMake.orEmpty() }
        val model = state.deviceModel.trim().ifBlank { existing?.deviceModel.orEmpty() }
        val name = DeviceDisplayNames.merge(
            existingName = existing?.deviceName.orEmpty(),
            incomingName = state.deviceName,
            make = make,
            model = model
        )
        return PairedDeviceEntity(
            deviceId = deviceId,
            deviceName = name.ifBlank { "Paired device" },
            lastKnownIp = state.resolvedIpAddress.ifBlank { existing?.lastKnownIp.orEmpty() },
            port = state.port.takeIf { it > 0 } ?: existing?.port ?: 0,
            publicKeyHash = state.publicKeyHash.trim().ifBlank { existing?.publicKeyHash.orEmpty() },
            publicKey = state.publicKey.trim().ifBlank { existing?.publicKey.orEmpty() },
            e2eeEnabled = state.publicKey.trim().isNotEmpty() || existing?.e2eeEnabled == true,
            rootPath = state.rootPath.ifBlank { existing?.rootPath?.ifBlank { "/" } ?: "/" },
            clientVersion = state.resolvedClientVersion.ifBlank { existing?.clientVersion.orEmpty() },
            clientVersionCode = state.resolvedClientVersionCode.takeIf { it > 0 }
                ?: existing?.clientVersionCode
                ?: 0,
            platform = state.platform.trim().ifBlank { existing?.platform.orEmpty() },
            os = state.os.trim().ifBlank { existing?.os.orEmpty() },
            deviceMake = state.deviceMake.trim().ifBlank { existing?.deviceMake.orEmpty() },
            deviceModel = state.deviceModel.trim().ifBlank { existing?.deviceModel.orEmpty() },
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
            os = entity.os,
            deviceMake = entity.deviceMake,
            deviceModel = entity.deviceModel,
            supportedProtocols = decodeProtocols(entity.supportedProtocolsJson),
            lastSeenTimestamp = entity.lastSeenEpochMs,
            rootPath = entity.rootPath,
            publicKeyHash = entity.publicKeyHash,
            publicKey = entity.publicKey
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
