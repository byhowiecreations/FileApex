package com.omninode.domain.pairing

import com.omninode.data.db.PairedDeviceEntity
import com.omninode.data.device.DeviceRepository
import com.omninode.data.identity.LocalIdentity
import com.omninode.di.OmniNodeServices
import com.omninode.domain.peer.PeerNodeState
import com.omninode.domain.peer.PeerNodeStateMapper
import com.omninode.network.OmniNodeClient
import com.omninode.util.NetworkUtils
import com.omninode.util.TimeUtils

/**
 * Coordinates one-time pairing/rename/removal deltas and local-only metadata broadcasts.
 *
 * Peer rosters are populated exclusively from direct identity/heartbeat ingestion — never from
 * multi-hop roster relay or proxy list fan-out.
 */
class PairingCoordinator(
    private val repository: DeviceRepository,
    private val client: OmniNodeClient,
    private val identityProvider: () -> LocalIdentity,
    private val onPassiveReachability: suspend (deviceIds: List<String>, epochMs: Long) -> Unit = { _, _ -> }
) {
    /**
     * Broadcaster path: inbound POST /pairing/respond from a scanner.
     */
    suspend fun handleInboundScanner(scanner: PairedDeviceEntity) {
        repository.adoptFromPairing(scanner)
        onPassiveReachability(listOf(scanner.deviceId), TimeUtils.now())
        broadcastPairingCompleteOnce(scanner)
    }

    /**
     * Scanner path: local upsert of broadcaster already done; emit one-time pairing deltas.
     */
    suspend fun afterOutboundPair(peer: PairedDeviceEntity) {
        broadcastPairingCompleteOnce(peer)
    }

    /**
     * Passive merge from a direct peer packet — ingest metadata/removals only (no roster gossip).
     */
    suspend fun mergeIncoming(request: ClusterSyncRequest) {
        val localId = identityProvider().deviceId
        for (record in request.removedDevices) {
            if (record.deviceId.isBlank() || record.deviceId == localId) {
                continue
            }
            runCatching { repository.applyRemoteRemoval(record) }
                .onFailure { error ->
                    println(
                        "PairingCoordinator: remote removal failed for ${record.deviceId} — ${error.message}"
                    )
                }
        }
        for (state in request.nodeStates) {
            if (state.deviceId.isBlank() || state.deviceId == localId) {
                continue
            }
            runCatching { repository.applyPeerNodeState(state) }
                .onSuccess {
                    val epochMs = state.lastSeenTimestamp.takeIf { it > 0L } ?: TimeUtils.now()
                    onPassiveReachability(listOf(state.deviceId.trim()), epochMs)
                }
                .onFailure { error ->
                    println(
                        "PairingCoordinator: node state apply failed for ${state.deviceId} — ${error.message}"
                    )
                }
        }
    }

    /**
     * Broadcasts this node's own metadata once to every paired peer (rename / identity refresh).
     */
    suspend fun broadcastSelfIdentity() {
        val selfState = selfNodeState()
        val peers = repository.listDevices()
        for (peer in peers) {
            runCatching {
                client.postClusterSync(
                    host = peer.lastKnownIp,
                    port = peer.port,
                    request = ClusterSyncRequest(
                        eventKind = PeerSyncEventKind.SELF_METADATA,
                        nodeStates = listOf(selfState)
                    )
                )
            }.onFailure { error ->
                println(
                    "PairingCoordinator: failed to broadcast self metadata to " +
                        "${peer.deviceName}: ${error.message}"
                )
            }
        }
    }

    /**
     * Fan-out a permanent removal to every remaining paired peer so they blocklist and drop it.
     */
    suspend fun broadcastDeviceRemoval(removed: PairedDeviceEntity) {
        val removal = RemovedDeviceRecord(
            deviceId = removed.deviceId,
            publicKeyHash = removed.publicKeyHash,
            lastKnownIp = removed.lastKnownIp,
            port = removed.port
        )
        val peers = repository.listDevices().filter { it.deviceId != removed.deviceId }
        for (peer in peers) {
            runCatching {
                client.postClusterSync(
                    host = peer.lastKnownIp,
                    port = peer.port,
                    request = ClusterSyncRequest(
                        eventKind = PeerSyncEventKind.REMOVAL,
                        removedDevices = listOf(removal)
                    )
                )
            }.onFailure { error ->
                println(
                    "PairingCoordinator: failed to broadcast removal of " +
                        "${removed.deviceName} to ${peer.deviceName}: ${error.message}"
                )
            }
        }
    }

    /**
     * One-time pairing propagation:
     * - Tell the new peer our self metadata once.
     * - Tell each existing peer the newcomer's identity once (no roster relay).
     */
    private suspend fun broadcastPairingCompleteOnce(newlyPaired: PairedDeviceEntity) {
        val me = identityProvider()
        val selfState = selfNodeState()
        val newPeerState = resolvePeerState(newlyPaired)

        runCatching {
            client.postClusterSync(
                host = newlyPaired.lastKnownIp,
                port = newlyPaired.port,
                request = ClusterSyncRequest(
                    eventKind = PeerSyncEventKind.SELF_METADATA,
                    nodeStates = listOf(selfState)
                )
            )
        }.onFailure { error ->
            println(
                "PairingCoordinator: failed self-metadata to ${newlyPaired.deviceName}: ${error.message}"
            )
        }

        val existing = repository.listDevices()
            .filter { it.deviceId != newlyPaired.deviceId && it.deviceId != me.deviceId }
        for (peer in existing) {
            runCatching {
                client.postClusterSync(
                    host = peer.lastKnownIp,
                    port = peer.port,
                    request = ClusterSyncRequest(
                        eventKind = PeerSyncEventKind.PAIRING_INTRO,
                        nodeStates = listOf(newPeerState)
                    )
                )
            }.onFailure { error ->
                println(
                    "PairingCoordinator: failed pairing intro for ${newlyPaired.deviceName} " +
                        "to ${peer.deviceName}: ${error.message}"
                )
            }
        }
    }

    private suspend fun resolvePeerState(peer: PairedDeviceEntity): PeerNodeState {
        val host = peer.lastKnownIp.trim()
        if (host.isNotEmpty()) {
            runCatching { client.fetchPeerNodeState(host, peer.port) }.getOrNull()?.let { return it }
        }
        return PeerNodeStateMapper.fromEntity(peer)
    }

    private fun selfNodeState() = PeerNodeStateMapper.selfState(
        identity = identityProvider(),
        pinRequired = OmniNodeServices.settings.pinRequiredEnabled.value
    )
}
