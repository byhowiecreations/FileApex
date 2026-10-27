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
    private val mutex: Mutex
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
     * Stores the TLS pin for [deviceId]. Only callers that received the pin over a trusted channel
     * (pairing QR, authenticated migration message, confirmed fingerprint) may use this. No merge path
     * ever copies a pin from incoming data.
     */
    suspend fun recordTlsPin(deviceId: String, pin: String, port: Int, alt: String = ""): Boolean =
        mutex.withLock {
            val trimmedId = deviceId.trim()
            val normalizedPin = pin.trim().lowercase()
            val normalizedAlt = alt.trim().lowercase()
            if (trimmedId.isEmpty() || !TLS_PIN_FORMAT.matches(normalizedPin)) return false
            if (normalizedAlt.isNotEmpty() && !TLS_PIN_FORMAT.matches(normalizedAlt)) return false
            val existing = deviceDao.getDevice(trimmedId) ?: return false
            if (existing.isRemoved) return false
            val resolvedPort = if (port in 1..65535) port else existing.tlsPort
            if (existing.tlsPin == normalizedPin && existing.tlsPinAlt == normalizedAlt &&
                existing.tlsPort == resolvedPort
            ) {
                return false
            }
            deviceDao.updateTls(trimmedId, normalizedPin, normalizedAlt, resolvedPort)
            true
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
