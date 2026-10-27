package com.fileapex.domain.presence

import com.fileapex.data.bulletin.BulletinPeerBackoff
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.db.isDockerNode
import com.fileapex.domain.notifications.NotificationCompanion
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/** Devices that answered recently are asked what they have seen of the others, so the others can skip a ping. */
internal class DigestRound(val sourceIds: Set<String>) {
    val hints = ConcurrentHashMap<String, Long>()
    val done = CompletableDeferred<Unit>()
    private val waiting = java.util.concurrent.atomic.AtomicInteger(sourceIds.size)
    private val finished = ConcurrentHashMap.newKeySet<String>()

    fun finish(id: String) {
        if (id in sourceIds && finished.add(id) && waiting.decrementAndGet() == 0) done.complete(Unit)
    }
}

internal interface DigestPeerView {
    fun hasUsableEndpoint(peer: PairedDeviceEntity): Boolean
    fun isOnline(peer: PairedDeviceEntity): Boolean
    fun wasRecentlyReachable(deviceId: String, withinMs: Long): Boolean
    suspend fun liveDevice(deviceId: String): PairedDeviceEntity?
    suspend fun endpointFor(peer: PairedDeviceEntity): PeerLanReachabilityVerdict.Direct?
    suspend fun fetchDigest(host: String, port: Int, ask: PresenceDigestMessage): PresenceDigestMessage?
}

internal class PresenceDigestRounds(private val view: DigestPeerView) {
    val skips = DigestSkipLog()
    private val support = BulletinPeerBackoff(DIGEST_UNSUPPORTED_RETRY_MS, DIGEST_UNSUPPORTED_RETRY_MS)
    private val clockSkewMs = ConcurrentHashMap<String, Long>()

    fun startRound(peers: List<PairedDeviceEntity>): DigestRound? {
        val sources = pickSources(peers.sortedBy { it.deviceName.lowercase() })
        return if (sources.isEmpty()) null else DigestRound(sources.map { it.deviceId }.toSet())
    }

    fun pickSources(orderedPeers: List<PairedDeviceEntity>): List<PairedDeviceEntity> {
        if (orderedPeers.size < DIGEST_MIN_PEERS) return emptyList()
        val now = TimeUtils.now()
        return orderedPeers.asSequence()
            .filter { it.publicKey.isNotBlank() && !it.isDockerNode() }
            .filter { view.hasUsableEndpoint(it) && view.isOnline(it) }
            .filter { !NotificationCompanion.isCompanion(it.deviceId) }
            .filter { support.canAttempt(it.deviceId, now) }
            .take(DIGEST_MAX_SOURCES)
            .toList()
    }

    suspend fun collectHints(
        sources: List<PairedDeviceEntity>,
        freshnessThresholdMs: Long
    ): Map<String, Long> {
        val localId = com.fileapex.data.identity.loadLocalIdentity().deviceId
        val hints = HashMap<String, Long>()
        for (source in sources) {
            if (!view.wasRecentlyReachable(source.deviceId, freshnessThresholdMs)) continue
            val live = view.liveDevice(source.deviceId) ?: source
            val endpoint = view.endpointFor(live) ?: continue
            val stamp = PresenceDigests.newStamp()
            val ask = PresenceDigests.buildAsk(localId, live, stamp, TimeUtils.now()) ?: continue
            val startedAt = TimeUtils.now()
            val result = cancellableCatching { view.fetchDigest(endpoint.host, endpoint.port, ask) }
            if (result.isFailure) continue
            val reply = result.getOrNull()
            if (reply == null) {
                support.onFailure(source.deviceId, TimeUtils.now())
                continue
            }
            val receivedAt = TimeUtils.now()
            val opened = PresenceDigests.open(reply, live, stamp, localId) ?: continue
            support.onSuccess(source.deviceId)
            val halfTrip = (receivedAt - startedAt) / 2
            clockSkewMs[source.deviceId] = receivedAt - halfTrip - opened.sentAtUtcMs
            for (seen in opened.seen) {
                hints.merge(seen.id, receivedAt - halfTrip - seen.ageMs) { a, b -> maxOf(a, b) }
            }
        }
        println("PeerPresenceMonitor: digest round named ${hints.size} peers from ${sources.size} sources")
        return hints
    }

    fun allowsSkip(peer: PairedDeviceEntity, hints: Map<String, Long>): Boolean {
        val reachedAt = hints[peer.deviceId] ?: return false
        if (!TimeUtils.isWithinWindow(reachedAt, PresenceDigests.MAX_ENTRY_AGE_MS)) return false
        if (!view.hasUsableEndpoint(peer) || !view.isOnline(peer)) return false
        if (peer.rootPath.isBlank() || peer.rootPath == "/") return false
        if (NotificationCompanion.isCompanion(peer.deviceId)) return false
        return skips.trySkip(peer.deviceId).also { if (it) println("PeerPresenceMonitor: digest skip ${peer.deviceId}") }
    }

    companion object {
        const val WAIT_MS = 800L
        private const val DIGEST_MIN_PEERS = 4
        private const val DIGEST_UNSUPPORTED_RETRY_MS = 6 * 60 * 60_000L
        private const val DIGEST_MAX_SOURCES = 2
    }
}
