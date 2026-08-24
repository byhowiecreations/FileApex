package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.domain.peer.PeerNodeStateMapper
import com.fileapex.network.FileApexClient
import com.fileapex.network.ServerLifecycleManager
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.delay

/**
 * Coordinates one-time pairing/rename/removal deltas and local-only metadata broadcasts.
 *
 * Peer rosters are maintained from direct identity/heartbeat ingestion. On QR pair, the
 * broadcaster shares its local roster once with the newcomer (direct peer only — not multi-hop gossip).
 */
class PairingCoordinator(
    private val repository: DeviceRepository,
    private val client: FileApexClient,
    private val identityProvider: () -> LocalIdentity,
    private val onPassiveReachability: suspend (deviceIds: List<String>, epochMs: Long) -> Unit = { _, _ -> }
) {
    /**
     * Broadcaster path: inbound POST /pairing/respond from a scanner (persist only).
     * [propagatePairingComplete] runs after the HTTP 201 so the scanner can receive merge packets.
     */
    suspend fun handleInboundScanner(scanner: PairedDeviceEntity) {
        repository.adoptFromPairing(scanner)
        onPassiveReachability(listOf(scanner.deviceId), TimeUtils.now())
    }

    /**
     * Broadcaster path: one-time roster seed + intro fan-out after pairing/respond returns 201.
     */
    suspend fun propagatePairingComplete(newlyPaired: PairedDeviceEntity) {
        broadcastPairingCompleteOnce(newlyPaired)
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
                        "PairingCoordinator: remote removal failed for ${record.deviceId} - ${error.message}"
                    )
                }
        }
        for (state in request.nodeStates) {
            if (state.deviceId.isBlank() || state.deviceId == localId) {
                continue
            }
            if (request.eventKind == PeerSyncEventKind.PAIRING_INTRO) {
                runCatching {
                    val entity = PeerNodeStateMapper.toEntity(state)
                    if (repository.isBlocklisted(entity)) {
                        return@runCatching
                    }
                    repository.adoptFromPairing(entity)
                }.onFailure { error ->
                    println(
                        "PairingCoordinator: pairing intro adopt failed for ${state.deviceId} - ${error.message}"
                    )
                }
            }
            runCatching { repository.applyPeerNodeState(state) }
                .onSuccess {
                    val epochMs = state.lastSeenTimestamp.takeIf { it > 0L } ?: TimeUtils.now()
                    onPassiveReachability(listOf(state.deviceId.trim()), epochMs)
                }
                .onFailure { error ->
                    println(
                        "PairingCoordinator: node state apply failed for ${state.deviceId} - ${error.message}"
                    )
                }
        }
    }

    /**
     * Broadcasts this node's own metadata once to every paired peer (rename / identity refresh).
     */
    suspend fun broadcastSelfIdentity() {
        if (!NetworkUtils.isUsableLanIpv4(NetworkUtils.preferredLanIpv4())) {
            println("PairingCoordinator: skip self broadcast - no usable LAN IPv4")
            return
        }
        val selfState = selfNodeState()
        val peers = repository.listDevices()
        for (peer in peers) {
            val host = peer.lastKnownIp.trim()
            if (!NetworkUtils.isUsableLanIpv4(host)) {
                continue
            }
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
     * After importing a roster, introduce this node to every paired peer (direct LAN push).
     */
    suspend fun announceSelfToCluster(excludeDeviceIds: Set<String> = emptySet()) {
        if (!NetworkUtils.isUsableLanIpv4(NetworkUtils.preferredLanIpv4())) {
            println("PairingCoordinator: skip self announce - no usable LAN IPv4")
            return
        }
        val localId = identityProvider().deviceId
        val selfState = selfNodeState()
        val peers = repository.listDevices().filter { peer ->
            peer.deviceId != localId && peer.deviceId !in excludeDeviceIds
        }
        for (peer in peers) {
            val host = peer.lastKnownIp.trim()
            if (!NetworkUtils.isUsableLanIpv4(host)) continue
            runCatching {
                client.postClusterSync(
                    host = host,
                    port = peer.port,
                    request = ClusterSyncRequest(
                        eventKind = PeerSyncEventKind.PAIRING_INTRO,
                        nodeStates = listOf(selfState)
                    )
                )
            }.onFailure { error ->
                println(
                    "PairingCoordinator: failed self announce to ${peer.deviceName}: ${error.message}"
                )
            }
        }
    }

    /**
     * Scanner path: ensure the LAN share server is listening before reverse pairing traffic.
     */
    suspend fun awaitShareServerReady() {
        ServerLifecycleManager.ensureRunning()
        if (ServerLifecycleManager.isRunning) {
            return
        }
        repeat(SHARE_SERVER_READY_ATTEMPTS) {
            if (ServerLifecycleManager.isRunning) {
                return
            }
            delay(SHARE_SERVER_POLL_MS)
        }
    }

    /**
     * Scanner path: after pairing with [host]:[port], import that peer's local roster.
     *
     * @return count of newly adopted peers (excluding self and [excludeDeviceIds])
     */
    suspend fun importDirectPeerRoster(
        host: String,
        port: Int,
        excludeDeviceIds: Set<String> = emptySet()
    ): Int {
        var imported = 0
        repeat(ROSTER_IMPORT_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                delay(ROSTER_IMPORT_RETRY_MS)
            }
            val remoteDevices = runCatching {
                client.listPairedDevices(host, port)
            }.getOrElse { error ->
                println(
                    "PairingCoordinator: roster import attempt ${attempt + 1} failed - ${error.message}"
                )
                return@repeat
            }
            val eligible = eligibleRosterDevices(remoteDevices, excludeDeviceIds)
            if (eligible.isEmpty()) {
                println("PairingCoordinator: roster import - broadcaster returned no importable peers")
                return 0
            }
            imported = ingestRosterDevices(remoteDevices, excludeDeviceIds)
            if (imported > 0) {
                return imported
            }
        }
        return imported
    }

    private fun eligibleRosterDevices(
        remoteDevices: List<PairedDeviceEntity>,
        excludeDeviceIds: Set<String>
    ): List<PairedDeviceEntity> {
        val localId = identityProvider().deviceId
        return remoteDevices.filter { device ->
            val deviceId = device.deviceId.trim()
            deviceId.isNotEmpty() && deviceId != localId && deviceId !in excludeDeviceIds
        }
    }

    private suspend fun ingestRosterDevices(
        remoteDevices: List<PairedDeviceEntity>,
        excludeDeviceIds: Set<String>
    ): Int {
        val localId = identityProvider().deviceId
        var imported = 0
        for (device in remoteDevices) {
            val deviceId = device.deviceId.trim()
            if (deviceId.isEmpty() || deviceId == localId || deviceId in excludeDeviceIds) continue
            val adopted = runCatching { repository.adoptFromPairing(device) }
                .onFailure { error ->
                    println(
                        "PairingCoordinator: roster adopt failed for ${device.deviceName} - ${error.message}"
                    )
                }
                .getOrDefault(false)
            if (adopted) {
                imported++
            }
            val peerHost = device.lastKnownIp.trim()
            if (!NetworkUtils.isUsableLanIpv4(peerHost)) continue
            runCatching { client.fetchPeerNodeState(peerHost, device.port) }
                .getOrNull()
                ?.let { state ->
                    repository.applyPeerNodeState(state, rosterDeviceId = device.deviceId)
                    val epochMs = state.lastSeenTimestamp.takeIf { it > 0L } ?: TimeUtils.now()
                    onPassiveReachability(listOf(state.deviceId.trim()), epochMs)
                }
        }
        return imported
    }

    /**
     * One-time pairing propagation:
     * - Seed the newcomer's roster with our identity and every other paired peer we know.
     * - Tell each existing peer the newcomer's identity once.
     */
    private suspend fun broadcastPairingCompleteOnce(newlyPaired: PairedDeviceEntity) {
        val me = identityProvider()
        val selfState = selfNodeState()
        val newPeerState = resolvePeerState(newlyPaired)

        val existing = repository.listDevices()
            .filter { it.deviceId != newlyPaired.deviceId && it.deviceId != me.deviceId }
        val rosterForNewcomer = buildList {
            add(selfState)
            existing.forEach { peer -> add(resolvePeerState(peer)) }
        }
        runCatching {
            client.postClusterSync(
                host = newlyPaired.lastKnownIp,
                port = newlyPaired.port,
                request = ClusterSyncRequest(
                    eventKind = PeerSyncEventKind.PAIRING_INTRO,
                    nodeStates = rosterForNewcomer
                )
            )
        }.onFailure { error ->
            println(
                "PairingCoordinator: failed roster seed to ${newlyPaired.deviceName}: ${error.message}"
            )
        }

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
            runCatching { client.fetchPeerNodeState(host, peer.port) }.getOrNull()?.let { state ->
                val resolvedIp = state.resolvedIpAddress.ifBlank { host }
                return if (resolvedIp == state.ipAddress) state else state.copy(ipAddress = resolvedIp)
            }
        }
        return PeerNodeStateMapper.fromEntity(peer)
    }

    private fun selfNodeState() = PeerNodeStateMapper.selfState(
        identity = identityProvider(),
        pinRequired = FileApexServices.settings.pinRequiredEnabled.value
    )

    private companion object {
        const val SHARE_SERVER_READY_ATTEMPTS = 20
        const val SHARE_SERVER_POLL_MS = 100L
        const val ROSTER_IMPORT_ATTEMPTS = 3
        const val ROSTER_IMPORT_RETRY_MS = 500L
    }
}
