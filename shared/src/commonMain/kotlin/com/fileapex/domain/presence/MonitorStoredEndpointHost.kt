package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.DeviceRepository
import com.fileapex.di.FileApexServices
import com.fileapex.domain.link.HealthSignal
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.network.FileApexClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the stored endpoint probe needs from the presence monitor: its client, its roster access and its reachability book. */
internal class MonitorStoredEndpointHost(
    private val client: FileApexClient,
    private val repository: DeviceRepository,
    private val mutex: Mutex,
    private val book: ReachabilityBook,
    private val reachable: suspend (String) -> Unit
) : StoredEndpointHost {
    override suspend fun probeHealth(host: String, port: Int, timeoutMs: Long, signal: HealthSignal?) =
        client.probeHealth(host, port, timeoutMs, signal)

    override suspend fun recordEndpointMismatch(peerId: String, host: String, answeredAs: String) {
        mutex.withLock { repository.recordEndpointMismatch(peerId, host, answeredAs) }
    }

    override suspend fun markReachable(deviceId: String) = reachable(deviceId)

    override suspend fun markRefused(deviceId: String) {
        book.markDown(deviceId)
        println("PeerPresenceMonitor: ${deviceId.take(8)} refused every connection, shown offline")
    }

    override fun onBulletinVersion(peer: PairedDeviceEntity, version: Long) {
        FileApexServices.bulletinSyncEngine.onPeerBulletinVersion(peer, version)
    }

    override suspend fun touchLastSeenEpoch(deviceId: String) {
        mutex.withLock { repository.touchPeerLastSeenEpoch(deviceId) }
    }

    override suspend fun touchLastSeen(deviceId: String, host: String, port: Int) {
        mutex.withLock { repository.touchPeerLastSeen(deviceId, host, port) }
    }

    override suspend fun fetchNodeState(host: String, port: Int, timeoutMs: Long) =
        client.fetchPeerNodeState(host, port, timeoutMs)

    override suspend fun applyNodeState(state: PeerNodeState, rosterDeviceId: String) {
        mutex.withLock { repository.applyPeerNodeState(state, rosterDeviceId = rosterDeviceId, observedDirectly = true) }
    }

    override suspend fun supersedeHardwareTwins(state: PeerNodeState) {
        com.fileapex.domain.pairing.HardwareTwins.supersede(state)
    }
}
