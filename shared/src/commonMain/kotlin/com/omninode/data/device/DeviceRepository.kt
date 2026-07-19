package com.omninode.data.device

import com.omninode.data.db.DeviceDao
import com.omninode.data.db.PairedDeviceEntity
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for paired-device CRUD.
 */
class DeviceRepository(private val deviceDao: DeviceDao) {
    fun observeDevices(): Flow<List<PairedDeviceEntity>> = deviceDao.getAllDevices()

    suspend fun listDevices(): List<PairedDeviceEntity> = deviceDao.getAllDevicesOnce()

    suspend fun getDevice(deviceId: String): PairedDeviceEntity? = deviceDao.getDevice(deviceId)

    suspend fun upsert(device: PairedDeviceEntity) {
        deviceDao.upsertDevice(device)
    }

    /**
     * Upserts [device] and removes stale rows that are clearly the same physical node under a
     * different [PairedDeviceEntity.deviceId] (e.g. pre-cloud pairing id + Firestore id, or
     * reinstall). Match by LAN endpoint and/or non-empty publicKeyHash.
     */
    suspend fun upsertReplacingAliases(device: PairedDeviceEntity) {
        val normalized = device.copy(
            deviceId = device.deviceId.trim(),
            deviceName = device.deviceName.trim(),
            lastKnownIp = device.lastKnownIp.trim(),
            publicKeyHash = device.publicKeyHash.trim(),
            rootPath = device.rootPath.ifBlank { "/" }
        )
        require(normalized.deviceId.isNotEmpty()) { "deviceId cannot be empty" }

        for (alias in findAliases(normalized)) {
            if (alias.deviceId != normalized.deviceId) {
                deviceDao.deleteDevice(alias.deviceId)
            }
        }
        deviceDao.upsertDevice(normalized)
    }

    /**
     * Collapse any existing duplicate rows in Room (same LAN endpoint or fingerprint).
     * Prefer keeping the lexicographically stable id so repeated calls are idempotent when
     * neither side has a preferred identity; callers that know the canonical id should use
     * [upsertReplacingAliases] instead.
     */
    suspend fun reconcileDuplicateEndpoints() {
        val all = deviceDao.getAllDevicesOnce()
        val seenEndpoints = mutableMapOf<String, PairedDeviceEntity>()
        val seenHashes = mutableMapOf<String, PairedDeviceEntity>()
        val toDelete = linkedSetOf<String>()

        for (device in all.sortedBy { it.deviceId }) {
            val endpointKey = endpointKey(device.lastKnownIp, device.port)
            if (endpointKey != null) {
                val prior = seenEndpoints[endpointKey]
                if (prior != null) {
                    toDelete += device.deviceId
                    continue
                }
                seenEndpoints[endpointKey] = device
            }
            val hash = device.publicKeyHash.trim()
            if (hash.isNotEmpty()) {
                val priorHash = seenHashes[hash]
                if (priorHash != null && priorHash.deviceId != device.deviceId) {
                    toDelete += device.deviceId
                    continue
                }
                seenHashes[hash] = device
            }
        }

        for (id in toDelete) {
            deviceDao.deleteDevice(id)
        }
    }

    private suspend fun findAliases(canonical: PairedDeviceEntity): List<PairedDeviceEntity> {
        val all = deviceDao.getAllDevicesOnce()
        val endpointKey = endpointKey(canonical.lastKnownIp, canonical.port)
        val hash = canonical.publicKeyHash.trim()
        return all.filter { other ->
            if (other.deviceId == canonical.deviceId) {
                false
            } else {
                val sameEndpoint = endpointKey != null &&
                    endpointKey(other.lastKnownIp, other.port) == endpointKey
                val sameHash = hash.isNotEmpty() && other.publicKeyHash.trim() == hash
                sameEndpoint || sameHash
            }
        }
    }

    private fun endpointKey(ip: String, port: Int): String? {
        val cleaned = ip.trim()
        if (cleaned.isEmpty() || cleaned == "127.0.0.1" || cleaned == "0.0.0.0") {
            return null
        }
        return "$cleaned:$port"
    }

    suspend fun rename(deviceId: String, newName: String) {
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { "Device name cannot be empty" }
        deviceDao.renameDevice(deviceId, trimmed)
    }

    suspend fun updateEndpoint(deviceId: String, ip: String, port: Int) {
        deviceDao.updateEndpoint(deviceId, ip, port)
    }

    suspend fun remove(deviceId: String) {
        deviceDao.deleteDevice(deviceId)
    }
}
