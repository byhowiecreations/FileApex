package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.network.FileApexClient
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * On-demand LAN sweep for a paired peer when its stored endpoint is stale.
 * Scans every usable local /24 (Wi‑Fi + vendor interfaces) — critical for Mac↔Android when
 * one side stored the wrong Mac or phone IP.
 */
internal object PeerLanDiscovery {
    private const val BATCH_SIZE = 32

    suspend fun discoverPeerState(
        peer: PairedDeviceEntity,
        client: FileApexClient,
        budgetMs: Long = LanPresenceTiming.LAN_DISCOVERY_BUDGET_MS
    ): PeerNodeState? {
        val port = peer.port.takeIf { it > 0 } ?: return null
        val scanRoots = subnetScanRoots()
        if (scanRoots.isEmpty()) return null

        val deadlineEpochMs = TimeUtils.now() + budgetMs
        val perRootBudgetMs = (budgetMs / scanRoots.size).coerceAtLeast(1_000L)
        for (root in scanRoots) {
            if (TimeUtils.now() >= deadlineEpochMs) break
            val remainingMs = (deadlineEpochMs - TimeUtils.now()).coerceAtLeast(250L)
            val rootBudgetMs = remainingMs.coerceAtMost(perRootBudgetMs)
            val match = discoverOnSubnet(
                peer = peer,
                client = client,
                localIp = root,
                port = port,
                budgetMs = rootBudgetMs
            )
            if (match != null) return match
        }
        return null
    }

    private suspend fun discoverOnSubnet(
        peer: PairedDeviceEntity,
        client: FileApexClient,
        localIp: String,
        port: Int,
        budgetMs: Long
    ): PeerNodeState? {
        val deadlineEpochMs = TimeUtils.now() + budgetMs
        val candidates = orderedSubnetCandidates(localIp)
        for (batch in candidates.chunked(BATCH_SIZE)) {
            if (TimeUtils.now() >= deadlineEpochMs) break
            val remainingMs = (deadlineEpochMs - TimeUtils.now()).coerceAtLeast(250L)
            val match = withTimeoutOrNull(remainingMs) {
                scanBatch(batch, port, peer, client)
            }
            if (match != null) return match
        }
        return null
    }

    private fun subnetScanRoots(): List<String> {
        val roots = NetworkUtils.lanBindCandidates()
            .filter { NetworkUtils.isUsableLanIpv4(it) }
            .distinct()
        if (roots.isNotEmpty()) {
            return roots
        }
        return NetworkUtils.preferredLanIpv4()
            .takeIf { NetworkUtils.isUsableLanIpv4(it) }
            ?.let { listOf(it) }
            .orEmpty()
    }

    private suspend fun scanBatch(
        hosts: List<String>,
        port: Int,
        peer: PairedDeviceEntity,
        client: FileApexClient
    ): PeerNodeState? = coroutineScope {
        hosts.map { host ->
            async(Dispatchers.IO) {
                probeIdentityMatch(host, port, peer, client)
            }
        }.awaitAll().firstOrNull { it != null }
    }

    private suspend fun probeIdentityMatch(
        host: String,
        port: Int,
        peer: PairedDeviceEntity,
        client: FileApexClient
    ): PeerNodeState? {
        if (!client.pingHealth(host, port, LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS)) {
            return null
        }
        val state = runCatching {
            client.fetchPeerNodeState(host, port, LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS)
        }.getOrNull() ?: return null
        return state.takeIf { matchesPeer(peer, it) }
    }

    private fun matchesPeer(peer: PairedDeviceEntity, state: PeerNodeState): Boolean {
        val targetId = peer.deviceId.trim()
        if (targetId.isNotEmpty() && state.deviceId.trim() == targetId) {
            return true
        }
        val targetHash = peer.publicKeyHash.trim()
        val stateHash = state.publicKeyHash.trim()
        return targetHash.isNotEmpty() && targetHash == stateHash
    }

    private fun orderedSubnetCandidates(localIp: String): List<String> {
        val parts = localIp.split('.')
        if (parts.size != 4) return emptyList()
        val selfOctet = parts[3].toIntOrNull()?.takeIf { it in 1..254 } ?: return emptyList()
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
        return buildList {
            for (delta in 1..254) {
                val lower = selfOctet - delta
                if (lower in 1..254) add("$prefix.$lower")
                val upper = selfOctet + delta
                if (upper in 1..254) add("$prefix.$upper")
            }
        }
    }
}
