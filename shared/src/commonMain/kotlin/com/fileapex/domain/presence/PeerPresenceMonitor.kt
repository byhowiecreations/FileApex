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
import com.fileapex.network.ServerLifecycleManager
import com.fileapex.network.sendWakeBroadcast
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
    private val _reachabilityEpochMs = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val _onlineSnapshotEpochMs = MutableStateFlow(0L)

    val reachabilityEpochMs: StateFlow<Map<String, Long>> = _reachabilityEpochMs.asStateFlow()
    val onlineSnapshotEpochMs: StateFlow<Long> = _onlineSnapshotEpochMs.asStateFlow()

    private val _onlineDeviceIds = MutableStateFlow<Set<String>>(emptySet())
    val onlineDeviceIds: StateFlow<Set<String>> = _onlineDeviceIds.asStateFlow()

    fun setAppInForeground(inForeground: Boolean) {
        appInForeground = inForeground
    }

    fun isDeviceOnline(device: PairedDeviceEntity): Boolean {
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
                println("PeerPresenceMonitor: background wake failed — ${error.message}")
            }
        }
    }

    suspend fun runSingleShotRevalidation() {
        runSweepOnce(SweepMode.FULL, skipFcmDispatch = true)
    }

    fun onMdnsPeerDiscovered(host: String, port: Int, hintedDeviceId: String?) {
        if (!FileApexServices.isDatabaseReady()) return
        scope.launch {
            runCatching {
                handleMdnsPeerDiscovered(host, port, hintedDeviceId)
            }.onFailure { error ->
                println("PeerPresenceMonitor: mDNS discovery failed — ${error.message}")
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
        val hint = hintedDeviceId?.trim().orEmpty()
        val peer = when {
            hint.isNotEmpty() -> mutex.withLock { repository.getDevice(hint) }
            else -> mutex.withLock {
                repository.listDevices().firstOrNull { device ->
                    device.lastKnownIp.trim() == host.trim()
                }
            }
        } ?: return

        mutex.withLock {
            repository.touchPeerLastSeen(peer.deviceId, host, port)
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

    private suspend fun runPeerRefreshSweep(mode: SweepMode, skipFcmDispatch: Boolean) {
        if (TransferActivityGuard.isTransferActive()) return
        awaitShareServerReady()
        val peers = mutex.withLock { repository.listDevices() }
        if (peers.isEmpty()) {
            refreshOnlineSnapshot()
            return
        }
        runCatching { sendWakeBroadcast() }
        val includeDiscovery = mode == SweepMode.FULL
        val allowPassiveWait = mode == SweepMode.FULL
        val discoveryBudget = if (mode == SweepMode.FULL) {
            LanPresenceTiming.STALE_PEER_LAN_DISCOVERY_BUDGET_MS
        } else {
            LanPresenceTiming.LIGHT_SWEEP_DISCOVERY_BUDGET_MS
        }
        for (peer in peers) {
            if (TransferActivityGuard.isTransferActive()) return
            primePeer(
                peer,
                includeDiscovery = includeDiscovery,
                allowPassiveWait = allowPassiveWait,
                discoveryBudgetMs = discoveryBudget
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

    suspend fun prepareForTransfer(targets: List<MultiCopyDeviceOption>) {
        val remote = targets.filter { !it.isLocal }
        if (remote.isEmpty()) return
        if (remote.all { wasRecentlyReachable(it.deviceId, LanPresenceTiming.TRANSFER_RECENT_REACHABILITY_MS) }) {
            return
        }
        primePeersForTransfer(remote)
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

        if (tryStoredEndpoint(peer, attempts, retryMs, timeoutMs)) {
            return true
        }

        if (allowPassiveWait) {
            delay(LanPresenceTiming.PASSIVE_ENDPOINT_WAIT_MS)
            val refreshed = mutex.withLock { repository.getDevice(peer.deviceId) } ?: peer
            if (refreshed.lastKnownIp != peer.lastKnownIp || refreshed.port != peer.port) {
                if (tryStoredEndpoint(refreshed, attempts, retryMs, timeoutMs)) {
                    return true
                }
            }
        }

        if (includeDiscovery) {
            val target = mutex.withLock { repository.getDevice(peer.deviceId) } ?: peer
            val discovered = PeerLanDiscovery.discoverPeerState(
                peer = target,
                client = client,
                budgetMs = discoveryBudgetMs
            )
            if (discovered != null) {
                mutex.withLock {
                    repository.applyPeerNodeState(discovered, rosterDeviceId = peer.deviceId)
                }
                markReachable(peer.deviceId, discovered.deviceId.trim())
                return true
            }
        }

        return isDeviceOnline(mutex.withLock { repository.getDevice(peer.deviceId) } ?: peer)
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
