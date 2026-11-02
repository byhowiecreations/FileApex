package com.fileapex.data.device

import com.fileapex.data.db.getAllDevicesIncludingTombstones
import com.fileapex.data.db.DeviceDao
import com.fileapex.i18n.AppI18n
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Small updates to a peer's row that follow from contact with it: last seen, address, tailnet address,
 * TLS pin, bound key and name. None of them reads or writes any other row.
 */
internal class PeerContactRecords(
    private val deviceDao: DeviceDao,
    private val mutex: Mutex,
    private val pins: TlsPinStore
) {
    /**
     * Records a successful health probe when the full identity payload could not be fetched.
     * Preserves version metadata while extending the offline grace window.
     */
    suspend fun touchPeerLastSeen(
        deviceId: String,
        ip: String,
        port: Int,
        epochMs: Long = TimeUtils.now()
    ): Boolean = mutex.withLock {
        val trimmedId = deviceId.trim()
        if (trimmedId.isEmpty()) return false
        val existing = deviceDao.getDevice(trimmedId) ?: return false
        if (existing.isRemoved) return false
        val cleanedIp = ip.trim()
        val nextEpoch = epochMs.coerceAtLeast(existing.lastSeenEpochMs)
        if (PeerEndpointRules.isLoopbackOrUnspecified(cleanedIp)) {
            // The peer answered through a loopback bridge: it was seen, but that is not its address.
            deviceDao.touchLastSeenEpoch(trimmedId, nextEpoch)
            return true
        }
        if (existing.lastSeenEpochMs == nextEpoch &&
            existing.lastKnownIp == cleanedIp &&
            existing.port == port
        ) {
            return false
        }
        deviceDao.touchLastSeen(trimmedId, cleanedIp, port, nextEpoch)
        true
    }

    /**
     * A profile that came with the chain: names, address, ports and keys of a device this device already holds as an
     * active member. Only profile columns are written; a relayed profile never supplies presence, never evicts a usable
     * address with one from another subnet, and never touches membership. [ownProfile] is true when the device sent it
     * about itself; a relayed copy may fill a placeholder name but never replaces a name already held, or a stale name
     * spread by one member would undo a rename on every device.
     */
    suspend fun applyChainProfile(state: com.fileapex.domain.peer.PeerNodeState, ownProfile: Boolean): Boolean {
        val id = state.deviceId.trim()
        if (id.isEmpty()) return false
        val changed = mutex.withLock {
            val existing = deviceDao.getDevice(id)?.takeUnless { it.isRemoved } ?: return@withLock null
            val mapped = com.fileapex.domain.peer.PeerNodeStateMapper.toEntity(state, existing)
            val incoming = if (ownProfile || DeviceDisplayNames.isPlaceholder(existing.deviceName)) mapped else mapped.copy(deviceName = existing.deviceName)
            val keepEndpoint = RosterMerge.hasUsableEndpoint(existing) && incoming.lastKnownIp != existing.lastKnownIp &&
                !com.fileapex.util.NetworkUtils.sameIpv4Slash24(incoming.lastKnownIp, existing.lastKnownIp)
            val next = RosterMerge.normalize(
                incoming.copy(
                    lastSeenEpochMs = existing.lastSeenEpochMs,
                    lastKnownIp = if (keepEndpoint) existing.lastKnownIp else incoming.lastKnownIp,
                    port = if (keepEndpoint) existing.port else incoming.port,
                    clusterVersion = existing.clusterVersion,
                    supportedProtocolsJson = RosterMerge.knownProtocols(incoming.supportedProtocolsJson, existing.supportedProtocolsJson)
                ),
                existing
            )
            if (next.copy(isRemoved = existing.isRemoved, removedAt = existing.removedAt, tlsPin = existing.tlsPin, tlsPinAlt = existing.tlsPinAlt, tlsPort = existing.tlsPort) != existing) {
                deviceDao.upsertProfile(next)
            }
            true
        } ?: return false
        if (state.tlsPort in 1..65535) pins.recordPort(id, state.tlsPort)
        return changed
    }

    /** Extends the online window without replacing the LAN address. Used for tailnet probes. */
    suspend fun touchPeerLastSeenEpoch(
        deviceId: String,
        epochMs: Long = TimeUtils.now()
    ): Boolean = mutex.withLock {
        val trimmedId = deviceId.trim()
        if (trimmedId.isEmpty()) return false
        val existing = deviceDao.getDevice(trimmedId) ?: return false
        if (existing.isRemoved) return false
        val nextEpoch = epochMs.coerceAtLeast(existing.lastSeenEpochMs)
        if (existing.lastSeenEpochMs == nextEpoch) return false
        deviceDao.touchLastSeenEpoch(trimmedId, nextEpoch)
        true
    }

    /** Stores a tailnet address only after /api/v1/identity matched [deviceId]. */
    suspend fun recordVerifiedTailnet(
        deviceId: String,
        hostname: String,
        ipv4: String
    ): Boolean = mutex.withLock {
        val trimmedId = deviceId.trim()
        val host = hostname.trim()
        val ip = ipv4.trim()
        if (trimmedId.isEmpty() || host.isEmpty() || !com.fileapex.tailscale.isTailscaleIPv4(ip)) {
            return false
        }
        val existing = deviceDao.getDevice(trimmedId) ?: return false
        if (existing.isRemoved) return false
        if (existing.tailnetHostname == host && existing.tailnetIpv4 == ip) return false
        deviceDao.updateTailnet(trimmedId, host, ip)
        true
    }

    /**
     * Hands the TLS pin for [deviceId] to the pin store. Only callers that received the pin over a trusted channel
     * (pairing QR, authenticated migration message, confirmed fingerprint) may use this. No merge path
     * ever copies a pin from incoming data.
     */
    suspend fun recordTlsPin(deviceId: String, pin: String, port: Int, alt: String = "", rejoin: Boolean = false, userConfirmed: Boolean = false): Boolean =
        mutex.withLock {
            val trimmedId = deviceId.trim()
            if (trimmedId.isEmpty()) return false
            val existing = deviceDao.getDevice(trimmedId) ?: return false
            if (existing.isRemoved && !rejoin) return false
            pins.record(trimmedId, pin, port, alt, rejoin, userConfirmed)
        }

    /** Only a key the id is derived from is accepted, and a stored key is never replaced. */
    suspend fun recordBoundPublicKey(deviceId: String, publicKey: String): Boolean =
        mutex.withLock {
            val trimmedId = deviceId.trim()
            val key = publicKey.trim()
            if (trimmedId.isEmpty() || !com.fileapex.security.IdentityBinding.matches(trimmedId, key)) return false
            val existing = deviceDao.getDevice(trimmedId) ?: return false
            if (existing.isRemoved || existing.publicKey.isNotBlank()) return false
            deviceDao.upsertDevice(
                existing.copy(
                    publicKey = key,
                    publicKeyHash = existing.publicKeyHash.ifBlank { com.fileapex.security.IdentityBinding.keyHash(key) }
                )
            )
            true
        }

    suspend fun clearTailnet(deviceId: String): Boolean = mutex.withLock {
        val trimmedId = deviceId.trim()
        if (trimmedId.isEmpty()) return false
        val existing = deviceDao.getDevice(trimmedId) ?: return false
        if (existing.tailnetHostname.isEmpty() && existing.tailnetIpv4.isEmpty()) return false
        deviceDao.updateTailnet(trimmedId, "", "")
        true
    }

    suspend fun rename(deviceId: String, newName: String) {
        mutex.withLock {
            val trimmed = newName.trim()
            require(trimmed.isNotEmpty()) { AppI18n.t("device_name_empty") }
            val existing = deviceDao.getDevice(deviceId) ?: return
            if (existing.deviceName == trimmed) return
            deviceDao.renameDevice(deviceId, trimmed)
        }
    }

    suspend fun updateEndpoint(deviceId: String, ip: String, port: Int) {
        mutex.withLock {
            val cleanedIp = ip.trim()
            if (PeerEndpointRules.isLoopbackOrUnspecified(cleanedIp)) return
            val existing = deviceDao.getDevice(deviceId) ?: return
            if (existing.lastKnownIp == cleanedIp && existing.port == port) return
            deviceDao.updateEndpoint(deviceId, cleanedIp, port)
        }
    }

    /** Repairs rows that stored a loopback bridge endpoint as the peer's address. Returns how many were fixed. */
    suspend fun clearLoopbackEndpoints(): Int = mutex.withLock {
        val broken = deviceDao.getAllDevicesIncludingTombstones()
            .filter { PeerEndpointRules.isLoopbackOrUnspecified(it.lastKnownIp) }
        broken.forEach { deviceDao.updateEndpoint(it.deviceId, "", it.port) }
        broken.size
    }

    private companion object {
        val TLS_PIN_FORMAT = Regex("[0-9a-f]{64}")
    }
}
