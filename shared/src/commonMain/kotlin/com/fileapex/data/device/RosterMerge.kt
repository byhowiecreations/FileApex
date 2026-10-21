package com.fileapex.data.device

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.LocalIdentity

/**
 * Pure roster merge rules: which rows are the same device, which one wins, and how two rows combine. No state and
 * no lock; [DeviceRepository] calls these while it holds its own lock.
 */
internal object RosterMerge {
    /**
     * Pure in-memory collapse used by Flows and list reads so the UI never sees
     * duplicate deviceIds / blank-IP name twins even before Room is cleaned.
     */
    internal fun collapseAliases(devices: List<PairedDeviceEntity>): List<PairedDeviceEntity> {
        if (devices.size < 2) return devices
        val kept = mutableListOf<PairedDeviceEntity>()
        for (device in devices.sortedWith(devicePreferenceOrder())) {
            val rivalIndex = kept.indexOfFirst { areAliases(it, device) }
            if (rivalIndex < 0) {
                if (kept.any { it.deviceId == device.deviceId }) continue
                kept += device
                continue
            }
            val rival = kept[rivalIndex]
            val winner = pickWinner(listOf(rival, device))
            val merged = preferRicher(rival, device).copy(deviceId = winner.deviceId)
            kept[rivalIndex] = merged
        }
        return kept.sortedBy { it.deviceName.lowercase() }
    }

    internal fun pickWinner(related: List<PairedDeviceEntity>): PairedDeviceEntity =
        related.maxWith(devicePreferenceOrder())

    internal fun areAliases(a: PairedDeviceEntity, b: PairedDeviceEntity): Boolean {
        if (a.deviceId == b.deviceId) return true

        val hashA = a.publicKeyHash.trim()
        val hashB = b.publicKeyHash.trim()
        if (hashA.isNotEmpty() && hashA == hashB) {
            return true
        }

        if (hashA.isNotEmpty() && hashB.isNotEmpty()) return false

        val endpointA = endpointKey(a.lastKnownIp, a.port)
        val endpointB = endpointKey(b.lastKnownIp, b.port)
        val nameA = normalizeName(a.deviceName)
        val nameB = normalizeName(b.deviceName)
        if (nameA.isNotEmpty() && nameA == nameB && (endpointA == null || endpointB == null)) {
            return true
        }

        return false
    }

    internal fun preferRicher(
        existing: PairedDeviceEntity?,
        incoming: PairedDeviceEntity
    ): PairedDeviceEntity {
        if (existing == null) return incoming
        val existingOk = hasUsableEndpoint(existing)
        val incomingOk = hasUsableEndpoint(incoming)
        val endpointSource = pickEndpointSource(existing, incoming)
        val primary = when {
            !existingOk && incomingOk -> incoming
            existingOk && !incomingOk -> existing
            else -> incoming
        }
        val secondary = if (primary.deviceId == incoming.deviceId) existing else incoming
        return primary.copy(
            deviceName = DeviceDisplayNames.merge(
                existingName = existing.deviceName.ifBlank { primary.deviceName.ifBlank { secondary.deviceName } },
                incomingName = incoming.deviceName,
                make = firstNonBlank(incoming.deviceMake, primary.deviceMake, secondary.deviceMake),
                model = firstNonBlank(incoming.deviceModel, primary.deviceModel, secondary.deviceModel)
            ),
            lastKnownIp = when {
                hasUsableEndpoint(endpointSource) -> endpointSource.lastKnownIp
                hasUsableEndpoint(secondary) -> secondary.lastKnownIp
                else -> primary.lastKnownIp
            },
            port = when {
                hasUsableEndpoint(endpointSource) -> endpointSource.port
                hasUsableEndpoint(secondary) -> secondary.port
                else -> primary.port
            },
            publicKeyHash = primary.publicKeyHash.ifBlank { secondary.publicKeyHash },
            publicKey = firstNonBlank(incoming.publicKey, primary.publicKey, secondary.publicKey),
            e2eeEnabled = incoming.e2eeEnabled || primary.e2eeEnabled || secondary.e2eeEnabled,
            rootPath = primary.rootPath.ifBlank {
                secondary.rootPath.ifBlank { "/" }
            },
            clientVersion = firstNonBlank(
                incoming.clientVersion,
                primary.clientVersion,
                secondary.clientVersion
            ),
            clientVersionCode = firstPositive(
                incoming.clientVersionCode,
                primary.clientVersionCode,
                secondary.clientVersionCode
            ),
            platform = firstNonBlank(incoming.platform, primary.platform, secondary.platform),
            os = firstNonBlank(incoming.os, primary.os, secondary.os),
            deviceMake = firstNonBlank(incoming.deviceMake, primary.deviceMake, secondary.deviceMake),
            deviceModel = firstNonBlank(incoming.deviceModel, primary.deviceModel, secondary.deviceModel),
            supportedProtocolsJson = incoming.supportedProtocolsJson.ifBlank {
                primary.supportedProtocolsJson.ifBlank { secondary.supportedProtocolsJson }
            },
            lastSeenEpochMs = maxOf(
                incoming.lastSeenEpochMs,
                primary.lastSeenEpochMs,
                secondary.lastSeenEpochMs
            ),
            cardPosX = primary.cardPosX ?: secondary.cardPosX,
            cardPosY = primary.cardPosY ?: secondary.cardPosY,
            cardSortOrder = if (primary.cardSortOrder != 0) primary.cardSortOrder else secondary.cardSortOrder,
            cardMenuOrder = primary.cardMenuOrder.ifBlank { secondary.cardMenuOrder },
            tilePosX = primary.tilePosX ?: secondary.tilePosX,
            tilePosY = primary.tilePosY ?: secondary.tilePosY,
            tileSortOrder = if (primary.tileSortOrder != 0) primary.tileSortOrder else secondary.tileSortOrder,
            tileMenuOrder = primary.tileMenuOrder.ifBlank { secondary.tileMenuOrder },
            clusterVersion = maxOf(incoming.clusterVersion, primary.clusterVersion, secondary.clusterVersion),
            isRemoved = existing.isRemoved && incoming.isRemoved,
            removedAt = if (existing.isRemoved && incoming.isRemoved) primary.removedAt ?: secondary.removedAt else null,
            tailnetHostname = firstNonBlank(
                incoming.tailnetHostname,
                primary.tailnetHostname,
                secondary.tailnetHostname
            ),
            tailnetIpv4 = listOf(incoming.tailnetIpv4, primary.tailnetIpv4, secondary.tailnetIpv4)
                .firstOrNull { com.fileapex.tailscale.isTailscaleIPv4(it.trim()) }
                ?.trim()
                .orEmpty()
        )
    }

    internal fun devicePreferenceOrder(): Comparator<PairedDeviceEntity> =
        compareBy<PairedDeviceEntity> { hasUsableEndpoint(it) }
            .thenBy { it.lastSeenEpochMs }
            .thenBy { it.clientVersion.isNotBlank() }
            .thenBy { it.deviceId }

    internal fun hasUsableEndpoint(device: PairedDeviceEntity): Boolean =
        endpointKey(device.lastKnownIp, device.port) != null

    /**
     * Prefer the endpoint observed most recently on the LAN. Cloud snapshots with
     * [PairedDeviceEntity.lastSeenEpochMs] = 0 must not overwrite a fresher local probe.
     */
    internal fun pickEndpointSource(
        existing: PairedDeviceEntity?,
        incoming: PairedDeviceEntity
    ): PairedDeviceEntity {
        if (existing == null) return incoming
        val existingOk = hasUsableEndpoint(existing)
        val incomingOk = hasUsableEndpoint(incoming)
        return when {
            !existingOk && incomingOk -> incoming
            existingOk && !incomingOk -> existing
            !existingOk && !incomingOk -> incoming
            existing.lastSeenEpochMs > incoming.lastSeenEpochMs -> existing
            incoming.lastSeenEpochMs > existing.lastSeenEpochMs -> incoming
            existing.lastSeenEpochMs == 0L && incoming.lastSeenEpochMs == 0L -> incoming
            else -> existing
        }
    }

    /**
     * True when [device] is this phone/Mac and must never appear under paired peers.
     * Matches local deviceId, the UI sentinel id, or this node's current LAN endpoint.
     */
    internal fun isLocalDevice(
        device: PairedDeviceEntity,
        local: LocalDeviceRef
    ): Boolean {
        if (device.deviceId == LocalIdentity.LOCAL_DEVICE_ID) return true
        if (local.deviceId.isNotBlank() && device.deviceId == local.deviceId) return true
        val endpoint = endpointKey(device.lastKnownIp, device.port) ?: return false
        return endpoint in local.endpoints
    }

    internal fun normalize(
        device: PairedDeviceEntity,
        preserveFrom: PairedDeviceEntity? = null
    ): PairedDeviceEntity {
        val platformStr = device.platform.trim().ifBlank { preserveFrom?.platform.orEmpty() }
        val isAndroid = platformStr.trim().equals("android", ignoreCase = true)
        val defaultRoot = if (isAndroid) "/storage/emulated/0" else "/"
        val preservedRoot = preserveFrom?.rootPath?.trim()?.takeIf { it.isNotEmpty() && it != "/" }
        val rootPathCandidate = device.rootPath.trim().takeIf { it.isNotEmpty() && it != "/" }
            ?: preservedRoot
            ?: defaultRoot
        val trimmed = device.copy(
            deviceId = device.deviceId.trim(),
            deviceName = device.deviceName.trim(),
            lastKnownIp = device.lastKnownIp.trim(),
            publicKeyHash = device.publicKeyHash.trim(),
            rootPath = rootPathCandidate,
            clientVersion = device.clientVersion.trim(),
            platform = platformStr,
            os = device.os.trim(),
            deviceMake = device.deviceMake.trim(),
            deviceModel = device.deviceModel.trim(),
            supportedProtocolsJson = device.supportedProtocolsJson.ifBlank { "[]" },
            lastSeenEpochMs = maxOf(
                device.lastSeenEpochMs.coerceAtLeast(0L),
                preserveFrom?.lastSeenEpochMs?.coerceAtLeast(0L) ?: 0L
            ),
            tlsPin = preserveFrom?.tlsPin.orEmpty(),
            tlsPinAlt = preserveFrom?.tlsPinAlt.orEmpty(),
            tlsPort = device.tlsPort.takeIf { it in 1..65535 } ?: preserveFrom?.tlsPort ?: 0,
            tailnetHostname = device.tailnetHostname.trim().ifBlank { preserveFrom?.tailnetHostname.orEmpty() },
            tailnetIpv4 = device.tailnetIpv4.trim().let { incoming ->
                if (com.fileapex.tailscale.isTailscaleIPv4(incoming)) {
                    incoming
                } else {
                    preserveFrom?.tailnetIpv4?.trim().orEmpty()
                        .takeIf { com.fileapex.tailscale.isTailscaleIPv4(it) }
                        .orEmpty()
                }
            }
        )
        return trimmed.copy(
            lastKnownIp = trimmed.lastKnownIp.ifBlank { preserveFrom?.lastKnownIp.orEmpty() },
            port = trimmed.port.takeIf { it > 0 } ?: preserveFrom?.port ?: 0,
            rootPath = trimmed.rootPath.takeIf { it.isNotEmpty() && it != "/" } ?: preservedRoot ?: defaultRoot,
            clientVersion = trimmed.clientVersion.ifBlank { preserveFrom?.clientVersion.orEmpty() },
            clientVersionCode = trimmed.clientVersionCode.takeIf { it > 0 }
                ?: preserveFrom?.clientVersionCode
                ?: 0,
            platform = platformStr,
            os = trimmed.os.ifBlank { preserveFrom?.os.orEmpty() },
            deviceMake = trimmed.deviceMake.ifBlank { preserveFrom?.deviceMake.orEmpty() },
            deviceModel = trimmed.deviceModel.ifBlank { preserveFrom?.deviceModel.orEmpty() },
            // Where a device sits is this device's own layout. What a peer sends is only a first placement, never a replacement.
            cardPosX = preserveFrom?.cardPosX ?: trimmed.cardPosX,
            cardPosY = preserveFrom?.cardPosY ?: trimmed.cardPosY,
            cardSortOrder = preserveFrom?.cardSortOrder?.takeIf { it != 0 } ?: trimmed.cardSortOrder,
            cardMenuOrder = preserveFrom?.cardMenuOrder?.takeIf { it.isNotBlank() } ?: trimmed.cardMenuOrder,
            tilePosX = preserveFrom?.tilePosX ?: trimmed.tilePosX,
            tilePosY = preserveFrom?.tilePosY ?: trimmed.tilePosY,
            tileSortOrder = preserveFrom?.tileSortOrder?.takeIf { it != 0 } ?: trimmed.tileSortOrder,
            tileMenuOrder = preserveFrom?.tileMenuOrder?.takeIf { it.isNotBlank() } ?: trimmed.tileMenuOrder
        )
    }

    internal fun normalizeName(name: String): String = name.trim().lowercase()

    internal fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.trim().isNotEmpty() }?.trim().orEmpty()

    internal fun firstPositive(vararg values: Int): Int =
        values.firstOrNull { it > 0 } ?: 0

    internal fun endpointKey(ip: String, port: Int): String? {
        val cleaned = ip.trim()
        if (cleaned.isEmpty() || cleaned == "127.0.0.1" || cleaned == "0.0.0.0") {
            return null
        }
        return "$cleaned:$port"
    }
}
