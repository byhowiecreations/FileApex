package com.fileapex.domain.presence

import com.fileapex.cloud.FcmWakeCoordinator
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.DeviceRepository
import com.fileapex.di.FileApexServices
import com.fileapex.domain.transfer.MultiCopyDeviceOption
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.network.FileApexClient
import com.fileapex.network.FileApexMdnsBrowser
import com.fileapex.network.PeerLanHttpPolicy
import com.fileapex.network.ServerLifecycleManager
import com.fileapex.network.sendWakeBroadcast
import com.fileapex.platform.isActiveLanConnectivity
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
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
 * Intent-driven peer reachability with battery-first background sweeps.
 *
 * - One coalesced sweep at a time (no overlapping LAN work)
 * - Full sweeps when UI is foreground / network changes / cold launch
 * - Light sweeps in background (health ping only, skip when all peers fresh)
 * - Yields during active file transfers
 */
class PeerPresenceMonitor(
    private val repository: DeviceRepository,
    private val client: FileApexClient
) {
    private enum class SweepMode {
        /** Health probe + discovery on failure + debounced self-broadcast / FCM. */
        FULL,
        /** Health ping only — no subnet scan, no broadcast storm. */
        LIGHT
    }

    private val mutex = Mutex()
    private val reachabilityLock = Mutex()
    private val sweepMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var snapshotWatcherJob: Job? = null
    private var lanPollJob: Job? = null
    @Volatile
    private var coldLaunchProbeScheduled = false
    @Volatile
    private var lastForegroundRefreshEpochMs = 0L
    @Volatile
    private var appInForeground = false
    @Volatile
    private var coalescePendingMode: SweepMode? = null
    @Volatile
    private var lastFcmWakeDispatchEpochMs = 0L
    @Volatile
    private var lastSelfBroadcastEpochMs = 0L

    private val lastReachableEpochById = mutableMapOf<String, Long>()
    private val discoveredMdnsEndpoints = mutableMapOf<Pair<String, Int>, Long>()
    private val _reachabilityEpochMs = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val _onlineSnapshotEpochMs = MutableStateFlow(0L)

    val reachabilityEpochMs: StateFlow<Map<String, Long>> = _reachabilityEpochMs.asStateFlow()
    val onlineSnapshotEpochMs: StateFlow<Long> = _onlineSnapshotEpochMs.asStateFlow()

    private val _onlineDeviceIds = MutableStateFlow<Set<String>>(emptySet())
    val onlineDeviceIds: StateFlow<Set<String>> = _onlineDeviceIds.asStateFlow()

    suspend fun getDiscoveredEndpoints(): List<Pair<String, Int>> = mutex.withLock {
        val cutoff = TimeUtils.now() - 300_000L
        discoveredMdnsEndpoints.entries.removeAll { it.value < cutoff }
        discoveredMdnsEndpoints.keys.toList()
    }

    fun setAppInForeground(inForeground: Boolean) {
        appInForeground = inForeground
    }

    fun isDeviceOnline(device: PairedDeviceEntity): Boolean {
        if (!hasUsableEndpoint(device)) return false
        val probeEpoch = _reachabilityEpochMs.value[device.deviceId] ?: 0L
        val lastSeen = maxOf(probeEpoch, device.lastSeenEpochMs)
        return TimeUtils.isWithinWindow(lastSeen, LanPresenceTiming.PRESENCE_READY_THRESHOLD_MS)
    }

    fun ensureOnlineSnapshotWatcher() {
        if (snapshotWatcherJob?.isActive == true) return
        snapshotWatcherJob = scope.launch {
            refreshOnlineSnapshot()
            while (isActive) {
                delay(LanPresenceTiming.ONLINE_SNAPSHOT_REFRESH_MS)
                refreshOnlineSnapshot()
            }
        }
    }

    /**
     * Battery-first LAN poll: 60s foreground / 5 min background; defers during transfers.
     */
    fun ensureLanPollLoop() {
        if (lanPollJob?.isActive == true) return
        lanPollJob = scope.launch {
            launchSweep(SweepMode.FULL)
            while (isActive) {
                if (TransferActivityGuard.isTransferActive()) {
                    delay(LanPresenceTiming.TRANSFER_DEFER_POLL_MS)
                    continue
                }
                val interval = if (appInForeground) {
                    LanPresenceTiming.FOREGROUND_LAN_POLL_MS
                } else {
                    LanPresenceTiming.BACKGROUND_LAN_POLL_MS
                }
                delay(interval)
                if (TransferActivityGuard.isTransferActive()) continue
                val mode = if (appInForeground) SweepMode.FULL else SweepMode.LIGHT
                if (mode == SweepMode.LIGHT &&
                    allPeersRecentlyReachable(LanPresenceTiming.PEER_FRESH_SKIP_SWEEP_MS)
                ) {
                    refreshOnlineSnapshot()
                    continue
                }
                launchSweep(mode)
            }
        }
    }

    @Deprecated("Use ensureLanPollLoop()", ReplaceWith("ensureLanPollLoop()"))
    fun ensureDesktopLanPoll() = ensureLanPollLoop()

    fun scheduleColdLaunchProbeOnce() {
        if (coldLaunchProbeScheduled) return
        coldLaunchProbeScheduled = true
        launchSweep(SweepMode.FULL)
    }

    fun refreshPeersOnForeground() {
        val now = TimeUtils.now()
        if (now - lastForegroundRefreshEpochMs < LanPresenceTiming.FOREGROUND_REFRESH_DEBOUNCE_MS) {
            return
        }
        lastForegroundRefreshEpochMs = now
        launchSweep(SweepMode.FULL)
    }

    fun onBackgroundWakeSignal(sourceDeviceId: String?) {
        if (!FileApexServices.isDatabaseReady()) return
        scope.launch {
            runCatching {
                onBackgroundWakeSignalInternal(sourceDeviceId)
            }.onFailure { error ->
                println("PeerPresenceMonitor: background wake failed - ${error.message}")
            }
        }
    }

    suspend fun runSingleShotRevalidation() {
        runSweepOnce(SweepMode.FULL, skipFcmDispatch = true)
    }

    fun onMdnsPeerDiscovered(host: String, port: Int, hintedDeviceId: String?) {
        val cleanedHost = host.trim()
        if (cleanedHost.isNotEmpty() && port > 0) {
            scope.launch {
                mutex.withLock {
                    discoveredMdnsEndpoints[cleanedHost to port] = TimeUtils.now()
                }
            }
        }
        if (!FileApexServices.isDatabaseReady()) return
        scope.launch {
            runCatching {
                handleMdnsPeerDiscovered(cleanedHost, port, hintedDeviceId)
            }.onFailure { error ->
                println("PeerPresenceMonitor: mDNS discovery failed - ${error.message}")
            }
        }
    }

    private fun launchSweep(mode: SweepMode) {
        scope.launch {
            runSweepOnce(mode, skipFcmDispatch = false)
        }
    }

    private suspend fun runSweepOnce(requestedMode: SweepMode, skipFcmDispatch: Boolean) {
        if (!sweepMutex.tryLock()) {
            coalescePendingMode = mergeSweepMode(coalescePendingMode, requestedMode)
            return
        }
        try {
            var mode = requestedMode
            do {
                coalescePendingMode = null
                runPeerRefreshSweep(mode, skipFcmDispatch)
                val pending = coalescePendingMode
                if (pending == null) break
                coalescePendingMode = null
                mode = pending
            } while (true)
        } finally {
            sweepMutex.unlock()
            val pending = coalescePendingMode
            if (pending != null) {
                coalescePendingMode = null
                runSweepOnce(pending, skipFcmDispatch = skipFcmDispatch)
            }
        }
    }

    private suspend fun onBackgroundWakeSignalInternal(sourceDeviceId: String?) {
        awaitShareServerReady()
        val trimmedSource = sourceDeviceId?.trim().orEmpty()
        if (trimmedSource.isNotEmpty()) {
            val peer = mutex.withLock { repository.getDevice(trimmedSource) }
            if (peer != null) {
                primePeer(
                    peer,
                    includeDiscovery = false,
                    allowPassiveWait = false,
                    discoveryBudgetMs = LanPresenceTiming.LIGHT_SWEEP_DISCOVERY_BUDGET_MS
                )
                refreshOnlineSnapshot()
                return
            }
        }
        runPeerRefreshSweep(SweepMode.LIGHT, skipFcmDispatch = true)
    }

    private suspend fun handleMdnsPeerDiscovered(host: String, port: Int, hintedDeviceId: String?) {
        val cleanedHost = host.trim()
        if (cleanedHost.isEmpty() || port <= 0) return
        val peer = resolveMdnsPeer(cleanedHost, port, hintedDeviceId) ?: return

        mutex.withLock {
            repository.touchPeerLastSeen(peer.deviceId, cleanedHost, port)
        }
        val refreshed = mutex.withLock { repository.getDevice(peer.deviceId) } ?: peer
        primePeer(
            refreshed,
            includeDiscovery = false,
            allowPassiveWait = false,
            discoveryBudgetMs = LanPresenceTiming.LIGHT_SWEEP_DISCOVERY_BUDGET_MS
        )
        refreshOnlineSnapshot()
    }

    /**
     * Map an mDNS-resolved endpoint to a roster row. Hinted ids can carry Bonjour conflict
     * suffixes; blank-IP rows are matched by probing /identity at the resolved host.
     */
    private suspend fun resolveMdnsPeer(
        host: String,
        port: Int,
        hintedDeviceId: String?
    ): PairedDeviceEntity? {
        val hint = hintedDeviceId?.trim().orEmpty()
        if (hint.isNotEmpty()) {
            mutex.withLock { repository.getDevice(hint) }?.let { return it }
        }
        mutex.withLock {
            repository.listDevices().firstOrNull { device ->
                device.lastKnownIp.trim() == host
            }
        }?.let { return it }

        val state = runCatching {
            client.fetchPeerNodeState(host, port, LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS)
        }.getOrNull() ?: return null
        val stateId = state.deviceId.trim()
        val stateHash = state.publicKeyHash.trim()
        val matched = mutex.withLock {
            repository.getDevice(stateId)
                ?: repository.listDevices().firstOrNull { device ->
                    val hash = device.publicKeyHash.trim()
                    hash.isNotEmpty() && hash == stateHash
                }
        } ?: return null
        mutex.withLock {
            repository.applyPeerNodeState(state, rosterDeviceId = matched.deviceId)
        }
        return mutex.withLock { repository.getDevice(matched.deviceId) } ?: matched
    }

    private suspend fun runPeerRefreshSweep(mode: SweepMode, skipFcmDispatch: Boolean) {
        if (TransferActivityGuard.isTransferActive()) return
        if (!isActiveLanConnectivity()) {
            refreshOnlineSnapshot()
            return
        }
        awaitShareServerReady()
        val peers = mutex.withLock { repository.listDevices() }
        if (peers.isEmpty()) {
            refreshOnlineSnapshot()
            if (mode == SweepMode.FULL) {
                runCatching { GoogleLinkCoordinator.publishSelfPresenceIfLinked() }
            }
            return
        }
        val orderedPeers = peers.sortedWith(
            compareBy<PairedDeviceEntity> { peer ->
                if (hasUsableEndpoint(peer)) 1 else 0
            }.thenBy { it.deviceName.lowercase() }
        )
        runCatching { sendWakeBroadcast() }
        val includeDiscovery = mode == SweepMode.FULL
        val allowPassiveWait = mode == SweepMode.FULL
        val staleDiscoveryBudget = if (mode == SweepMode.FULL) {
            LanPresenceTiming.STALE_PEER_LAN_DISCOVERY_BUDGET_MS
        } else {
            LanPresenceTiming.LIGHT_SWEEP_DISCOVERY_BUDGET_MS
        }
        for (peer in orderedPeers) {
            if (TransferActivityGuard.isTransferActive()) return
            primePeer(
                peer,
                includeDiscovery = includeDiscovery,
                allowPassiveWait = allowPassiveWait,
                discoveryBudgetMs = staleDiscoveryBudget
            )
        }
        if (mode == SweepMode.FULL) {
            maybeBroadcastSelfIdentity()
        }
        refreshOnlineSnapshot()
        runCatching { GoogleLinkCoordinator.publishSelfPresenceIfLinked() }
        if (mode == SweepMode.FULL && !skipFcmDispatch) {
            maybeDispatchFcmWake()
        }
    }

    private suspend fun maybeBroadcastSelfIdentity() {
        val now = TimeUtils.now()
        if (now - lastSelfBroadcastEpochMs < LanPresenceTiming.SELF_BROADCAST_MIN_INTERVAL_MS) {
            return
        }
        lastSelfBroadcastEpochMs = now
        runCatching { FileApexServices.pairingCoordinator.broadcastSelfIdentity() }
    }

    private fun maybeDispatchFcmWake() {
        val now = TimeUtils.now()
        if (now - lastFcmWakeDispatchEpochMs < LanPresenceTiming.FCM_WAKE_MIN_INTERVAL_MS) {
            return
        }
        lastFcmWakeDispatchEpochMs = now
        FcmWakeCoordinator.dispatchPresenceWakeToLinkedPeers()
    }

    private suspend fun allPeersRecentlyReachable(withinMs: Long): Boolean {
        val peers = mutex.withLock { repository.listDevices() }
        if (peers.isEmpty()) return true
        return peers.all { wasRecentlyReachable(it.deviceId, withinMs) }
    }

    private suspend fun awaitShareServerReady() {
        repeat(SERVER_READY_ATTEMPTS) {
            if (ServerLifecycleManager.isRunning) {
                delay(SERVER_SETTLE_MS)
                return
            }
            delay(SERVER_READY_POLL_MS)
        }
    }

    suspend fun notifyPassiveReachability(vararg deviceIds: String, epochMs: Long = TimeUtils.now()) {
        markReachable(*deviceIds, epochMs = epochMs)
        refreshOnlineSnapshot()
    }

    suspend fun refreshOnlineSnapshot() {
        val peers = mutex.withLock { repository.listDevices() }
        publishStableOnlineIds(peers)
        _onlineSnapshotEpochMs.value = TimeUtils.now()
    }

    suspend fun validatePeerOnDemand(peer: PairedDeviceEntity): Boolean {
        runCatching { sendWakeBroadcast() }
        val reached = primePeer(
            peer,
            includeDiscovery = true,
            allowPassiveWait = true,
            discoveryBudgetMs = LanPresenceTiming.STALE_PEER_LAN_DISCOVERY_BUDGET_MS
        )
        refreshOnlineSnapshot()
        return reached
    }

    /**
     * Single quick LAN assessment for transfer, file navigation, and Device Details.
     * Uses an ~800ms health ping only — never runs the full discovery sweep.
     * Prefer [resolveOutboundEndpoint] for tap-to-browse and sends when the roster IP may be blank.
     */
    suspend fun quickAssessLanReachability(peer: PairedDeviceEntity): PeerLanReachabilityVerdict {
        if (!isActiveLanConnectivity()) {
            return PeerLanReachabilityVerdict.LocalOffLocalWifi
        }
        val refreshed = mutex.withLock { repository.getDevice(peer.deviceId) } ?: peer
        assessStoredEndpoint(refreshed)?.let { return it }
        return PeerLanReachabilityVerdict.PeerOffline
    }

    /**
     * Outbound LAN host:port for navigation, transfer, and queue drain.
     * Runs mDNS + discovery when the roster row lacks a usable IP (QR cluster seed).
     */
    suspend fun resolveOutboundEndpoint(peer: PairedDeviceEntity): PeerLanReachabilityVerdict.Direct? {
        if (!isActiveLanConnectivity()) return null
        val live = mutex.withLock { repository.getDevice(peer.deviceId) } ?: peer
        val host = live.lastKnownIp.trim()
        val port = live.port
        if (host.isNotEmpty() && NetworkUtils.isPrivateLanPeerHost(host) && port > 0) {
            return PeerLanReachabilityVerdict.Direct(host, port)
        }
        return null
    }

    private suspend fun assessStoredEndpoint(
        peer: PairedDeviceEntity
    ): PeerLanReachabilityVerdict.Direct? {
        val host = peer.lastKnownIp.trim()
        val port = peer.port
        if (host.isEmpty() || !NetworkUtils.isPrivateLanPeerHost(host) || !PeerLanHttpPolicy.canRoute(host)) {
            return null
        }
        if (wasRecentlyReachable(
                peer.deviceId,
                LanPresenceTiming.DEVICE_DETAILS_RECENT_REACHABILITY_MS
            )
        ) {
            return PeerLanReachabilityVerdict.Direct(host, port)
        }
        if (client.pingHealth(host, port, LanPresenceTiming.DEVICE_DETAILS_PING_TIMEOUT_MS)) {
            markReachable(peer.deviceId)
            mutex.withLock {
                repository.touchPeerLastSeen(peer.deviceId, host, port)
            }
            return PeerLanReachabilityVerdict.Direct(host, port)
        }
        return null
    }

    suspend fun prepareForTransfer(targets: List<MultiCopyDeviceOption>) {
        val remote = targets.filter { !it.isLocal }
        if (remote.isEmpty()) return
        val needsPrime = remote.filter { target ->
            val peer = mutex.withLock { repository.getDevice(target.deviceId) } ?: return@filter true
            if (!wasRecentlyReachable(peer.deviceId, LanPresenceTiming.TRANSFER_RECENT_REACHABILITY_MS)) {
                return@filter true
            }
            val host = peer.lastKnownIp.trim()
            if (host.isEmpty() || !PeerLanHttpPolicy.canRoute(host)) {
                return@filter true
            }
            !client.pingHealth(host, peer.port, LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS)
        }
        if (needsPrime.isEmpty()) return
        primePeersForTransfer(needsPrime)
    }

    suspend fun primePeersForTransfer(targets: List<MultiCopyDeviceOption>) {
        if (targets.isEmpty()) return
        runCatching { sendWakeBroadcast() }
        val attempts = LanPresenceTiming.ON_DEMAND_PRIME_ATTEMPTS
        val retryMs = LanPresenceTiming.ON_DEMAND_PRIME_RETRY_MS
        val timeoutMs = LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS
        for (target in targets.filter { !it.isLocal }) {
            val peer = mutex.withLock { repository.getDevice(target.deviceId) } ?: continue
            if (tryStoredEndpoint(peer, attempts, retryMs, timeoutMs, fetchNodeState = false)) {
                continue
            }
            runCatching { FileApexMdnsBrowser.requestProbe() }
            delay(LanPresenceTiming.TRANSFER_MDNS_SETTLE_MS)
            val refreshed = mutex.withLock { repository.getDevice(target.deviceId) } ?: peer
            if (tryStoredEndpoint(refreshed, attempts, retryMs, timeoutMs, fetchNodeState = false)) {
                continue
            }
            primePeer(
                refreshed,
                includeDiscovery = true,
                allowPassiveWait = false,
                discoveryBudgetMs = LanPresenceTiming.STALE_PEER_LAN_DISCOVERY_BUDGET_MS
            )
        }
        refreshOnlineSnapshot()
    }

    private fun wasRecentlyReachable(deviceId: String, withinMs: Long): Boolean {
        val epoch = _reachabilityEpochMs.value[deviceId] ?: return false
        return TimeUtils.isWithinWindow(epoch, withinMs)
    }

    private suspend fun primePeer(
        peer: PairedDeviceEntity,
        includeDiscovery: Boolean,
        allowPassiveWait: Boolean,
        discoveryBudgetMs: Long
    ): Boolean {
        val attempts = LanPresenceTiming.ON_DEMAND_PRIME_ATTEMPTS
        val retryMs = LanPresenceTiming.ON_DEMAND_PRIME_RETRY_MS
        val timeoutMs = LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS

        var current = mutex.withLock { repository.getDevice(peer.deviceId) } ?: peer
        if (!hasUsableEndpoint(current)) {
            runCatching { FileApexMdnsBrowser.requestProbe() }
            delay(LanPresenceTiming.TRANSFER_MDNS_SETTLE_MS)
            current = mutex.withLock { repository.getDevice(peer.deviceId) } ?: current
        }

        if (tryStoredEndpoint(current, attempts, retryMs, timeoutMs)) {
            return true
        }

        if (allowPassiveWait) {
            delay(LanPresenceTiming.PASSIVE_ENDPOINT_WAIT_MS)
            val refreshed = mutex.withLock { repository.getDevice(peer.deviceId) } ?: current
            if (refreshed.lastKnownIp != current.lastKnownIp || refreshed.port != current.port) {
                if (tryStoredEndpoint(refreshed, attempts, retryMs, timeoutMs)) {
                    return true
                }
            }
            current = refreshed
        }

        val shouldDiscover = includeDiscovery || !hasUsableEndpoint(current)
        if (shouldDiscover) {
            val target = mutex.withLock { repository.getDevice(peer.deviceId) } ?: current
            val discoveryBudget = if (hasUsableEndpoint(target)) {
                LanPresenceTiming.LIGHT_SWEEP_DISCOVERY_BUDGET_MS
            } else {
                discoveryBudgetMs
            }
            val discovered = PeerLanDiscovery.discoverPeerState(
                peer = target,
                client = client,
                budgetMs = discoveryBudget
            )
            if (discovered != null) {
                mutex.withLock {
                    repository.applyPeerNodeState(discovered, rosterDeviceId = peer.deviceId)
                }
                markReachable(peer.deviceId, discovered.deviceId.trim())
                return true
            }
        }

        val refreshed = mutex.withLock { repository.getDevice(peer.deviceId) } ?: current
        return hasUsableEndpoint(refreshed)
    }

    internal fun hasUsableEndpoint(peer: PairedDeviceEntity): Boolean {
        val host = peer.lastKnownIp.trim()
        return host.isNotEmpty() &&
            host != "127.0.0.1" &&
            host != "0.0.0.0" &&
            NetworkUtils.isPrivateLanPeerHost(host) &&
            peer.port > 0
    }

    private suspend fun tryStoredEndpoint(
        peer: PairedDeviceEntity,
        attempts: Int,
        retryMs: Long,
        timeoutMs: Long,
        fetchNodeState: Boolean = true
    ): Boolean {
        val host = peer.lastKnownIp.trim()
        if (host.isEmpty() || host == "127.0.0.1" || host == "0.0.0.0") {
            return false
        }
        if (!NetworkUtils.isPrivateLanPeerHost(host)) {
            return false
        }
        repeat(attempts) { attempt ->
            if (client.pingHealth(host, peer.port, timeoutMs)) {
                markReachable(peer.deviceId)
                if (fetchNodeState) {
                    val state = runCatching {
                        client.fetchPeerNodeState(host, peer.port, timeoutMs)
                    }.getOrNull()
                    if (state != null) {
                        mutex.withLock {
                            repository.applyPeerNodeState(state, rosterDeviceId = peer.deviceId)
                        }
                        markReachable(state.deviceId.trim())
                        return true
                    }
                }
                mutex.withLock {
                    repository.touchPeerLastSeen(peer.deviceId, host, peer.port)
                }
                return true
            }
            if (attempt < attempts - 1) {
                delay(retryMs)
            }
        }
        return false
    }

    private suspend fun markReachable(vararg deviceIds: String, epochMs: Long = TimeUtils.now()) {
        reachabilityLock.withLock {
            var changed = false
            for (id in deviceIds) {
                val trimmed = id.trim()
                if (trimmed.isEmpty()) continue
                val previous = lastReachableEpochById[trimmed] ?: 0L
                val next = epochMs.coerceAtLeast(previous)
                if (next > previous) {
                    lastReachableEpochById[trimmed] = next
                    changed = true
                }
            }
            if (changed) {
                _reachabilityEpochMs.value = lastReachableEpochById.toMap()
            }
        }
    }

    private suspend fun publishStableOnlineIds(devices: List<PairedDeviceEntity>) {
        val nextOnline = devices.filter { isDeviceOnline(it) }.map { it.deviceId }.toSet()
        reachabilityLock.withLock {
            if (_onlineDeviceIds.value != nextOnline) {
                _onlineDeviceIds.value = nextOnline
            }
        }
    }

    private fun mergeSweepMode(existing: SweepMode?, incoming: SweepMode): SweepMode {
        if (existing == SweepMode.FULL || incoming == SweepMode.FULL) return SweepMode.FULL
        return SweepMode.LIGHT
    }

    companion object {
        const val OFFLINE_GRACE_MS = LanPresenceTiming.OFFLINE_GRACE_MS
        private const val SERVER_READY_ATTEMPTS = 25
        private const val SERVER_READY_POLL_MS = 100L
        private const val SERVER_SETTLE_MS = 250L
    }
}
