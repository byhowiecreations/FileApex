package com.omninode.domain.presence

import com.omninode.data.db.PairedDeviceEntity
import com.omninode.data.device.DeviceRepository
import com.omninode.domain.pairing.ClusterSyncRequest
import com.omninode.domain.pairing.PairingCoordinator
import com.omninode.network.OmniNodeClient
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
 * Periodically probes paired peers and pulls their device rosters so
 * online/offline status and new cluster members appear without app restart.
 */
class PeerPresenceMonitor(
    private val repository: DeviceRepository,
    private val client: OmniNodeClient,
    private val pairingCoordinator: PairingCoordinator,
    private val selfDeviceProvider: () -> PairedDeviceEntity
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val _onlineDeviceIds = MutableStateFlow<Set<String>>(emptySet())
    val onlineDeviceIds: StateFlow<Set<String>> = _onlineDeviceIds.asStateFlow()

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

    private suspend fun pollOnce() {
        val peers = mutex.withLock { repository.listDevices() }
        if (peers.isEmpty()) {
            mutex.withLock { _onlineDeviceIds.value = emptySet() }
            return
        }

        val self = selfDeviceProvider()
        val probeResults = coroutineScope {
            peers.map { peer ->
                async { probePeer(peer, self) }
            }.awaitAll()
        }

        val online = linkedSetOf<String>()
        for (result in probeResults) {
            if (result == null) continue
            online += result.peer.deviceId
            result.refreshedPeer?.let { refreshed ->
                mutex.withLock {
                    repository.upsertReplacingAliases(refreshed)
                }
            }
            result.mergeRequest?.let { request ->
                runCatching {
                    pairingCoordinator.mergeIncoming(request)
                }.onFailure { error ->
                    println(
                        "PeerPresenceMonitor: roster pull failed for " +
                            "${result.peer.deviceName}: ${error.message}"
                    )
                }
            }
        }

        mutex.withLock {
            runCatching { repository.reconcileDuplicateEndpoints() }
            if (_onlineDeviceIds.value != online) {
                _onlineDeviceIds.value = online
            }
        }
    }

    private suspend fun probePeer(
        peer: PairedDeviceEntity,
        self: PairedDeviceEntity
    ): PeerProbeResult? {
        val host = peer.lastKnownIp.trim()
        // Empty/loopback hosts can resolve oddly (e.g. probe this device) and
        // produce fake "Online · :8080" rows — skip until a real LAN IP exists.
        if (host.isEmpty() || host == "127.0.0.1" || host == "0.0.0.0") {
            return null
        }
        if (!client.pingHealth(host, peer.port)) {
            return null
        }

        val refreshedPeer = runCatching {
            val identity = client.fetchIdentity(host, peer.port)
            if (identity.deviceId != peer.deviceId) return@runCatching null
            val refreshed = peer.copy(
                deviceName = identity.deviceName.ifBlank { peer.deviceName },
                rootPath = identity.rootPath.ifBlank { peer.rootPath },
                port = identity.port.takeIf { it > 0 } ?: peer.port
            )
            refreshed.takeIf { it != peer }
        }.getOrNull()

        val mergeRequest = runCatching {
            val remoteRoster = client.listPairedDevices(host, peer.port)
            ClusterSyncRequest(
                introducer = peer,
                devices = remoteRoster + self
            )
        }.getOrNull()

        return PeerProbeResult(
            peer = peer,
            refreshedPeer = refreshedPeer,
            mergeRequest = mergeRequest
        )
    }

    private data class PeerProbeResult(
        val peer: PairedDeviceEntity,
        val refreshedPeer: PairedDeviceEntity?,
        val mergeRequest: ClusterSyncRequest?
    )

    companion object {
        private const val POLL_INTERVAL_MS = 4_000L
    }
}
