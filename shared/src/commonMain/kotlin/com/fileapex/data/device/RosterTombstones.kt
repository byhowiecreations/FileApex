package com.fileapex.data.device

import com.fileapex.data.db.getAllDevicesOnce
import com.fileapex.data.db.DeviceDao
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.db.RemovedDeviceEntity
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun PairedDeviceEntity.membershipVersion(): Long = maxOf(clusterVersion, removedAt ?: 0L)

/**
 * Local blocklist for removed peers. Membership itself is the signed chain; a tombstone here
 * only stops profile/cloud re-import until this device pairs again or the chain re-admits.
 */
internal class RosterTombstones(
    private val deviceDao: DeviceDao,
    private val mutex: Mutex,
    private val localDevice: () -> LocalDeviceRef,
    private val roster: RosterAccess
) {
    @Volatile
    private var tombstoneIndex: TombstoneIndex? = null

    internal class TombstoneIndex(
        private val byId: Map<String, Long>,
        private val byHash: Map<String, Long>
    ) {
        fun versionFor(deviceId: String, publicKeyHash: String): Long =
            maxOf(byId[deviceId] ?: 0L, publicKeyHash.takeIf { it.isNotEmpty() }?.let { byHash[it] } ?: 0L)
    }

    fun invalidate() {
        tombstoneIndex = null
    }

    suspend fun tombstoneIndexLocked(): TombstoneIndex {
        tombstoneIndex?.let { return it }
        val byId = HashMap<String, Long>()
        val byHash = HashMap<String, Long>()
        for (row in deviceDao.getAllRemovedDevices()) {
            val version = row.removedAtEpochMs.coerceAtLeast(1L)
            byId.merge(row.deviceId.trim(), version, ::maxOf)
            row.publicKeyHash.trim().takeIf { it.isNotEmpty() }?.let { byHash.merge(it, version, ::maxOf) }
        }
        for (row in deviceDao.getTombstonedDevices()) {
            byId.merge(row.deviceId.trim(), row.membershipVersion().coerceAtLeast(1L), ::maxOf)
        }
        return TombstoneIndex(byId, byHash).also { tombstoneIndex = it }
    }

    suspend fun clearTombstonesLocked(deviceId: String, publicKeyHash: String) {
        deviceDao.clearRemovedDevice(deviceId)
        if (publicKeyHash.isNotEmpty()) {
            deviceDao.clearRemovedByPublicKeyHash(publicKeyHash)
        }
        tombstoneIndex = null
    }

    /** True when a tombstone blocks [device]. Pairing on this device clears first; a claimed stamp never lifts it. */
    suspend fun tombstoneBlocksLocked(device: PairedDeviceEntity): Boolean {
        val trimmedId = device.deviceId.trim()
        if (trimmedId.isEmpty() || trimmedId == LocalIdentity.LOCAL_DEVICE_ID) return false
        val local = localDevice()
        if (local.deviceId.isNotBlank() && trimmedId == local.deviceId) return false
        val hash = device.publicKeyHash.trim().ifBlank {
            deviceDao.getDevice(trimmedId)?.publicKeyHash?.trim().orEmpty()
        }
        return tombstoneIndexLocked().versionFor(trimmedId, hash) > 0L
    }

    /**
     * Permanently removes a peer from the roster and blocklists it against
     * profile/cloud re-import until the user pairs again.
     */
    suspend fun removePermanently(deviceId: String, reason: String = "", removedBy: String = ""): Boolean =
        mutex.withLock {
            val trimmedId = deviceId.trim()
            if (trimmedId.isEmpty() || trimmedId == LocalIdentity.LOCAL_DEVICE_ID) {
                return false
            }
            val local = localDevice()
            if (local.deviceId.isNotBlank() && trimmedId == local.deviceId) {
                return false
            }
            val device = deviceDao.getDevice(trimmedId) ?: return false
            val hash = device.publicKeyHash.trim()
            val victims = if (hash.isNotEmpty()) {
                deviceDao.getAllDevicesOnce().filter { it.deviceId == trimmedId || it.publicKeyHash.trim() == hash }
            } else {
                listOf(device)
            }
            val now = TimeUtils.now()
            tombstoneIndex = null
            for (victim in victims) {
                val tombstone = victim.copy(
                    isRemoved = true,
                    removedAt = now,
                    clusterVersion = now,
                    publicKey = "",
                    publicKeyHash = "",
                    e2eeEnabled = false
                )
                roster.writeDevice(tombstone)
                deviceDao.insertRemovedDevice(
                    RemovedDeviceEntity(
                        deviceId = victim.deviceId,
                        publicKeyHash = victim.publicKeyHash.trim(),
                        lastKnownIp = victim.lastKnownIp.trim(),
                        port = victim.port,
                        removedAtEpochMs = now,
                        reason = reason,
                        removedBy = removedBy
                    )
                )
            }
            true
        }

    /**
     * Removals of peers never seen locally used to leave blank paired rows; the blocklist entry
     * in removed_devices is all that is needed.
     */
    suspend fun compactNamelessTombstonesLocked() {
        val ghosts = deviceDao.getTombstonedDevices().filter { it.deviceName.isBlank() }
        if (ghosts.isEmpty()) return
        for (ghost in ghosts) {
            deviceDao.insertRemovedDevice(
                RemovedDeviceEntity(
                    deviceId = ghost.deviceId,
                    publicKeyHash = ghost.publicKeyHash.trim(),
                    lastKnownIp = ghost.lastKnownIp.trim(),
                    port = ghost.port,
                    removedAtEpochMs = ghost.membershipVersion().coerceAtLeast(1L)
                )
            )
            deviceDao.deleteDevice(ghost.deviceId)
        }
        tombstoneIndex = null
    }

    suspend fun isBlocklisted(device: PairedDeviceEntity): Boolean =
        mutex.withLock { tombstoneBlocksLocked(device) }

    /**
     * Request gate. A tombstone stays until a signed chain ADMIT or a pairing handshake on this
     * device. A claimed membership version on the request is not authority.
     */
    suspend fun isDeviceIdRevoked(deviceId: String, membershipVersion: Long = 0L): Boolean {
        val trimmed = deviceId.trim()
        if (trimmed.isEmpty() || trimmed == LocalIdentity.LOCAL_DEVICE_ID) return false
        val local = localDevice()
        if (local.deviceId.isNotBlank() && trimmed == local.deviceId) return false
        val index = tombstoneIndex ?: mutex.withLock { tombstoneIndexLocked() }
        val hash = deviceDao.getDevice(trimmed)?.publicKeyHash?.trim().orEmpty()
        return index.versionFor(trimmed, hash) > 0L
    }

    /** Why [deviceId] was removed and by which device, empty when the removal did not say. */
    suspend fun revokedDetail(deviceId: String): RemovalDetail {
        val trimmed = deviceId.trim()
        val hash = deviceDao.getDevice(trimmed)?.publicKeyHash?.trim().orEmpty()
        val row = deviceDao.getAllRemovedDevices()
            .filter { it.deviceId == trimmed || (hash.isNotEmpty() && it.publicKeyHash == hash) }
            .maxByOrNull { it.removedAtEpochMs } ?: return RemovalDetail()
        return RemovalDetail(row.reason, row.removedBy)
    }

    /** The stamp of the tombstone that outranks [deviceId], or 0 when none does. */
    suspend fun revokedVersion(deviceId: String): Long {
        val trimmed = deviceId.trim()
        val hash = deviceDao.getDevice(trimmed)?.publicKeyHash?.trim().orEmpty()
        return (tombstoneIndex ?: mutex.withLock { tombstoneIndexLocked() }).versionFor(trimmed, hash)
    }
}

/** Why a device was removed and who removed it; both empty when the removal did not say. */
data class RemovalDetail(val reason: String = "", val removedBy: String = "")
