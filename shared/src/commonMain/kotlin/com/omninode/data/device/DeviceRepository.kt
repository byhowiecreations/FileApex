package com.omninode.data.device

import com.omninode.data.db.DeviceDao
import com.omninode.data.db.PairedDeviceEntity
import com.omninode.data.db.RemovedDeviceEntity
import com.omninode.data.identity.LocalIdentity
import com.omninode.domain.pairing.RemovedDeviceRecord
import com.omninode.domain.peer.PeerNodeState
import com.omninode.domain.peer.PeerNodeStateMapper
import com.omninode.util.TimeUtils
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
            if (isBlocklistedLocked(normalized)) return
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
            if (isBlocklistedLocked(normalized)) return false
            upsertReplacingAliasesLocked(normalized)
        }

    /**
     * Explicit pairing handshake — clears the removal blocklist entry and upserts the peer.
     */
    suspend fun adoptFromPairing(device: PairedDeviceEntity): Boolean =
        mutateMutex.withLock {
            val normalized = normalize(device)
            if (isLocalDevice(normalized)) {
                return purgeLocalRowsLocked()
            }
            deviceDao.clearRemovedDevice(normalized.deviceId)
            val hash = normalized.publicKeyHash.trim()
            if (hash.isNotEmpty()) {
                deviceDao.clearRemovedByPublicKeyHash(hash)
            }
            upsertReplacingAliasesLocked(normalized)
        }

    /**
     * Atomically replaces the peer record keyed by [PeerNodeState.deviceId].
     *
     * [rosterDeviceId] is the row id used to reach this peer when it differs from the
     * authoritative payload id (stale roster restore). The payload [deviceName] always wins.
     */
    suspend fun applyPeerNodeState(state: PeerNodeState, rosterDeviceId: String? = null): Boolean =
        mutateMutex.withLock {
            val existingById = deviceDao.getDevice(state.deviceId.trim())
            val rosterId = rosterDeviceId?.trim().orEmpty()
            val existingByRoster = if (rosterId.isNotEmpty()) deviceDao.getDevice(rosterId) else null
            val existing = existingById ?: existingByRoster
            val entity = PeerNodeStateMapper.toEntity(state, existing)
            val normalized = normalize(entity, existing)
            if (isLocalDevice(normalized)) {
                return purgeLocalRowsLocked()
            }
            if (isBlocklistedLocked(normalized)) return false
            replacePeerRecordAuthoritative(normalized, rosterDeviceId)
        }

    /**
     * Records a successful health probe when the full identity payload could not be fetched.
     * Preserves version metadata while extending the offline grace window.
     */
    suspend fun touchPeerLastSeen(
        deviceId: String,
        ip: String,
        port: Int,
        epochMs: Long = TimeUtils.now()
    ): Boolean = mutateMutex.withLock {
        val trimmedId = deviceId.trim()
        if (trimmedId.isEmpty()) return false
        val existing = deviceDao.getDevice(trimmedId) ?: return false
        val cleanedIp = ip.trim()
        val nextEpoch = epochMs.coerceAtLeast(existing.lastSeenEpochMs)
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
     * LAN identity probe is authoritative for [live] — replaces a stale Room row when ids diverge
     * (common after roster restore from an older database file).
     */
    suspend fun adoptLiveIdentity(staleDeviceId: String, live: PairedDeviceEntity): Boolean =
        applyPeerNodeState(PeerNodeStateMapper.fromEntity(live), staleDeviceId)

    /**
     * Permanently removes a peer from the roster and blocklists it against
     * cluster/cloud re-import until the user pairs again.
     */
    suspend fun removePermanently(deviceId: String): Boolean =
        mutateMutex.withLock {
            val trimmedId = deviceId.trim()
            if (trimmedId.isEmpty() || trimmedId == LocalIdentity.LOCAL_DEVICE_ID) {
                return false
            }
            val local = localDeviceProvider()
            if (local.deviceId.isNotBlank() && trimmedId == local.deviceId) {
                return false
            }
            val device = deviceDao.getDevice(trimmedId) ?: return false
            deviceDao.insertRemovedDevice(
                RemovedDeviceEntity(
                    deviceId = device.deviceId,
                    publicKeyHash = device.publicKeyHash.trim(),
                    lastKnownIp = device.lastKnownIp.trim(),
                    port = device.port,
                    removedAtEpochMs = TimeUtils.now()
                )
            )
            deviceDao.deleteDevice(trimmedId)
            true
        }

    /**
     * Apply a removal event from a cluster peer or cloud snapshot.
     * Blocklists the peer even when it is not currently in the local roster.
     */
    suspend fun applyRemoteRemoval(record: RemovedDeviceRecord): Boolean =
        mutateMutex.withLock {
            val trimmedId = record.deviceId.trim()
            if (trimmedId.isEmpty() || trimmedId == LocalIdentity.LOCAL_DEVICE_ID) {
                return false
            }
            val local = localDeviceProvider()
            if (local.deviceId.isNotBlank() && trimmedId == local.deviceId) {
                return false
            }
            val existing = deviceDao.getDevice(trimmedId)
            val hash = record.publicKeyHash.trim().ifBlank { existing?.publicKeyHash?.trim().orEmpty() }
            deviceDao.insertRemovedDevice(
                RemovedDeviceEntity(
                    deviceId = trimmedId,
                    publicKeyHash = hash,
                    lastKnownIp = record.lastKnownIp.trim().ifBlank { existing?.lastKnownIp?.trim().orEmpty() },
                    port = record.port.takeIf { it > 0 } ?: existing?.port ?: 0,
                    removedAtEpochMs = TimeUtils.now()
                )
            )
            if (existing != null) {
                deviceDao.deleteDevice(trimmedId)
            }
            if (hash.isNotEmpty()) {
                deviceDao.getAllDevicesOnce()
                    .filter { row -> row.publicKeyHash.trim() == hash && row.deviceId != trimmedId }
                    .forEach { row -> deviceDao.deleteDevice(row.deviceId) }
            }
            true
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

    /**
     * Authoritative ingestion for live [PeerNodeState] payloads — never re-keys via alias merge.
     */
    private suspend fun replacePeerRecordAuthoritative(
        normalized: PairedDeviceEntity,
        rosterDeviceId: String?
    ): Boolean {
        require(normalized.deviceId.isNotEmpty()) { "deviceId cannot be empty" }
        val rosterId = rosterDeviceId?.trim().orEmpty()
        val endpoint = endpointKey(normalized.lastKnownIp, normalized.port)
        for (row in deviceDao.getAllDevicesOnce()) {
            if (row.deviceId == normalized.deviceId) continue
            val sameEndpoint = endpoint != null && endpointKey(row.lastKnownIp, row.port) == endpoint
            val staleRoster = rosterId.isNotEmpty() && row.deviceId == rosterId
            if (sameEndpoint || staleRoster) {
                deviceDao.deleteDevice(row.deviceId)
            }
        }
        val purgedSelf = purgeLocalRowsLocked()
        val existing = deviceDao.getDevice(normalized.deviceId)
        val merged = normalize(normalized, existing)
        if (existing == merged && !purgedSelf) {
            return false
        }
        deviceDao.upsertDevice(merged)
        return true
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
            deviceName = incoming.deviceName.trim().ifBlank { primary.deviceName.ifBlank { secondary.deviceName } },
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
            },
            clientVersion = incoming.clientVersion.ifBlank { primary.clientVersion.ifBlank { secondary.clientVersion } },
            clientVersionCode = incoming.clientVersionCode.takeIf { it > 0 }
                ?: primary.clientVersionCode.takeIf { it > 0 }
                ?: secondary.clientVersionCode,
            platform = incoming.platform.ifBlank { primary.platform.ifBlank { secondary.platform } },
            supportedProtocolsJson = incoming.supportedProtocolsJson.ifBlank {
                primary.supportedProtocolsJson.ifBlank { secondary.supportedProtocolsJson }
            },
            lastSeenEpochMs = maxOf(
                incoming.lastSeenEpochMs,
                primary.lastSeenEpochMs,
                secondary.lastSeenEpochMs
            )
        )
    }

    private fun devicePreferenceOrder(): Comparator<PairedDeviceEntity> =
        compareBy<PairedDeviceEntity> { !hasUsableEndpoint(it) }
            .thenByDescending { it.lastSeenEpochMs }
            .thenByDescending { it.clientVersion.isNotBlank() }
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

    private fun normalize(
        device: PairedDeviceEntity,
        preserveFrom: PairedDeviceEntity? = null
    ): PairedDeviceEntity {
        val trimmed = device.copy(
            deviceId = device.deviceId.trim(),
            deviceName = device.deviceName.trim(),
            lastKnownIp = device.lastKnownIp.trim(),
            publicKeyHash = device.publicKeyHash.trim(),
            rootPath = device.rootPath.ifBlank { "/" },
            clientVersion = device.clientVersion.trim(),
            platform = device.platform.trim(),
            supportedProtocolsJson = device.supportedProtocolsJson.ifBlank { "[]" },
            lastSeenEpochMs = device.lastSeenEpochMs.coerceAtLeast(0L)
        )
        return trimmed.copy(
            clientVersion = trimmed.clientVersion.ifBlank { preserveFrom?.clientVersion.orEmpty() },
            clientVersionCode = trimmed.clientVersionCode.takeIf { it > 0 }
                ?: preserveFrom?.clientVersionCode
                ?: 0,
            platform = trimmed.platform.ifBlank { preserveFrom?.platform.orEmpty() }
        )
    }

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
        removePermanently(deviceId)
    }

    suspend fun isBlocklisted(device: PairedDeviceEntity): Boolean =
        mutateMutex.withLock { isBlocklistedLocked(device) }

    private suspend fun isBlocklistedLocked(device: PairedDeviceEntity): Boolean {
        if (deviceDao.countRemovedById(device.deviceId) > 0) return true
        val hash = device.publicKeyHash.trim()
        if (hash.isNotEmpty() && deviceDao.countRemovedByPublicKeyHash(hash) > 0) {
            return true
        }
        return false
    }
}
