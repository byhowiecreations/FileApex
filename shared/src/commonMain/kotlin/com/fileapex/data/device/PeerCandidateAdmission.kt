package com.fileapex.data.device

import com.fileapex.data.db.getAllDevicesOnce
import com.fileapex.data.db.DeviceDao
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.domain.peer.ClusterClock
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.domain.peer.PeerNodeStateMapper
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the candidate and removal rules need from the roster; [DeviceRepository] supplies it so the lock and the clock stay in one place. */
internal interface RosterAccess {
    suspend fun seedClockLocked()
    suspend fun tombstoneVersionLocked(deviceId: String, publicKeyHash: String): Long
    suspend fun tombstoneBlocksLocked(device: PairedDeviceEntity, canReinstate: Boolean): Boolean
    suspend fun writeKeepingTlsTrust(device: PairedDeviceEntity, clearTls: Boolean = false)
    suspend fun aliasesLocked(canonical: PairedDeviceEntity): List<PairedDeviceEntity>
}

/**
 * Peers learned from anyone but the user's own pairing wait here as unverified candidates until this device
 * reaches them directly. Every call that changes state takes the repository's roster lock.
 */
internal class PeerCandidateAdmission(
    private val deviceDao: DeviceDao,
    private val candidates: PeerCandidateStore?,
    private val mutex: Mutex,
    private val localDevice: () -> LocalDeviceRef,
    private val roster: RosterAccess
) {
    private val recentlyDropped = HashMap<String, Long>()

    /** Holds [device] as unverified. It does not enter the roster and is never shared with anyone. */
    suspend fun stage(
        device: PairedDeviceEntity,
        introducedBy: String,
        origin: CandidateOrigin
    ): Boolean = mutex.withLock {
        roster.seedClockLocked()
        stageLocked(RosterMerge.normalize(device), introducedBy, origin)
    }

    suspend fun stageLocked(
        normalized: PairedDeviceEntity,
        introducedBy: String,
        origin: CandidateOrigin
    ): Boolean {
        val store = candidates ?: return false
        val id = normalized.deviceId
        if (id.isEmpty() || id == LocalIdentity.LOCAL_DEVICE_ID || RosterMerge.isLocalDevice(normalized, localDevice()) || normalized.isRemoved) {
            return false
        }
        val existing = deviceDao.getDevice(id)
        if (existing != null && !existing.isRemoved) return false
        val now = TimeUtils.now()
        recentlyDropped[id]?.let { droppedAt ->
            if (now - droppedAt < DROPPED_RETRY_BLOCK_MS) return false
            recentlyDropped.remove(id)
        }
        val version = normalized.clusterVersion
        val vouched = origin == CandidateOrigin.INTRODUCED || origin == CandidateOrigin.TRANSPORT
        if (vouched && !ClusterClock.isAcceptable(version)) return false
        val hash = normalized.publicKeyHash.trim()
        val tombstone = roster.tombstoneVersionLocked(id, hash)
        if (tombstone > 0L && !(version > tombstone && ClusterClock.isAcceptable(version))) return false
        val previous = store.get(id)
        val staged = normalized.copy(
            lastSeenEpochMs = 0L,
            isRemoved = false,
            removedAt = null,
            tlsPin = "",
            tlsPinAlt = ""
        )
        store.put(
            PeerCandidate(
                device = staged,
                introducedBy = introducedBy,
                origin = origin,
                introducedAtEpochMs = previous?.introducedAtEpochMs ?: now,
                expiresAtEpochMs = previous?.expiresAtEpochMs ?: (now + CANDIDATE_TTL_MS),
                attempts = previous?.attempts ?: 0,
                lastAttemptEpochMs = previous?.lastAttemptEpochMs ?: 0L
            )
        )
        return previous == null
    }

    suspend fun recordAttempt(deviceId: String) {
        val store = candidates ?: return
        mutex.withLock {
            val current = store.get(deviceId.trim()) ?: return
            store.put(current.copy(attempts = current.attempts + 1, lastAttemptEpochMs = TimeUtils.now()))
        }
    }

    suspend fun drop(deviceId: String, remember: Boolean = false) {
        val store = candidates ?: return
        mutex.withLock {
            store.remove(deviceId.trim())
            if (remember) recentlyDropped[deviceId.trim()] = TimeUtils.now()
        }
    }

    suspend fun forgetDropped() {
        mutex.withLock { recentlyDropped.clear() }
    }

    /** Removes candidates past their deadline and refuses the same introduction again for a while. */
    suspend fun expire(): Int {
        val store = candidates ?: return 0
        return mutex.withLock {
            val now = TimeUtils.now()
            val expired = store.all().filter { it.expiresAtEpochMs <= now }
            expired.forEach { recentlyDropped[it.deviceId] = now }
            store.removeExpired(now)
            expired.size
        }
    }

    /**
     * Adds a verified candidate to the roster. [live] is what the peer itself returned when this device
     * contacted it, so presence and version come from that contact and not from whoever introduced it.
     */
    suspend fun promote(candidate: PeerCandidate, live: PeerNodeState): Boolean =
        mutex.withLock {
            val store = candidates ?: return false
            roster.seedClockLocked()
            val liveVersion = live.membershipVersion.takeIf { live.hasMembershipProtocol && ClusterClock.isAcceptable(it) }
            val version = liveVersion ?: candidate.device.clusterVersion
            val entity = RosterMerge.normalize(
                PeerNodeStateMapper.toEntity(live, candidate.device).copy(
                    clusterVersion = version,
                    lastSeenEpochMs = TimeUtils.now(),
                    isRemoved = false,
                    removedAt = null
                ),
                candidate.device
            )
            store.remove(candidate.deviceId)
            if (RosterMerge.isLocalDevice(entity, localDevice())) return false
            if (roster.tombstoneBlocksLocked(entity, canReinstate = true)) return false
            ClusterClock.observe(version)
            roster.writeKeepingTlsTrust(entity)
            true
        }

    /**
     * A stored address answered as another device. A row that was never reached directly is an outdated
     * copy and goes back to unverified; one that was reached before only loses its stale address.
     */
    suspend fun recordEndpointMismatch(deviceId: String, host: String, answeredAs: String): Boolean =
        mutex.withLock {
            val id = deviceId.trim()
            val row = deviceDao.getDevice(id) ?: return false
            if (row.isRemoved || id == answeredAs.trim()) return false
            val store = candidates
            if (row.lastSeenEpochMs == 0L && store != null) {
                deviceDao.deleteDevice(id)
                stageLocked(row, "health-check", CandidateOrigin.LOCAL)
                return true
            }
            if (row.lastKnownIp.trim() == host.trim()) {
                deviceDao.updateEndpoint(id, "", row.port)
                return true
            }
            false
        }

    /**
     * Startup migration: rows this device never reached directly were added on someone's word. They are
     * held as unverified until a direct contact confirms them.
     */
    suspend fun demoteUnverifiedRows(): Int = mutex.withLock {
        val store = candidates ?: return 0
        roster.seedClockLocked()
        val now = TimeUtils.now()
        var moved = 0
        for (row in deviceDao.getAllDevicesOnce()) {
            if (RosterMerge.isLocalDevice(row, localDevice()) || row.lastSeenEpochMs > 0L) continue
            if (row.clusterVersion > 0L && now - row.clusterVersion < NEW_ROW_GRACE_MS) continue
            deviceDao.deleteDevice(row.deviceId)
            if (stageLocked(row, "migration", CandidateOrigin.LOCAL)) moved++
        }
        moved
    }

    private companion object {
        const val DROPPED_RETRY_BLOCK_MS = 24L * 60L * 60L * 1000L
        const val NEW_ROW_GRACE_MS = 10L * 60L * 1000L
    }
}
