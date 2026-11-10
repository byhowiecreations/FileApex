package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.domain.peer.PeerNodeStateMapper
import com.fileapex.security.tls.PeerTlsRoutes
import com.fileapex.network.FileApexClient
import com.fileapex.network.ServerLifecycleManager
import com.fileapex.util.cancellableCatching
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pairing handshake helpers. Membership after pairing is the signed chain ([MembershipHub.onPaired]).
 */
class PairingCoordinator(
    private val repository: DeviceRepository,
    private val client: FileApexClient,
    private val identityProvider: () -> LocalIdentity,
    private val onPassiveReachability: suspend (deviceIds: List<String>, epochMs: Long) -> Unit = { _, _ -> },
    private val fetchNodeState: suspend (host: String, port: Int) -> PeerNodeState? = { host, port ->
        cancellableCatching { withTimeoutOrNull(HANDSHAKE_FETCH_MS) { client.fetchPeerNodeState(host, port) } }.getOrNull()
    }
) {
    /**
     * Broadcaster path: inbound POST /pairing/respond from a scanner (persist only).
     */
    suspend fun handleInboundScanner(scanner: PairedDeviceEntity) {
        repository.adoptFromPairing(scanner)
        if (scanner.tlsPin.isNotBlank()) {
            repository.recordTlsPin(scanner.deviceId, scanner.tlsPin, scanner.tlsPort, rejoin = true)
        }
        repeat(HANDSHAKE_KEY_ATTEMPTS) {
            if (repository.getDevice(scanner.deviceId)?.publicKey?.isNotBlank() == true) return@repeat
            fetchPeerKey(scanner.deviceId, scanner.lastKnownIp, scanner.port)
        }
        onPassiveReachability(listOf(scanner.deviceId), TimeUtils.now())
    }

    /** Ready means the peer's key is stored and, when it runs TLS, its pin is stored and a TLS contact worked. */
    suspend fun awaitPairingReady(peerId: String, timeoutMs: Long = PAIRING_READY_TIMEOUT_MS): Boolean {
        val deadline = TimeUtils.now() + timeoutMs
        var waitMs = 1_000L
        while (TimeUtils.now() < deadline) {
            val peer = repository.getDevice(peerId)?.takeUnless { it.isRemoved } ?: return false
            if (isPairingReady(peer)) return true
            delay(waitMs)
            waitMs = minOf(waitMs + 1_000L, 5_000L)
        }
        return false
    }

    private suspend fun isPairingReady(peer: PairedDeviceEntity): Boolean {
        val state = fetchNodeState(peer.lastKnownIp, peer.port)?.takeIf { it.deviceId.trim() == peer.deviceId }
            ?: return false
        if (repository.getDevice(peer.deviceId)?.publicKey.isNullOrBlank()) {
            storePeerKey(peer.deviceId, state)
        }
        val current = repository.getDevice(peer.deviceId) ?: return false
        if (current.publicKey.isBlank()) return false
        val runsTls = state.tlsPort in 1..65535 && state.tlsPin.isNotEmpty()
        return !runsTls || (current.tlsPin.isNotEmpty() && PeerTlsRoutes.isPinned(current.lastKnownIp, current.port))
    }

    private suspend fun fetchPeerKey(peerId: String, host: String, port: Int) {
        fetchNodeState(host, port)?.takeIf { it.deviceId.trim() == peerId }?.let { storePeerKey(peerId, it) }
    }

    private suspend fun storePeerKey(peerId: String, state: PeerNodeState) {
        repository.applyPeerNodeState(state, rosterDeviceId = peerId, observedDirectly = true)
        if (repository.getDevice(peerId)?.publicKey.isNullOrBlank()) {
            repository.recordBoundPublicKey(peerId, state.publicKey)
        }
    }

    /**
     * Scanner path: local upsert of broadcaster already done; emit one-time pairing deltas.
     */
    suspend fun afterOutboundPair(peer: PairedDeviceEntity, preexistingDeviceIds: Set<String>? = null) {
        com.fileapex.cloud.device.DeviceIdentity.onPaired()
        com.fileapex.domain.clipboard.ClipboardShareCoordinator.checkAndApplyAutoDefaultTarget()
        cancellableCatching { FileApexServices.bulletinSyncEngineOrNull()?.onDevicePairingComplete(peer) }
    }

    /**
     * Broadcasts this node's own metadata once to every paired peer that is online (rename / identity refresh).
     */
    suspend fun broadcastSelfIdentity(onlineDeviceIds: Set<String>) {
        val me = identityProvider().deviceId
        FileApexServices.membershipHub?.chainReplicator?.announce(onlineDeviceIds.filter { it != me })
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

    /** This device's own state, as it tells the cluster about itself. */
    fun ownState() = selfNodeState()

    private fun selfNodeState() = PeerNodeStateMapper.selfState(
        identity = identityProvider(),
        pinRequired = FileApexServices.settings.pinRequiredEnabled.value
    )

    private companion object {
        const val SHARE_SERVER_READY_ATTEMPTS = 20
        const val SHARE_SERVER_POLL_MS = 100L
        const val PAIRING_READY_TIMEOUT_MS = 10L * 60_000L
        const val HANDSHAKE_FETCH_MS = 3_000L
        const val HANDSHAKE_KEY_ATTEMPTS = 3
    }
}
