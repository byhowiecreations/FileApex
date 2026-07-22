package com.omninode.domain.presence

import com.omninode.data.db.PairedDeviceEntity
import com.omninode.data.device.DeviceRepository
import com.omninode.domain.peer.PeerNodeState
import com.omninode.domain.transfer.MultiCopyDeviceOption
import com.omninode.network.OmniNodeClient
import com.omninode.network.sendWakeBroadcast
import com.omninode.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Listens for direct peer heartbeats/identity payloads and tracks reachability.
 *
 * This monitor never ingests gossip rosters — only direct [PeerNodeState] from each peer endpoint.
 */
class PeerPresenceMonitor(
    private val repository: DeviceRepository,
    private val client: OmniNodeClient
) {
    private val mutex = Mutex()
    private val reachabilityLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val lastReachableEpochById = mutableMapOf<String, Long>()
    private val _reachabilityEpochMs = MutableStateFlow<Map<String, Long>>(emptyMap())

    val reachabilityEpochMs: StateFlow<Map<String, Long>> = _reachabilityEpochMs.asStateFlow()

    private val _onlineDeviceIds = MutableStateFlow<Set<String>>(emptySet())
    val onlineDeviceIds: StateFlow<Set<String>> = _onlineDeviceIds.asStateFlow()

    fun isDeviceOnline(device: PairedDeviceEntity): Boolean {
        val probeEpoch = _reachabilityEpochMs.value[device.deviceId] ?: 0L
        val lastSeen = maxOf(probeEpoch, device.lastSeenEpochMs)
        return TimeUtils.isWithinWindow(lastSeen, OFFLINE_GRACE_MS)
    }

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                runCatching { pollOnce() }
                    .onFailure { error ->
                        println("PeerPresenceMonitor: poll failed :: ${error.message}")
                    }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    suspend fun refreshNow() {
        pollOnce()
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
        val refreshedPeers = mutex.withLock { repository.listDevices() }
        publishStableOnlineIds(refreshedPeers)
    }

    suspend fun primePeer(peer: PairedDeviceEntity): Boolean {
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
                    return true
                }
                mutex.withLock {
                    repository.touchPeerLastSeen(peer.deviceId, host, peer.port)
                }
                return true
            }
            if (attempt < PRIME_ATTEMPTS - 1) {
                delay(PRIME_RETRY_MS)
            }
        }
        return isDeviceOnline(peer)
    }

    private suspend fun pollOnce() {
        val peers = mutex.withLock { repository.listDevices() }
        if (peers.isEmpty()) {
            publishStableOnlineIds(emptyList())
            return
        }

        val probeResults = coroutineScope {
            peers.map { peer ->
                async { probePeer(peer) }
            }.awaitAll()
        }

        for (result in probeResults) {
            if (result == null) continue
            markReachable(result.rosterDeviceId, result.liveDeviceId)
            if (result.nodeState != null) {
                mutex.withLock {
                    repository.applyPeerNodeState(
                        result.nodeState,
                        rosterDeviceId = result.rosterDeviceId
                    )
                }
            } else {
                mutex.withLock {
                    repository.touchPeerLastSeen(
                        deviceId = result.rosterDeviceId,
                        ip = result.host,
                        port = result.port
                    )
                }
            }
        }

        val refreshedPeers = mutex.withLock { repository.listDevices() }
        publishStableOnlineIds(refreshedPeers)
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

    private suspend fun probePeer(peer: PairedDeviceEntity): PeerProbeResult? {
        val host = peer.lastKnownIp.trim()
        if (host.isEmpty() || host == "127.0.0.1" || host == "0.0.0.0") {
            return null
        }
        if (!client.pingHealth(host, peer.port)) {
            return null
        }

        val rawState = runCatching { client.fetchPeerNodeState(host, peer.port) }.getOrNull()
            ?: return PeerProbeResult(
                rosterDeviceId = peer.deviceId,
                liveDeviceId = peer.deviceId,
                deviceName = peer.deviceName,
                host = host,
                port = peer.port,
                nodeState = null
            )

        val nodeState = rawState.copy(
            lastSeenTimestamp = TimeUtils.now().coerceAtLeast(rawState.lastSeenTimestamp)
        )
        val liveHost = nodeState.resolvedIpAddress.ifBlank { host }
        val livePort = nodeState.port.takeIf { it > 0 } ?: peer.port

        return PeerProbeResult(
            rosterDeviceId = peer.deviceId,
            liveDeviceId = nodeState.deviceId,
            deviceName = nodeState.deviceName.ifBlank { peer.deviceName },
            host = liveHost,
            port = livePort,
            nodeState = nodeState
        )
    }

    private data class PeerProbeResult(
        val rosterDeviceId: String,
        val liveDeviceId: String,
        val deviceName: String,
        val host: String,
        val port: Int,
        val nodeState: PeerNodeState?
    )

    companion object {
        private const val POLL_INTERVAL_MS = 4_000L
        /** UI offline grace — roster rows are retained until explicit removal. */
        const val OFFLINE_GRACE_MS = 120_000L
        private const val PRIME_ATTEMPTS = 4
        private const val PRIME_RETRY_MS = 750L
    }
}
