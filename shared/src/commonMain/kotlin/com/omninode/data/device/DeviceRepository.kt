package com.omninode.data.device

import com.omninode.data.db.DeviceDao
import com.omninode.data.db.PairedDeviceEntity
import com.omninode.data.identity.LocalIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Identifies this node so "This device" never appears again as a paired peer.
 */
data class LocalDeviceRef(
    val deviceId: String,
    /** Usable `ip:port` endpoints for this node on the LAN. */
    val endpoints: Set<String>
) {
    companion object {
        val None = LocalDeviceRef(deviceId = "", endpoints = emptySet())
    }
}

/**
 * Single source of truth for paired-device CRUD.
 */
class DeviceRepository(
    private val deviceDao: DeviceDao,
    private val localDeviceProvider: () -> LocalDeviceRef = { LocalDeviceRef.None }
) {
    private val mutateMutex = Mutex()

    fun observeDevices(): Flow<List<PairedDeviceEntity>> =
        deviceDao.getAllDevices()
            .map { collapseAndExcludeSelf(it) }
            .distinctUntilChanged()

    suspend fun listDevices(): List<PairedDeviceEntity> =
        collapseAndExcludeSelf(deviceDao.getAllDevicesOnce())

    suspend fun getDevice(deviceId: String): PairedDeviceEntity? = deviceDao.getDevice(deviceId)

    suspend fun upsert(device: PairedDeviceEntity) {
        mutateMutex.withLock {
            val normalized = normalize(device)
            if (isLocalDevice(normalized)) {
                purgeLocalRowsLocked()
                return
            }
            val existing = deviceDao.getDevice(normalized.deviceId)
            if (existing == normalized) return
            deviceDao.upsertDevice(normalized)
        }
    }

    /**
     * Upserts [device] and collapses any alias rows that are the same physical node under a
     * different [PairedDeviceEntity.deviceId].
     *
     * Never stores this device as a paired peer (shown only as the dedicated "This device" row).
     *
     * @return true if Room was mutated.
     */
    suspend fun upsertReplacingAliases(device: PairedDeviceEntity): Boolean =
        mutateMutex.withLock {
            val normalized = normalize(device)
            if (isLocalDevice(normalized)) {
                return purgeLocalRowsLocked()
            }
            upsertReplacingAliasesLocked(normalized)
        }

    /**
     * Persist a one-shot collapse of duplicates already in Room and drop this device if present.
     */
    suspend fun reconcileDuplicateEndpoints() {
        mutateMutex.withLock {
            purgeLocalRowsLocked()
            val all = deviceDao.getAllDevicesOnce().filterNot { isLocalDevice(it) }
            if (all.size < 2) return
            persistCollapsed(all)
        }
    }

    private suspend fun upsertReplacingAliasesLocked(normalized: PairedDeviceEntity): Boolean {
        require(normalized.deviceId.isNotEmpty()) { "deviceId cannot be empty" }

        val existing = deviceDao.getDevice(normalized.deviceId)
        val aliases = findAliasesLocked(normalized)
            .filterNot { isLocalDevice(it) }
        val related = buildList {
            add(normalized)
            if (existing != null && !isLocalDevice(existing)) add(existing)
            addAll(aliases)
        }.distinctBy { it.deviceId }
            .filterNot { isLocalDevice(it) }

        if (related.isEmpty()) {
            return purgeLocalRowsLocked()
        }

        val hasUsableEndpoint = related.any { hasUsableEndpoint(it) }
        if (!hasUsableEndpoint && existing == null && aliases.isEmpty()) {
            return false
        }

        val winnerId = pickWinner(related).deviceId
        var merged = related.first { it.deviceId == winnerId }
        for (row in related) {
            merged = preferRicher(merged, row).copy(deviceId = winnerId)
        }

        val toDelete = related.map { it.deviceId }.filter { it != winnerId }.toSet()
        val currentWinner = deviceDao.getDevice(winnerId)
        val purgedSelf = purgeLocalRowsLocked()
        if (toDelete.isEmpty() && currentWinner == merged && !purgedSelf) {
            return false
        }

        for (id in toDelete) {
            deviceDao.deleteDevice(id)
        }
        if (currentWinner != merged) {
            deviceDao.upsertDevice(merged)
        }
        return true
    }

    private suspend fun persistCollapsed(all: List<PairedDeviceEntity>) {
        val collapsed = collapseAliases(all)
        val keepIds = collapsed.map { it.deviceId }.toSet()
        val byId = all.associateBy { it.deviceId }
        for (device in all) {
            if (device.deviceId !in keepIds) {
                deviceDao.deleteDevice(device.deviceId)
            }
        }
        for (keeper in collapsed) {
            if (byId[keeper.deviceId] != keeper) {
                deviceDao.upsertDevice(keeper)
            }
        }
    }

    private suspend fun findAliasesLocked(canonical: PairedDeviceEntity): List<PairedDeviceEntity> {
        val all = deviceDao.getAllDevicesOnce()
        return all.filter { other ->
            other.deviceId != canonical.deviceId && areAliases(canonical, other)
        }
    }

    private fun collapseAndExcludeSelf(devices: List<PairedDeviceEntity>): List<PairedDeviceEntity> =
        collapseAliases(devices.filterNot { isLocalDevice(it) })

    /**
     * Pure in-memory collapse used by Flows and list reads so the UI never sees
     * duplicate deviceIds / blank-IP name twins even before Room is cleaned.
     */
    private fun collapseAliases(devices: List<PairedDeviceEntity>): List<PairedDeviceEntity> {
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

    private fun pickWinner(related: List<PairedDeviceEntity>): PairedDeviceEntity =
        related.minWith(devicePreferenceOrder())

    private fun areAliases(a: PairedDeviceEntity, b: PairedDeviceEntity): Boolean {
        if (a.deviceId == b.deviceId) return true

        val endpointA = endpointKey(a.lastKnownIp, a.port)
        val endpointB = endpointKey(b.lastKnownIp, b.port)
        if (endpointA != null && endpointA == endpointB) {
            return true
        }

        val hashA = a.publicKeyHash.trim()
        val hashB = b.publicKeyHash.trim()
        if (hashA.isNotEmpty() && hashA == hashB) {
            return true
        }

        val nameA = normalizeName(a.deviceName)
        val nameB = normalizeName(b.deviceName)
        if (nameA.isNotEmpty() && nameA == nameB && (endpointA == null || endpointB == null)) {
            return true
        }

        return false
    }

    private fun preferRicher(
        existing: PairedDeviceEntity?,
        incoming: PairedDeviceEntity
    ): PairedDeviceEntity {
        if (existing == null) return incoming
        val existingOk = hasUsableEndpoint(existing)
        val incomingOk = hasUsableEndpoint(incoming)
        val primary = when {
            !existingOk && incomingOk -> incoming
            existingOk && !incomingOk -> existing
            else -> incoming
        }
        val secondary = if (primary.deviceId == incoming.deviceId) existing else incoming
        return primary.copy(
            deviceName = primary.deviceName.ifBlank { secondary.deviceName },
            lastKnownIp = when {
                hasUsableEndpoint(primary) -> primary.lastKnownIp
                hasUsableEndpoint(secondary) -> secondary.lastKnownIp
                else -> primary.lastKnownIp
            },
            port = when {
                hasUsableEndpoint(primary) -> primary.port
                hasUsableEndpoint(secondary) -> secondary.port
                else -> primary.port
            },
            publicKeyHash = primary.publicKeyHash.ifBlank { secondary.publicKeyHash },
            rootPath = primary.rootPath.ifBlank {
                secondary.rootPath.ifBlank { "/" }
            }
        )
    }

    private fun devicePreferenceOrder(): Comparator<PairedDeviceEntity> =
        compareBy<PairedDeviceEntity> { !hasUsableEndpoint(it) }
            .thenBy { it.deviceId }

    private fun hasUsableEndpoint(device: PairedDeviceEntity): Boolean =
        endpointKey(device.lastKnownIp, device.port) != null

    /**
     * True when [device] is this phone/Mac — must never appear under paired peers.
     * Matches local deviceId, the UI sentinel id, or this node's current LAN endpoint.
     */
    private fun isLocalDevice(device: PairedDeviceEntity): Boolean {
        if (device.deviceId == LocalIdentity.LOCAL_DEVICE_ID) return true
        val local = localDeviceProvider()
        if (local.deviceId.isNotBlank() && device.deviceId == local.deviceId) return true
        val endpoint = endpointKey(device.lastKnownIp, device.port) ?: return false
        return endpoint in local.endpoints
    }

    private suspend fun purgeLocalRowsLocked(): Boolean {
        val all = deviceDao.getAllDevicesOnce()
        val victims = all.filter { isLocalDevice(it) }
        if (victims.isEmpty()) return false
        for (row in victims) {
            deviceDao.deleteDevice(row.deviceId)
        }
        return true
    }

    private fun normalize(device: PairedDeviceEntity): PairedDeviceEntity =
        device.copy(
            deviceId = device.deviceId.trim(),
            deviceName = device.deviceName.trim(),
            lastKnownIp = device.lastKnownIp.trim(),
            publicKeyHash = device.publicKeyHash.trim(),
            rootPath = device.rootPath.ifBlank { "/" }
        )

    private fun normalizeName(name: String): String = name.trim().lowercase()

    private fun endpointKey(ip: String, port: Int): String? {
        val cleaned = ip.trim()
        if (cleaned.isEmpty() || cleaned == "127.0.0.1" || cleaned == "0.0.0.0") {
            return null
        }
        return "$cleaned:$port"
    }

    suspend fun rename(deviceId: String, newName: String) {
        mutateMutex.withLock {
            val trimmed = newName.trim()
            require(trimmed.isNotEmpty()) { "Device name cannot be empty" }
            val existing = deviceDao.getDevice(deviceId) ?: return
            if (existing.deviceName == trimmed) return
            deviceDao.renameDevice(deviceId, trimmed)
        }
    }

    suspend fun updateEndpoint(deviceId: String, ip: String, port: Int) {
        mutateMutex.withLock {
            val cleanedIp = ip.trim()
            val existing = deviceDao.getDevice(deviceId) ?: return
            if (existing.lastKnownIp == cleanedIp && existing.port == port) return
            deviceDao.updateEndpoint(deviceId, cleanedIp, port)
        }
    }

    suspend fun remove(deviceId: String) {
        mutateMutex.withLock {
            deviceDao.deleteDevice(deviceId)
        }
    }
}
