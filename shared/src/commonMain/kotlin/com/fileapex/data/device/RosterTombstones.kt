package com.fileapex.data.device

import com.fileapex.data.db.getAllDevicesOnce
import com.fileapex.data.db.DeviceDao
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.db.RemovedDeviceEntity
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.domain.pairing.RemovedDeviceRecord
import com.fileapex.domain.peer.ClusterClock
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val TOMBSTONE_GOSSIP_MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L
private const val FINGERPRINT_CACHE_MS = 30_000L

internal fun PairedDeviceEntity.membershipVersion(): Long = maxOf(clusterVersion, removedAt ?: 0L)

private fun PairedDeviceEntity.asTombstone(version: Long, publicKeyHash: String): PairedDeviceEntity =
    copy(
        isRemoved = true,
        removedAt = version,
        clusterVersion = version,
        publicKey = "",
        publicKeyHash = publicKeyHash,
        e2eeEnabled = false
    )

/**
 * Removals, the tombstone index and the rules that stop a removed peer coming back. Every call that changes
 * state takes the repository's roster lock; the index is also read without it on the request gate.
 */
internal class RosterTombstones(
    private val deviceDao: DeviceDao,
    private val mutex: Mutex,
    private val localDevice: () -> LocalDeviceRef,
    private val membershipStore: MembershipStore,
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

    private suspend fun reinstateLocked(deviceId: String, publicKeyHash: String, version: Long) {
        clearTombstonesLocked(deviceId, publicKeyHash)
        val row = deviceDao.getDevice(deviceId)
        if (row?.isRemoved == true) {
            roster.writeKeepingTlsTrust(row.copy(isRemoved = false, removedAt = null, clusterVersion = version))
        }
        ClusterClock.observe(version)
    }

    /**
     * True when a tombstone outranks [device]. With [canReinstate], a strictly newer plausible
     * [PairedDeviceEntity.clusterVersion] lifts the tombstone instead.
     */
    suspend fun tombstoneBlocksLocked(device: PairedDeviceEntity, canReinstate: Boolean): Boolean {
        val trimmedId = device.deviceId.trim()
        if (trimmedId.isEmpty() || trimmedId == LocalIdentity.LOCAL_DEVICE_ID) return false
        val local = localDevice()
        if (local.deviceId.isNotBlank() && trimmedId == local.deviceId) return false
        val hash = device.publicKeyHash.trim().ifBlank {
            deviceDao.getDevice(trimmedId)?.publicKeyHash?.trim().orEmpty()
        }
        val tombstoneVersion = tombstoneIndexLocked().versionFor(trimmedId, hash)
        if (tombstoneVersion <= 0L) return false
        val incoming = device.clusterVersion
        if (canReinstate && incoming > tombstoneVersion && ClusterClock.isAcceptable(incoming)) {
            reinstateLocked(trimmedId, hash, incoming)
            return false
        }
        return true
    }

    /** The removal records inside the fingerprint window (see RemovalFingerprint). */
    suspend fun windowedRemovals(): List<RemovedDeviceRecord> =
        com.fileapex.domain.pairing.RemovalFingerprint.windowed(getTombstoneRecords(), TimeUtils.now())

    @Volatile
    private var fingerprintCache: Pair<Long, String>? = null

    /** The fingerprint of [windowedRemovals], kept for 30 seconds so a busy health route does not read the database each time. */
    suspend fun removalFingerprint(): String {
        val now = TimeUtils.now()
        fingerprintCache?.takeIf { now - it.first < FINGERPRINT_CACHE_MS }?.let { return it.second }
        val value = com.fileapex.domain.pairing.RemovalFingerprint.of(windowedRemovals())
        fingerprintCache = now to value
        return value
    }

    /**
     * Permanently removes a peer from the roster and blocklists it against
     * cluster/cloud re-import until the user pairs again.
     */
    suspend fun removePermanently(deviceId: String): Boolean =
        mutex.withLock {
            roster.seedClockLocked()
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
            val now = ClusterClock.next()
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
                roster.writeKeepingTlsTrust(tombstone, clearTls = true)
                deviceDao.insertRemovedDevice(
                    RemovedDeviceEntity(
                        deviceId = victim.deviceId,
                        publicKeyHash = victim.publicKeyHash.trim(),
                        lastKnownIp = victim.lastKnownIp.trim(),
                        port = victim.port,
                        removedAtEpochMs = now
                    )
                )
            }
            true
        }

    /**
     * Apply a removal stamped by another node. Ignored unless it carries the current membership
     * protocol and is strictly newer than every version held for that device, so echoes of old
     * removals and legacy re-stamps can never evict a re-paired peer.
     *
     * @return true when applied, the only case in which it should be forwarded.
     */
    suspend fun applyRemoteRemoval(record: RemovedDeviceRecord): Boolean =
        mutex.withLock {
            roster.seedClockLocked()
            val trimmedId = record.deviceId.trim()
            if (trimmedId.isEmpty() || trimmedId == LocalIdentity.LOCAL_DEVICE_ID) {
                return false
            }
            val local = localDevice()
            if (local.deviceId.isNotBlank() && trimmedId == local.deviceId) {
                return false
            }
            if (record.membershipProtocol < ClusterClock.MEMBERSHIP_PROTOCOL) return false
            val version = record.membershipVersion()
            if (!ClusterClock.isAcceptable(version)) return false

            val existing = deviceDao.getDevice(trimmedId)
            val hash = record.publicKeyHash.trim().ifBlank { existing?.publicKeyHash?.trim().orEmpty() }
            val known = maxOf(
                existing?.membershipVersion() ?: 0L,
                tombstoneIndexLocked().versionFor(trimmedId, hash)
            )
            if (version <= known) return false
            ClusterClock.observe(version)

            if (existing != null) {
                roster.writeKeepingTlsTrust(existing.asTombstone(version, hash), clearTls = true)
            }
            deviceDao.insertRemovedDevice(
                RemovedDeviceEntity(
                    deviceId = trimmedId,
                    publicKeyHash = hash,
                    lastKnownIp = record.lastKnownIp.trim().ifBlank { existing?.lastKnownIp?.trim().orEmpty() },
                    port = record.port.takeIf { it > 0 } ?: existing?.port ?: 0,
                    removedAtEpochMs = version
                )
            )
            val aliases = if (existing != null) {
                roster.aliasesLocked(existing)
            } else if (hash.isNotEmpty()) {
                deviceDao.getAllDevicesOnce().filter { row ->
                    row.publicKeyHash.trim() == hash && row.deviceId != trimmedId
                }
            } else {
                emptyList()
            }
            aliases.filter { it.membershipVersion() < version }.forEach { row ->
                val aliasHash = row.publicKeyHash.trim().ifBlank { hash }
                roster.writeKeepingTlsTrust(row.asTombstone(version, aliasHash), clearTls = true)
                deviceDao.insertRemovedDevice(
                    RemovedDeviceEntity(
                        deviceId = row.deviceId,
                        publicKeyHash = row.publicKeyHash.trim(),
                        lastKnownIp = row.lastKnownIp.trim(),
                        port = row.port,
                        removedAtEpochMs = version
                    )
                )
            }
            tombstoneIndex = null
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
            if (deviceDao.countRemovedById(ghost.deviceId) == 0) {
                deviceDao.insertRemovedDevice(
                    RemovedDeviceEntity(
                        deviceId = ghost.deviceId,
                        publicKeyHash = ghost.publicKeyHash.trim(),
                        lastKnownIp = ghost.lastKnownIp.trim(),
                        port = ghost.port,
                        removedAtEpochMs = ghost.membershipVersion().coerceAtLeast(1L)
                    )
                )
            }
            deviceDao.deleteDevice(ghost.deviceId)
        }
        tombstoneIndex = null
    }

    suspend fun isBlocklisted(device: PairedDeviceEntity): Boolean =
        mutex.withLock { tombstoneBlocksLocked(device, canReinstate = false) }

    /**
     * Request gate. [membershipVersion] is the caller's own [ClusterClock] stamp (`mv`); a stamp
     * strictly newer than our tombstone means the caller re-paired after the removal, so the row
     * is reinstated. Reads the in-memory tombstone index without the mutex on the hot path.
     */
    suspend fun isDeviceIdRevoked(deviceId: String, membershipVersion: Long = 0L): Boolean {
        val trimmed = deviceId.trim()
        if (trimmed.isEmpty() || trimmed == LocalIdentity.LOCAL_DEVICE_ID) return false
        val local = localDevice()
        if (local.deviceId.isNotBlank() && trimmed == local.deviceId) return false
        val index = tombstoneIndex ?: mutex.withLock { tombstoneIndexLocked() }
        val hash = deviceDao.getDevice(trimmed)?.publicKeyHash?.trim().orEmpty()
        val tombstoneVersion = index.versionFor(trimmed, hash)
        if (tombstoneVersion <= 0L) return false
        if (membershipVersion <= tombstoneVersion || !ClusterClock.isAcceptable(membershipVersion)) return true
        mutex.withLock {
            val current = tombstoneIndexLocked().versionFor(trimmed, hash)
            if (current > 0L && membershipVersion > current) {
                reinstateLocked(trimmed, hash, membershipVersion)
            }
        }
        return false
    }

    /** The stamp of the tombstone that outranks [deviceId], or 0 when none does. */
    suspend fun revokedVersion(deviceId: String): Long {
        val trimmed = deviceId.trim()
        val hash = deviceDao.getDevice(trimmed)?.publicKeyHash?.trim().orEmpty()
        return (tombstoneIndex ?: mutex.withLock { tombstoneIndexLocked() }).versionFor(trimmed, hash)
    }

    /**
     * Removals to seed a newly paired peer with. Only stamps made since this node ran the current
     * membership protocol are marked as such; older ones may be legacy re-stamps and stay advisory.
     */
    suspend fun getTombstoneRecords(maxAgeMs: Long = TOMBSTONE_GOSSIP_MAX_AGE_MS): List<RemovedDeviceRecord> =
        mutex.withLock {
            roster.seedClockLocked()
            val since = TimeUtils.now() - maxAgeMs
            val protocolSince = membershipStore.protocolSince
            val byId = LinkedHashMap<String, RemovedDeviceRecord>()
            fun add(record: RemovedDeviceRecord) {
                val current = byId[record.deviceId]
                if (current == null || record.membershipVersion() > current.membershipVersion()) {
                    byId[record.deviceId] = record.copy(
                        publicKeyHash = record.publicKeyHash.ifBlank { current?.publicKeyHash.orEmpty() }
                    )
                } else if (current.publicKeyHash.isBlank() && record.publicKeyHash.isNotBlank()) {
                    byId[record.deviceId] = current.copy(publicKeyHash = record.publicKeyHash)
                }
            }
            fun protocolFor(version: Long): Int =
                if (protocolSince > 0L && version >= protocolSince) ClusterClock.MEMBERSHIP_PROTOCOL else 0
            for (entity in deviceDao.getTombstonedDevices()) {
                val version = entity.membershipVersion()
                add(
                    RemovedDeviceRecord(
                        deviceId = entity.deviceId,
                        publicKeyHash = entity.publicKeyHash,
                        lastKnownIp = entity.lastKnownIp,
                        port = entity.port,
                        clusterVersion = version,
                        removedAt = version,
                        membershipProtocol = protocolFor(version)
                    )
                )
            }
            for (entity in deviceDao.getAllRemovedDevices()) {
                add(
                    RemovedDeviceRecord(
                        deviceId = entity.deviceId,
                        publicKeyHash = entity.publicKeyHash,
                        lastKnownIp = entity.lastKnownIp,
                        port = entity.port,
                        clusterVersion = entity.removedAtEpochMs,
                        removedAt = entity.removedAtEpochMs,
                        membershipProtocol = protocolFor(entity.removedAtEpochMs)
                    )
                )
            }
            byId.values.filter { it.membershipVersion() >= since }
        }
}
