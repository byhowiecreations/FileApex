package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.db.isDockerNode
import com.fileapex.domain.link.HealthSignal
import com.fileapex.domain.link.LinkedElsewhere
import com.fileapex.domain.link.PhoneLink
import com.fileapex.domain.notifications.NotificationCompanion
import com.fileapex.domain.notifications.NotificationDebugLog
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.network.FileApexClient
import com.fileapex.tailscale.TailscaleNodeRuntime
import com.fileapex.tailscale.tailnetPeerIsOnline
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

/**
 * Identity/metadata refresh after a successful probe runs only when metadata is missing or the
 * last fetch is older than [LanPresenceTiming.NODE_STATE_REFRESH_TTL_MS]. Claims the slot so
 * concurrent probes don't fetch twice.
 */
internal class NodeStateClaims(private val now: () -> Long = TimeUtils::now) {
    private val fetchedAtMs = ConcurrentHashMap<String, Long>()
    private val versionSeen = ConcurrentHashMap<String, Long>()

    fun claim(peer: PairedDeviceEntity, peerVersion: Long?): Boolean {
        val at = now()
        val missingMetadata = peer.rootPath.isBlank() || peer.rootPath == "/" || peer.deviceName.isBlank()
        var claimed = false
        fetchedAtMs.compute(peer.deviceId) { _, previous ->
            val stale = PeerStateRefreshPolicy.shouldFetch(
                peerVersion = peerVersion,
                seenVersion = versionSeen[peer.deviceId],
                ageMs = previous?.let { at - it },
                missingMetadata = missingMetadata
            )
            claimed = stale
            if (stale) at else previous
        }
        if (claimed && peerVersion != null) versionSeen[peer.deviceId] = peerVersion
        return claimed
    }
}

internal interface StoredEndpointHost {
    suspend fun probeHealth(host: String, port: Int, timeoutMs: Long, signal: HealthSignal?): FileApexClient.HealthProbe
    suspend fun recordEndpointMismatch(peerId: String, host: String, answeredAs: String)
    suspend fun markReachable(deviceId: String)

    /** Every attempt at the peer's LAN address was refused: the host is up and its app is not running. */
    suspend fun markRefused(deviceId: String)
    fun onBulletinVersion(peer: PairedDeviceEntity, version: Long)
    suspend fun touchLastSeenEpoch(deviceId: String)
    suspend fun touchLastSeen(deviceId: String, host: String, port: Int)
    suspend fun fetchNodeState(host: String, port: Int, timeoutMs: Long): PeerNodeState
    suspend fun applyNodeState(state: PeerNodeState, rosterDeviceId: String)
    suspend fun supersedeHardwareTwins(state: PeerNodeState)
}

internal class StoredEndpointProbe(
    private val host: StoredEndpointHost,
    private val claims: NodeStateClaims
) {
    suspend fun tryStoredEndpoint(
        peer: PairedDeviceEntity,
        attempts: Int,
        retryMs: Long,
        timeoutMs: Long,
        fetchNodeState: Boolean = true
    ): Boolean {
        val tailnetUsable = tailnetNodeUp() &&
            tailnetPeerIsOnline(TailscaleNodeRuntime.observedTailnetPeers(), peer.deviceId, peer.deviceName)
        val endpoint = resolvePeerEndpoint(peer, tailnetUsable) ?: return false
        var refusedAttempts = 0
        repeat(attempts) { attempt ->
            val signal = NotificationCompanion.pollSignalFor(
                peer.deviceId, peer.isDockerNode(), peer.os, peer.platform, peer.tlsPin
            ) ?: PhoneLink.pollSignalFor(peer)
            val probe = host.probeHealth(endpoint.host, endpoint.port, timeoutMs, signal)
            val reached = probe.reached
            if (probe.refused) refusedAttempts++
            if (signal != null) NotificationDebugLog.log("sweep link poll to ${NotificationDebugLog.short(peer.deviceId)} ${signal.query()} reached=$reached")
            if (reached) LinkedElsewhere.shared.note(peer.deviceId, probe.linkedTo)
            val answeredAs = probe.respondedAs
            if (reached && answeredAs != null && answeredAs != peer.deviceId) {
                // Someone answers at this address, but not the device stored for it.
                if (!endpoint.tailnet) host.recordEndpointMismatch(peer.deviceId, endpoint.host, answeredAs)
                return false
            }
            if (reached) {
                host.markReachable(peer.deviceId)
                probe.bulletinVersion?.let { host.onBulletinVersion(peer, it) }
                probe.chainState?.let { com.fileapex.membership.ChainStateInbox.note(peer.deviceId, it) }
                if (endpoint.tailnet) {
                    host.touchLastSeenEpoch(peer.deviceId)
                } else {
                    host.touchLastSeen(peer.deviceId, endpoint.host, endpoint.port)
                }
                if (fetchNodeState && claims.claim(peer, probe.membershipVersion ?: probe.chainState?.profileVersion)) {
                    val state = cancellableCatching {
                        host.fetchNodeState(endpoint.host, endpoint.port, timeoutMs)
                    }.getOrNull()
                    if (state != null) {
                        if (state.deviceId.trim() != peer.deviceId) {
                            if (!endpoint.tailnet) host.recordEndpointMismatch(peer.deviceId, endpoint.host, state.deviceId.trim())
                            return false
                        }
                        host.applyNodeState(state, peer.deviceId)
                        // Same hardware under another id is the same device before a reinstall: retire the outdated row.
                        if (state.hardwareHash.isNotBlank()) {
                            cancellableCatching { host.supersedeHardwareTwins(state) }
                        }
                        host.markReachable(state.deviceId.trim())
                    }
                }
                return true
            }
            if (attempt < attempts - 1) {
                delay(retryMs)
            }
        }
        com.fileapex.membership.ChainStateInbox.unreached(peer.deviceId)
        if (!endpoint.tailnet && attempts > 0 && refusedAttempts == attempts) host.markRefused(peer.deviceId)
        return false
    }
}
