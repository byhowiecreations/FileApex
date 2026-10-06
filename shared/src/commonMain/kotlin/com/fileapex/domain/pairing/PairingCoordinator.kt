package com.fileapex.domain.pairing

import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.peer.ClusterClock
import com.fileapex.domain.peer.PeerNodeStateMapper
import com.fileapex.domain.peer.rosterWithoutRelayedPresence
import com.fileapex.network.FileApexClient
import com.fileapex.network.ServerLifecycleManager
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    private val forwardScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        com.fileapex.domain.clipboard.ClipboardShareCoordinator.checkAndApplyAutoDefaultTarget()
        runCatching {
            FileApexServices.bulletinSyncEngineOrNull()?.onDevicePairingComplete(newlyPaired)
        }
    }

    /**
     * Scanner path: local upsert of broadcaster already done; emit one-time pairing deltas.
     */
    suspend fun afterOutboundPair(peer: PairedDeviceEntity) {
        broadcastPairingCompleteOnce(peer)
        com.fileapex.domain.clipboard.ClipboardShareCoordinator.checkAndApplyAutoDefaultTarget()
        runCatching {
            FileApexServices.bulletinSyncEngineOrNull()?.onDevicePairingComplete(peer)
        }
    }

    /**
     * Passive merge from a direct peer packet — ingest metadata/removals only (no roster gossip).
     */
    suspend fun mergeIncoming(request: ClusterSyncRequest) {
        val localId = identityProvider().deviceId
        for (record in request.removedDevices) {
            if (record.deviceId.isBlank()) {
                continue
            }
            if (record.deviceId == localId) {
                if (handleSelfRemoval(record)) return
                continue
            }
            handlePeerRemoval(record, forward = false)
        }
        for (state in request.nodeStates) {
            if (state.deviceId.isBlank()) {
                continue
            }
            if (state.deviceId == localId) {
                if (request.eventKind == PeerSyncEventKind.PAIRING_INTRO && state.hasMembershipProtocol) {
                    repository.recordSelfMembership(state.membershipVersion)
                }
                continue
            }
            val applied = try {
                repository.applyPeerNodeState(state)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                println("PairingCoordinator: node state apply failed for ${state.deviceId} - ${error.message}")
                false
            }
            if (applied && request.eventKind == PeerSyncEventKind.PAIRING_INTRO) {
                repository.getDevice(state.deviceId.trim())?.let { entity ->
                    FileApexServices.bulletinSyncEngineOrNull()?.onDevicePairingComplete(entity)
                }
            }
            if (state.lastSeenTimestamp > 0L && state.publishesPresence()) {
                onPassiveReachability(listOf(state.deviceId.trim()), state.lastSeenTimestamp)
            }
        }
    }

    /**
     * Removal of another peer. Forwarded to the rest of the roster only when it changed local
     * state, so echoes stop after one hop.
     */
    suspend fun handlePeerRemoval(record: RemovedDeviceRecord, forward: Boolean): Boolean {
        val applied = try {
            repository.applyRemoteRemoval(record)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            println("PairingCoordinator: remote removal failed for ${record.deviceId} - ${error.message}")
            false
        }
        if (!applied) return false
        com.fileapex.session.DeviceSessionManager.clearSession(record.deviceId)
        FileApexServices.presenceMonitor.refreshOnlineSnapshot()
        forwardScope.launch {
            GoogleLinkCoordinator.publishRemovedPeer(record.deviceId)
        }
        if (forward) {
            forwardScope.launch {
                broadcastRemovalToCluster(record)
            }
        }
        return true
    }

    /** Wipes the local cluster only for a removal stamped after this node's latest pairing. */
    suspend fun handleSelfRemoval(record: RemovedDeviceRecord): Boolean {
        if (!repository.acceptsSelfRemoval(record)) {
            println(
                "PairingCoordinator: ignored self removal v=${record.membershipVersion()} " +
                    "protocol=${record.membershipProtocol} (membership v=${repository.selfMembershipVersion()})"
            )
            return false
        }
        repository.handleRevocationByCluster()
        com.fileapex.session.DeviceSessionManager.clearAllSessions()
        FileApexServices.presenceMonitor.refreshOnlineSnapshot()
        return true
    }

    /** Rows a peer returned from cluster sync; may only add or refresh active peers. */
    private suspend fun reconcileReturnedRoster(
        roster: List<PairedDeviceEntity>,
        source: PairedDeviceEntity? = null
    ) {
        val me = identityProvider().deviceId
        val rows = rosterWithoutRelayedPresence(source?.clientVersion.orEmpty(), roster)
        for (remote in rows) {
            if (remote.deviceId.isBlank() || remote.deviceId == me) continue
            try {
                repository.reconcileRemotePeer(remote)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                println("PairingCoordinator: roster reconcile failed for ${remote.deviceId} - ${error.message}")
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
        coroutineScope {
            peers.map { peer ->
                async(Dispatchers.IO) {
                    val host = peer.lastKnownIp.trim()
                    if (!NetworkUtils.isUsableLanIpv4(host)) {
                        return@async
                    }
                    runCatching {
                        val returnedRoster = client.postClusterSync(
                            host = peer.lastKnownIp,
                            port = peer.port,
                            request = ClusterSyncRequest(
                                eventKind = PeerSyncEventKind.SELF_METADATA,
                                nodeStates = listOf(selfState),
                                clusterVersion = selfState.clusterVersion
                            )
                        )
                        reconcileReturnedRoster(returnedRoster, source = peer)
                    }.onFailure { error ->
                        println(
                            "PairingCoordinator: failed to broadcast self metadata to " +
                                "${peer.deviceName}: ${error.message}"
                        )
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * Fan-out a permanent removal:
     * 1. Priority direct removal notification to the removed device if reachable on LAN.
     * 2. Parallel tombstone broadcast to every remaining paired peer.
     */
    suspend fun broadcastDeviceRemoval(removed: PairedDeviceEntity) {
        val entry = repository.getDeviceEntry(removed.deviceId)
        val clusterVersion = entry?.takeIf { it.isRemoved }?.let { maxOf(it.clusterVersion, it.removedAt ?: 0L) }
            ?.takeIf { it > 0L }
            ?: run {
                println("PairingCoordinator: skip removal broadcast for ${removed.deviceId} - no local tombstone")
                return
            }
        val removal = RemovedDeviceRecord(
            deviceId = removed.deviceId,
            publicKeyHash = removed.publicKeyHash,
            lastKnownIp = removed.lastKnownIp,
            port = removed.port,
            clusterVersion = clusterVersion,
            removedAt = clusterVersion,
            membershipProtocol = ClusterClock.MEMBERSHIP_PROTOCOL
        )

        val targetHost = removed.lastKnownIp.trim()
        if (NetworkUtils.isUsableLanIpv4(targetHost)) {
            runCatching {
                client.postClusterRemove(targetHost, removed.port, removal)
            }.onFailure { error ->
                println(
                    "PairingCoordinator: direct priority removal notification to " +
                        "${removed.deviceName} failed - ${error.message}"
                )
            }
        }

        val peers = repository.listDevices().filter { it.deviceId != removed.deviceId }
        coroutineScope {
            peers.map { peer ->
                async(Dispatchers.IO) {
                    val peerHost = peer.lastKnownIp.trim()
                    if (!NetworkUtils.isUsableLanIpv4(peerHost)) return@async
                    runCatching {
                        val returnedRoster = client.postClusterSync(
                            host = peerHost,
                            port = peer.port,
                            request = ClusterSyncRequest(
                                eventKind = PeerSyncEventKind.REMOVAL,
                                removedDevices = listOf(removal),
                                clusterVersion = clusterVersion
                            )
                        )
                        reconcileReturnedRoster(returnedRoster, source = peer)
                    }.onFailure { error ->
                        println(
                            "PairingCoordinator: failed to broadcast removal of " +
                                "${removed.deviceName} to ${peer.deviceName}: ${error.message}"
                        )
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * Re-broadcasts an incoming peer removal to all other peers in the local roster.
     */
    suspend fun broadcastRemovalToCluster(removal: RemovedDeviceRecord) {
        val me = identityProvider().deviceId
        val peers = repository.listDevices().filter { it.deviceId != removal.deviceId && it.deviceId != me }
        coroutineScope {
            peers.map { peer ->
                async(Dispatchers.IO) {
                    val peerHost = peer.lastKnownIp.trim()
                    if (!NetworkUtils.isUsableLanIpv4(peerHost)) return@async
                    runCatching {
                        client.postClusterSync(
                            host = peerHost,
                            port = peer.port,
                            request = ClusterSyncRequest(
                                eventKind = PeerSyncEventKind.REMOVAL,
                                removedDevices = listOf(removal),
                                clusterVersion = removal.clusterVersion
                            )
                        )
                    }
                }
            }.awaitAll()
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
        coroutineScope {
            peers.map { peer ->
                async(Dispatchers.IO) {
                    val host = peer.lastKnownIp.trim()
                    if (!NetworkUtils.isUsableLanIpv4(host)) return@async
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
            }.awaitAll()
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
        excludeDeviceIds: Set<String> = emptySet(),
        sourceClientVersion: String = ""
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
            imported = ingestRosterDevices(remoteDevices, excludeDeviceIds, sourceClientVersion)
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
        excludeDeviceIds: Set<String>,
        sourceClientVersion: String = ""
    ): Int {
        val localId = identityProvider().deviceId
        var imported = 0
        for (device in remoteDevices) {
            val deviceId = device.deviceId.trim()
            if (deviceId.isEmpty() || deviceId == localId || deviceId in excludeDeviceIds) continue
            val adopted = runCatching {
                repository.adoptFromRosterIntro(
                    rosterWithoutRelayedPresence(sourceClientVersion, listOf(device)).first()
                )
            }
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
                    if (state.publishesPresence()) {
                        val epochMs = state.lastSeenTimestamp.takeIf { it > 0L } ?: TimeUtils.now()
                        onPassiveReachability(listOf(state.deviceId.trim()), epochMs)
                    }
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
        val pairingVersion = repository.nextMembershipVersion()
        repository.adoptFromPairing(newlyPaired, version = pairingVersion)
        repository.recordSelfMembership(pairingVersion)
        val updatedNewcomer = repository.getDevice(newlyPaired.deviceId)
            ?: newlyPaired.copy(clusterVersion = pairingVersion, isRemoved = false, removedAt = null)

        val selfState = selfNodeState().copy(clusterVersion = pairingVersion)
        val newPeerState = PeerNodeStateMapper.fromEntity(updatedNewcomer).copy(
            clusterVersion = pairingVersion,
            membershipVersion = maxOf(pairingVersion, updatedNewcomer.clusterVersion)
        )

        val existing = repository.listDevices()
            .filter { it.deviceId != newlyPaired.deviceId && it.deviceId != me.deviceId }

        // 1. Announce the newcomer to all existing cluster peers FIRST with the new clusterVersion
        // so their tombstones are cleared before the newcomer starts communicating with them.
        if (existing.isNotEmpty()) {
            coroutineScope {
                existing.map { peer ->
                    async(Dispatchers.IO) {
                        runCatching {
                            withTimeoutOrNull(3000L) {
                                val returnedRoster = client.postClusterSync(
                                    host = peer.lastKnownIp,
                                    port = peer.port,
                                    request = ClusterSyncRequest(
                                        eventKind = PeerSyncEventKind.PAIRING_INTRO,
                                        nodeStates = listOf(newPeerState),
                                        clusterVersion = pairingVersion
                                    )
                                )
                                reconcileReturnedRoster(returnedRoster, source = peer)
                            }
                        }.onFailure { error ->
                            println(
                                "PairingCoordinator: failed pairing intro for ${newlyPaired.deviceName} " +
                                    "to ${peer.deviceName}: ${error.message}"
                            )
                        }
                    }
                }.awaitAll()
            }
        }

        // 2. Seed the newcomer with every cluster device, plus its own stamp so its revocation-gate
        // version outranks any tombstone peers still hold from before this pairing.
        val rosterForNewcomer = buildList {
            add(selfState)
            add(newPeerState)
            existing.forEach { peer -> add(PeerNodeStateMapper.fromEntity(peer)) }
        }
        val tombstones = repository.getTombstoneRecords().filter { it.deviceId != newlyPaired.deviceId }
        runCatching {
            val returnedRoster = client.postClusterSync(
                host = newlyPaired.lastKnownIp,
                port = newlyPaired.port,
                request = ClusterSyncRequest(
                    eventKind = PeerSyncEventKind.PAIRING_INTRO,
                    nodeStates = rosterForNewcomer,
                    removedDevices = tombstones,
                    clusterVersion = pairingVersion
                )
            )
            reconcileReturnedRoster(returnedRoster, source = updatedNewcomer)
        }.onFailure { error ->
            println(
                "PairingCoordinator: failed roster seed to ${newlyPaired.deviceName}: ${error.message}"
            )
        }
    }

    private fun selfNodeState() = PeerNodeStateMapper.selfState(
        identity = identityProvider(),
        membershipVersion = repository.selfMembershipVersion(),
        pinRequired = FileApexServices.settings.pinRequiredEnabled.value
    )

    private companion object {
        const val SHARE_SERVER_READY_ATTEMPTS = 20
        const val SHARE_SERVER_POLL_MS = 100L
        const val ROSTER_IMPORT_ATTEMPTS = 3
        const val ROSTER_IMPORT_RETRY_MS = 500L
    }
}
