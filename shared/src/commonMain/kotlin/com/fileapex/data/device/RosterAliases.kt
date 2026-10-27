package com.fileapex.data.device

import com.fileapex.data.db.getAllDevicesOnce
import com.fileapex.data.db.DeviceDao
import com.fileapex.data.db.PairedDeviceEntity

/**
 * The rules that keep one row per physical device: replacing a peer's row by its id, collapsing rows that are
 * aliases of each other, and dropping this device from its own roster. Callers hold the roster lock.
 */
internal class RosterAliases(
    private val deviceDao: DeviceDao,
    private val localDevice: () -> LocalDeviceRef,
    private val roster: RosterAccess
) {
    /**
     * Live [PeerNodeState] ingest keyed by payload [deviceId]; does not re-key via alias merge.
     */
    suspend fun replacePeerRecordByDeviceId(
        normalized: PairedDeviceEntity,
        rosterDeviceId: String?
    ): Boolean {
        require(normalized.deviceId.isNotEmpty()) { "deviceId cannot be empty" }
        val rosterId = rosterDeviceId?.trim().orEmpty()
        val endpoint = RosterMerge.endpointKey(normalized.lastKnownIp, normalized.port)
        for (row in deviceDao.getAllDevicesOnce()) {
            if (row.deviceId == normalized.deviceId) continue
            val sameEndpoint = endpoint != null && RosterMerge.endpointKey(row.lastKnownIp, row.port) == endpoint
            val sameDevice = RosterIdentity.samePhysicalDevice(
                incomingId = normalized.deviceId,
                incomingPublicKeyHash = normalized.publicKeyHash,
                otherId = row.deviceId,
                otherPublicKeyHash = row.publicKeyHash,
                staleRosterId = rosterId
            )
            when {
                RosterIdentity.shouldDetachStolenEndpoint(sameEndpoint, sameDevice) -> {
                    deviceDao.updateEndpoint(row.deviceId, "", row.port)
                }
                sameDevice && (sameEndpoint || row.deviceId == rosterId) -> {
                    deviceDao.deleteDevice(row.deviceId)
                }
            }
        }
        val purgedSelf = purgeLocalRowsLocked()
        val existing = deviceDao.getDevice(normalized.deviceId)
        val merged = RosterMerge.normalize(normalized, existing)
        if (existing == merged && !purgedSelf) {
            return false
        }
        roster.writeKeepingTlsTrust(merged)
        return true
    }

    suspend fun upsertReplacingAliasesLocked(normalized: PairedDeviceEntity): Boolean {
        require(normalized.deviceId.isNotEmpty()) { "deviceId cannot be empty" }

        val existing = deviceDao.getDevice(normalized.deviceId)
        val aliases = findAliasesLocked(normalized)
            .filterNot { RosterMerge.isLocalDevice(it, localDevice()) }
        val related = buildList {
            add(normalized)
            if (existing != null && !RosterMerge.isLocalDevice(existing, localDevice())) add(existing)
            addAll(aliases)
        }.distinctBy { it.deviceId }
            .filterNot { RosterMerge.isLocalDevice(it, localDevice()) }

        if (related.isEmpty()) {
            return purgeLocalRowsLocked()
        }

        val hasUsableEndpoint = related.any { RosterMerge.hasUsableEndpoint(it) }
        if (!hasUsableEndpoint && existing == null && aliases.isEmpty()) {
            return false
        }

        val winnerId = RosterMerge.pickWinner(related).deviceId
        var merged = related.first { it.deviceId == winnerId }
        for (row in related) {
            merged = RosterMerge.preferRicher(merged, row).copy(deviceId = winnerId)
        }
        merged = merged.copy(supportedProtocolsJson = RosterMerge.knownProtocols(merged.supportedProtocolsJson, existing?.supportedProtocolsJson)) // distinctBy dropped `existing`

        val toDelete = related.map { it.deviceId }.filter { it != winnerId }.toSet()
        val currentWinner = deviceDao.getDevice(winnerId)
        val purgedSelf = purgeLocalRowsLocked()
        if (toDelete.isEmpty() && currentWinner == merged && !purgedSelf) {
            return false
        }

        if (currentWinner != merged) roster.writeKeepingTlsTrust(merged) // survivor first: the list never misses it
        for (id in toDelete) {
            deviceDao.deleteDevice(id)
        }
        return true
    }

    suspend fun persistCollapsed(all: List<PairedDeviceEntity>) {
        val collapsed = RosterMerge.collapseAliases(all)
        val keepIds = collapsed.map { it.deviceId }.toSet()
        val byId = all.associateBy { it.deviceId }
        for (keeper in collapsed) {
            if (byId[keeper.deviceId] != keeper) {
                roster.writeKeepingTlsTrust(keeper)
            }
        }
        for (device in all) {
            if (device.deviceId !in keepIds) {
                deviceDao.deleteDevice(device.deviceId)
            }
        }
    }

    suspend fun findAliasesLocked(canonical: PairedDeviceEntity): List<PairedDeviceEntity> {
        val all = deviceDao.getAllDevicesOnce()
        return all.filter { other ->
            other.deviceId != canonical.deviceId && RosterMerge.areAliases(canonical, other)
        }
    }

    fun collapseAndExcludeSelf(devices: List<PairedDeviceEntity>): List<PairedDeviceEntity> {
        val local = localDevice()
        return RosterMerge.collapseAliases(devices.filterNot { RosterMerge.isLocalDevice(it, local) })
    }

    suspend fun purgeLocalRowsLocked(): Boolean {
        val all = deviceDao.getAllDevicesOnce()
        val local = localDevice()
        val victims = all.filter { RosterMerge.isLocalDevice(it, local) }
        if (victims.isEmpty()) return false
        for (row in victims) {
            deviceDao.deleteDevice(row.deviceId)
        }
        return true
    }
}
