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
        mutex.withLock {
            val peers = repository.listDevices()
            if (peers.isEmpty()) {
                _onlineDeviceIds.value = emptySet()
                return
            }

            val online = linkedSetOf<String>()
            val self = selfDeviceProvider()

            for (peer in peers) {
                val reachable = client.pingHealth(peer.lastKnownIp, peer.port)
                if (!reachable) continue
                online += peer.deviceId

                runCatching {
                    val identity = client.fetchIdentity(peer.lastKnownIp, peer.port)
                    if (identity.deviceId == peer.deviceId) {
                        val refreshed = peer.copy(
                            deviceName = identity.deviceName.ifBlank { peer.deviceName },
                            rootPath = identity.rootPath.ifBlank { peer.rootPath },
                            port = identity.port.takeIf { it > 0 } ?: peer.port
                        )
                        if (refreshed != peer) {
                            repository.upsertReplacingAliases(refreshed)
                        }
                    }
                }

                runCatching {
                    val remoteRoster = client.listPairedDevices(peer.lastKnownIp, peer.port)
                    pairingCoordinator.mergeIncoming(
                        ClusterSyncRequest(
                            introducer = peer,
                            devices = remoteRoster + self
                        )
                    )
                }.onFailure { error ->
                    println(
                        "PeerPresenceMonitor: roster pull failed for " +
                            "${peer.deviceName}: ${error.message}"
                    )
                }
            }

            if (_onlineDeviceIds.value != online) {
                _onlineDeviceIds.value = online
            }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 4_000L
    }
}
