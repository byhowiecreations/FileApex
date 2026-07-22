package com.omninode.domain.presence

import com.omninode.data.db.PairedDeviceEntity
import com.omninode.data.device.DeviceRepository
import com.omninode.domain.presence.LanPresenceTiming
import com.omninode.domain.transfer.MultiCopyDeviceOption
import com.omninode.network.OmniNodeClient
import com.omninode.network.sendWakeBroadcast
import com.omninode.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Passive peer reachability from inbound LAN merge payloads and Firebase sync.
 *
 * Active LAN health probes run only on user-initiated browse or transfer actions —
 * never as an idle background poll loop.
 */
class PeerPresenceMonitor(
    private val repository: DeviceRepository,
    private val client: OmniNodeClient
) {
    private val mutex = Mutex()
    private val reachabilityLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var snapshotWatcherJob: Job? = null

    private val lastReachableEpochById = mutableMapOf<String, Long>()
    private val _reachabilityEpochMs = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val _onlineSnapshotEpochMs = MutableStateFlow(0L)

    val reachabilityEpochMs: StateFlow<Map<String, Long>> = _reachabilityEpochMs.asStateFlow()
    /** Bumps when offline-grace windows are re-evaluated locally (no network I/O). */
    val onlineSnapshotEpochMs: StateFlow<Long> = _onlineSnapshotEpochMs.asStateFlow()

    private val _onlineDeviceIds = MutableStateFlow<Set<String>>(emptySet())
    val onlineDeviceIds: StateFlow<Set<String>> = _onlineDeviceIds.asStateFlow()

    fun isDeviceOnline(device: PairedDeviceEntity): Boolean {
        val probeEpoch = _reachabilityEpochMs.value[device.deviceId] ?: 0L
        val lastSeen = maxOf(probeEpoch, device.lastSeenEpochMs)
        return TimeUtils.isWithinWindow(lastSeen, LanPresenceTiming.OFFLINE_GRACE_MS)
    }

    /**
     * Starts a local-only watcher that re-evaluates grace windows for UI badges.
     * Does not perform any outbound network probes.
     */
    fun ensureOnlineSnapshotWatcher() {
        if (snapshotWatcherJob?.isActive == true) return
        snapshotWatcherJob = scope.launch {
            refreshOnlineSnapshot()
            while (isActive) {
                delay(LanPresenceTiming.ONLINE_SNAPSHOT_REFRESH_MS)
                refreshOnlineSnapshot()
            }
        }
    }

    fun stopOnlineSnapshotWatcher() {
        snapshotWatcherJob?.cancel()
        snapshotWatcherJob = null
    }

    /**
     * Records passive reachability from inbound [POST /devices/merge] payloads or Firebase sync.
     */
    suspend fun notifyPassiveReachability(vararg deviceIds: String, epochMs: Long = TimeUtils.now()) {
        markReachable(*deviceIds, epochMs = epochMs)
        refreshOnlineSnapshot()
    }

    suspend fun refreshOnlineSnapshot() {
        val peers = mutex.withLock { repository.listDevices() }
        publishStableOnlineIds(peers)
        _onlineSnapshotEpochMs.value = TimeUtils.now()
    }

    /**
     * On-demand health validation when the user opens a remote device.
     */
    suspend fun validatePeerOnDemand(peer: PairedDeviceEntity): Boolean {
        runCatching { sendWakeBroadcast() }
        return primePeer(peer)
    }

    /**
     * Pre-transfer wake/ping: UDP wake, health probe, identity ingest, and reachability refresh.
     */
    suspend fun primePeersForTransfer(targets: List<MultiCopyDeviceOption>) {
        if (targets.isEmpty()) return
        runCatching { sendWakeBroadcast() }
        for (target in targets.filter { !it.isLocal }) {
            val peer = mutex.withLock { repository.getDevice(target.deviceId) } ?: continue
            primePeer(peer)
        }
        refreshOnlineSnapshot()
    }

    private suspend fun primePeer(peer: PairedDeviceEntity): Boolean {
        val host = peer.lastKnownIp.trim()
        if (host.isEmpty() || host == "127.0.0.1" || host == "0.0.0.0") {
            return false
        }
        repeat(PRIME_ATTEMPTS) { attempt ->
            if (client.pingHealth(host, peer.port)) {
                markReachable(peer.deviceId)
                val state = runCatching { client.fetchPeerNodeState(host, peer.port) }.getOrNull()
                if (state != null) {
                    mutex.withLock {
                        repository.applyPeerNodeState(state, rosterDeviceId = peer.deviceId)
                    }
                    markReachable(state.deviceId.trim())
                    refreshOnlineSnapshot()
                    return true
                }
                mutex.withLock {
                    repository.touchPeerLastSeen(peer.deviceId, host, peer.port)
                }
                refreshOnlineSnapshot()
                return true
            }
            if (attempt < PRIME_ATTEMPTS - 1) {
                delay(PRIME_RETRY_MS)
            }
        }
        return isDeviceOnline(peer)
    }

    private suspend fun markReachable(vararg deviceIds: String, epochMs: Long = TimeUtils.now()) {
        reachabilityLock.withLock {
            var changed = false
            for (id in deviceIds) {
                val trimmed = id.trim()
                if (trimmed.isEmpty()) continue
                val previous = lastReachableEpochById[trimmed] ?: 0L
                val next = epochMs.coerceAtLeast(previous)
                if (next > previous) {
                    lastReachableEpochById[trimmed] = next
                    changed = true
                }
            }
            if (changed) {
                _reachabilityEpochMs.value = lastReachableEpochById.toMap()
            }
        }
    }

    private suspend fun publishStableOnlineIds(devices: List<PairedDeviceEntity>) {
        val nextOnline = devices.filter { isDeviceOnline(it) }.map { it.deviceId }.toSet()
        reachabilityLock.withLock {
            if (_onlineDeviceIds.value != nextOnline) {
                _onlineDeviceIds.value = nextOnline
            }
        }
    }

    companion object {
        /** @see LanPresenceTiming.OFFLINE_GRACE_MS */
        const val OFFLINE_GRACE_MS = LanPresenceTiming.OFFLINE_GRACE_MS
        private const val PRIME_ATTEMPTS = 4
        private const val PRIME_RETRY_MS = 750L
    }
}
