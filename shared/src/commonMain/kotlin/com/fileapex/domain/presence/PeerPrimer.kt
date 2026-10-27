package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.domain.transfer.MultiCopyDeviceOption
import com.fileapex.network.FileApexMdnsBrowser
import com.fileapex.network.sendWakeBroadcast
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal interface PrimerHost {
    suspend fun liveDevice(deviceId: String): PairedDeviceEntity?
    fun hasUsableEndpoint(peer: PairedDeviceEntity): Boolean
    suspend fun tryStoredEndpoint(
        peer: PairedDeviceEntity,
        attempts: Int,
        retryMs: Long,
        timeoutMs: Long,
        fetchNodeState: Boolean
    ): Boolean
    fun shouldScan(deviceId: String, networkKey: String): Boolean
    fun recordScanFailure(deviceId: String, networkKey: String)
    suspend fun discover(target: PairedDeviceEntity, budgetMs: Long): PeerNodeState?
    suspend fun applyDiscovered(state: PeerNodeState, rosterDeviceId: String)
    suspend fun markReachable(deviceId: String, discoveredId: String)
}

internal class PeerPrimer(private val host: PrimerHost) {
    suspend fun primePeer(
        peer: PairedDeviceEntity,
        includeDiscovery: Boolean,
        allowPassiveWait: Boolean,
        discoveryBudgetMs: Long,
        bypassScanBackoff: Boolean = false
    ): Boolean {
        val attempts = LanPresenceTiming.ON_DEMAND_PRIME_ATTEMPTS
        val retryMs = LanPresenceTiming.ON_DEMAND_PRIME_RETRY_MS
        val timeoutMs = LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS

        var current = host.liveDevice(peer.deviceId) ?: peer
        if (!host.hasUsableEndpoint(current)) {
            cancellableCatching { FileApexMdnsBrowser.requestProbe() }
            delay(LanPresenceTiming.TRANSFER_MDNS_SETTLE_MS)
            current = host.liveDevice(peer.deviceId) ?: current
        }

        if (host.tryStoredEndpoint(current, attempts, retryMs, timeoutMs, true)) {
            return true
        }

        if (allowPassiveWait) {
            delay(LanPresenceTiming.PASSIVE_ENDPOINT_WAIT_MS)
            val refreshed = host.liveDevice(peer.deviceId) ?: current
            if (refreshed.lastKnownIp != current.lastKnownIp || refreshed.port != current.port) {
                if (host.tryStoredEndpoint(refreshed, attempts, retryMs, timeoutMs, true)) {
                    return true
                }
            }
            current = refreshed
        }

        val shouldDiscover = includeDiscovery || !host.hasUsableEndpoint(current)
        val scanNetworkKey = LanScanBackoff.currentNetworkKey()
        if (shouldDiscover && !bypassScanBackoff && !host.shouldScan(peer.deviceId, scanNetworkKey)) {
            return false
        }
        if (shouldDiscover) {
            val target = host.liveDevice(peer.deviceId) ?: current
            val discoveryBudget = if (host.hasUsableEndpoint(target)) {
                LanPresenceTiming.LIGHT_SWEEP_DISCOVERY_BUDGET_MS
            } else {
                discoveryBudgetMs
            }
            val discovered = host.discover(target, discoveryBudget)
            if (discovered == null || discovered.deviceId.trim() != peer.deviceId) {
                host.recordScanFailure(peer.deviceId, scanNetworkKey)
            }
            if (discovered != null && discovered.deviceId.trim() == peer.deviceId) {
                host.applyDiscovered(discovered, peer.deviceId)
                host.markReachable(peer.deviceId, discovered.deviceId.trim())
                return true
            }
        }

        val refreshed = host.liveDevice(peer.deviceId) ?: current
        return host.hasUsableEndpoint(refreshed)
    }

    suspend fun primeForTransfer(targets: List<MultiCopyDeviceOption>, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            cancellableCatching { sendWakeBroadcast() }
        }
        val attempts = LanPresenceTiming.ON_DEMAND_PRIME_ATTEMPTS
        val retryMs = LanPresenceTiming.ON_DEMAND_PRIME_RETRY_MS
        val timeoutMs = LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS
        coroutineScope {
            targets.filter { !it.isLocal }.map { target ->
                async(Dispatchers.IO) {
                    val peer = host.liveDevice(target.deviceId) ?: return@async
                    if (host.tryStoredEndpoint(peer, attempts, retryMs, timeoutMs, false)) {
                        return@async
                    }
                    cancellableCatching { FileApexMdnsBrowser.requestProbe() }
                    delay(LanPresenceTiming.TRANSFER_MDNS_SETTLE_MS)
                    val refreshed = host.liveDevice(target.deviceId) ?: peer
                    if (host.tryStoredEndpoint(refreshed, attempts, retryMs, timeoutMs, false)) {
                        return@async
                    }
                    primePeer(
                        refreshed,
                        includeDiscovery = true,
                        allowPassiveWait = false,
                        discoveryBudgetMs = LanPresenceTiming.STALE_PEER_LAN_DISCOVERY_BUDGET_MS,
                        bypassScanBackoff = true
                    )
                }
            }.awaitAll()
        }
    }
}
