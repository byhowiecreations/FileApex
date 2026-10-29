package com.fileapex.domain.presence

import com.fileapex.domain.notifications.NotificationCompanion
import com.fileapex.cloud.FcmWakeCoordinator
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.DeviceRepository
import com.fileapex.di.FileApexServices
import com.fileapex.iroh.IrohRuntime
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.domain.transfer.MultiCopyDeviceOption
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.network.FileApexClient
import com.fileapex.network.ServerLifecycleManager
import com.fileapex.network.WakeHelloOutbox
import com.fileapex.network.sendWakeBroadcast
import com.fileapex.platform.isActiveLanConnectivity
import com.fileapex.tailscale.TailscaleNodeRuntime
import com.fileapex.tailscale.TailscalePhase
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Intent-driven peer reachability with battery-first background checks.
 *
 * - Every device is checked on its own job ([probes]); nothing waits for another device
 * - Full checks when UI is foreground / network changes / cold launch
 * - Light checks in background (health ping only, skip when the peer is fresh)
 * - A device with bytes moving to or from it is not probed; that traffic is the proof
 */
class PeerPresenceMonitor(
    private val repository: DeviceRepository,
    private val client: FileApexClient,
    private val lanScanBackoff: LanScanBackoff
) {
    private val tailnetRoster = TailscalePeerRoster(repository, client)
    private val mutex = Mutex()
    private val book = ReachabilityBook()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probes = PeerProbeScheduler(scope) { deviceId, mode -> probeDevice(deviceId, mode) }
    @Volatile
    private var digestRound: DigestRound? = null
    private var snapshotWatcherJob: Job? = null
    private var lanPollJob: Job? = null
    private var tailnetWatchJob: Job? = null
    @Volatile
    private var coldLaunchProbeScheduled = false
    @Volatile
    private var lastForegroundRefreshEpochMs = 0L
    @Volatile
    private var appInForeground = false
    @Volatile
    private var lastFcmWakeDispatchEpochMs = 0L
    private val lastSelfBroadcastEpochMs = ConcurrentHashMap<String, Long>()
    private val uiInteractiveGate = CompletableDeferred<Unit>()

    private val outbound = PeerOutboundRoutes(object : RouteHost {
        override suspend fun liveDevice(deviceId: String) = mutex.withLock { repository.getDevice(deviceId) }
        override suspend fun markReachableOverLan(deviceId: String) = markReachable(deviceId)
        override suspend fun markReachableRemote(deviceId: String, via: RouteKind) = markReachable(deviceId, overLan = false, via = via)
        override fun noteRoute(deviceId: String, kind: RouteKind) = book.noteRoute(deviceId, kind)
        override suspend fun touchLastSeenEpoch(deviceId: String) = mutex.withLock<Unit> { repository.touchPeerLastSeenEpoch(deviceId) }
        override fun msSinceReached(deviceId: String): Long? =
            book.epochMs.value[deviceId]?.let { TimeUtils.now() - it }
        override fun recordRemoteReach(deviceId: String, via: RouteKind) {
            book.noteRoute(deviceId, via)
            lanScanBackoff.recordRemoteReach(deviceId, LanScanBackoff.currentNetworkKey(), LanPresenceTiming.REMOTE_ROUTE_SCAN_HOLD_MS)
        }
        override suspend fun fetchNodeState(host: String, port: Int, timeoutMs: Long) =
            client.fetchPeerNodeState(host, port, timeoutMs)
        override suspend fun pingHealth(host: String, port: Int, timeoutMs: Long) =
            client.pingHealth(host, port, timeoutMs)
    })

    /** The one route order (LAN, Tailscale, Iroh) for calls to a peer; see [PeerOutboundRoutes.routes]. */
    val routes: PeerRouteResolver get() = outbound.routes
    private val wakeGate = SweepWakeGate()
    private val digests = PresenceDigestRounds(object : DigestPeerView {
        override fun hasUsableEndpoint(peer: PairedDeviceEntity) = this@PeerPresenceMonitor.hasUsableEndpoint(peer)
        override fun isOnline(peer: PairedDeviceEntity) = isDeviceOnline(peer)
        override fun wasRecentlyReachable(deviceId: String, withinMs: Long) =
            this@PeerPresenceMonitor.wasRecentlyReachable(deviceId, withinMs)
        override suspend fun liveDevice(deviceId: String) = mutex.withLock { repository.getDevice(deviceId) }
        override suspend fun endpointFor(peer: PairedDeviceEntity) = resolveOutboundEndpoint(peer)
        override suspend fun fetchDigest(host: String, port: Int, ask: PresenceDigestMessage) =
            client.fetchPresenceDigest(host, port, ask, LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS)
    })
    val reachabilityEpochMs: StateFlow<Map<String, Long>> = book.epochMs
    val onlineSnapshotEpochMs: StateFlow<Long> = book.snapshotEpochMs
    val onlineDeviceIds: StateFlow<Set<String>> = book.onlineIds
    val reachedVia: StateFlow<Map<String, RouteKind>> = book.via

    fun setAppInForeground(inForeground: Boolean) {
        appInForeground = inForeground
    }

    fun isAppInForeground(): Boolean = appInForeground

    /** Called once the desktop window has presented and input should stay responsive. */
    fun markUiInteractive() {
        if (!uiInteractiveGate.isCompleted) {
            uiInteractiveGate.complete(Unit)
        }
    }

    private suspend fun awaitUiInteractive(timeoutMs: Long = 8_000L) {
        if (uiInteractiveGate.isCompleted) return
        // Desktop Main calls markUiInteractive(); Android has no window gate, fail open fast.
        val timeout = if (com.fileapex.cloud.currentPlatformLabel() == "Android") 50L else timeoutMs
        withTimeoutOrNull(timeout) { uiInteractiveGate.await() }
        if (!uiInteractiveGate.isCompleted) {
            uiInteractiveGate.complete(Unit)
        }
    }

    fun isDeviceOnline(device: PairedDeviceEntity): Boolean {
        if (!hasUsableEndpoint(device)) return false
        val probeEpoch = book.epochMs.value[device.deviceId] ?: 0L
        val lastSeen = maxOf(probeEpoch, device.lastSeenEpochMs)
        return TimeUtils.isWithinWindow(lastSeen, LanPresenceTiming.PRESENCE_READY_THRESHOLD_MS)
    }

    @Synchronized fun ensureOnlineSnapshotWatcher() {
        if (snapshotWatcherJob?.isActive == true) return
        snapshotWatcherJob = scope.launch {
            watchOnlineSnapshot(repository.observeDevices(), { refreshOnlineSnapshot() }) {
                onlineExpiryWaitMs(mutex.withLock { repository.listDevices() }, book.onlineIds.value, book.epochMs.value, TimeUtils.now())
            }
        }
    }

    /**
     * Battery-first LAN poll: 5 min foreground and background; defers during transfers.
     */
    @Synchronized fun ensureLanPollLoop() {
        if (tailnetWatchJob?.isActive != true) {
            tailnetWatchJob = scope.launch {
                var previous: TailscalePhase? = null
                TailscaleNodeRuntime.state
                    .map { it.phase }
                    .distinctUntilChanged()
                    .collect { phase ->
                        val wasUp = previous == TailscalePhase.Up
                        if (phase == TailscalePhase.Up && !wasUp) {
                            refreshAll(PresenceMode.Full)
                        } else if (wasUp && phase != TailscalePhase.Up) {
                            refreshAll(PresenceMode.Light)
                        }
                        previous = phase
                    }
            }
        }
        if (lanPollJob?.isActive == true) return
        lanPollJob = scope.launch {
            // Do not FULL-sweep during first Compose frames, saturates CPU and Room writers.
            awaitUiInteractive()
            delay(400)
            refreshAll(PresenceMode.Full)
            while (isActive) {
                val interval = if (appInForeground) {
                    LanPresenceTiming.FOREGROUND_LAN_POLL_MS
                } else {
                    LanPresenceTiming.BACKGROUND_LAN_POLL_MS
                }
                delay(interval)
                val mode = if (appInForeground) PresenceMode.Full else PresenceMode.Light
                // The connected phone's grant rides on this poll, so it is never skipped as fresh.
                if (mode == PresenceMode.Light &&
                    NotificationCompanion.desiredDeviceId().isEmpty() &&
                    allPeersRecentlyReachable(LanPresenceTiming.PEER_FRESH_SKIP_SWEEP_MS)
                ) {
                    refreshOnlineSnapshot()
                    continue
                }
                refreshAll(mode)
            }
        }
    }

    fun scheduleColdLaunchProbeOnce() {
        if (coldLaunchProbeScheduled) return
        coldLaunchProbeScheduled = true
        scope.launch {
            awaitUiInteractive()
            delay(800)
            refreshAll(PresenceMode.Full)
        }
    }

    fun refreshPeersOnForeground() {
        val now = TimeUtils.now()
        if (now - lastForegroundRefreshEpochMs < LanPresenceTiming.FOREGROUND_REFRESH_DEBOUNCE_MS) {
            return
        }
        lastForegroundRefreshEpochMs = now
        if (!uiInteractiveGate.isCompleted) {
            scope.launch {
                awaitUiInteractive()
                delay(400)
                refreshAll(PresenceMode.Full)
            }
            return
        }
        refreshAll(PresenceMode.Full)
    }

    fun onBackgroundWakeSignal(sourceDeviceId: String?) {
        if (!FileApexServices.isDatabaseReady()) return
        scope.launch {
            cancellableCatching {
                onBackgroundWakeSignalInternal(sourceDeviceId)
            }.onFailure { error ->
                println("PeerPresenceMonitor: background wake failed - ${error.message}")
            }
        }
    }

    suspend fun runSingleShotRevalidation() {
        announceOnNetwork()
        refreshAll(PresenceMode.Full, skipFcmDispatch = true)
    }

    fun onMdnsPeerDiscovered(host: String, port: Int, hintedDeviceId: String?) {
        val cleanedHost = host.trim()
        if (!FileApexServices.isDatabaseReady()) return
        scope.launch {
            cancellableCatching {
                handleMdnsPeerDiscovered(cleanedHost, port, hintedDeviceId)
            }.onFailure { error ->
                println("PeerPresenceMonitor: mDNS discovery failed - ${error.message}")
            }
        }
    }

    /**
     * Asks every paired device to be checked. This only decides what is due and hands each device to its own job
     * ([probes]); it never waits for a device, so it returns at once however many are slow or gone.
     */
    private fun refreshAll(mode: PresenceMode, skipFcmDispatch: Boolean = false) {
        scope.launch {
            val peers = mutex.withLock { repository.listDevices() }
            WakeHelloOutbox.setRoster(peers)
            val lan = isActiveLanConnectivity()
            if (lan || tailnetNodeUp()) {
                scope.launch {
                    cancellableCatching {
                        awaitShareServerReady()
                        if (repository.listCandidates().isNotEmpty()) FileApexServices.peerAdmission.verifyPending()
                    }
                }
            }
            if (peers.isEmpty()) {
                refreshOnlineSnapshot()
                if (mode == PresenceMode.Full) {
                    scope.launch { cancellableCatching { GoogleLinkCoordinator.publishSelfPresenceIfLinked() } }
                }
                return@launch
            }
            val wantsWake = mode == PresenceMode.Full || peers.any { !hasUsableEndpoint(it) || !isDeviceOnline(it) }
            if (wantsWake && lan && wakeGate.claimSweepWake(TimeUtils.now())) {
                scope.launch(Dispatchers.IO) { cancellableCatching { sendWakeBroadcast() } }
            }
            digestRound = (if (mode == PresenceMode.Light) digests.startRound(peers) else null).also { println("PeerPresenceMonitor: $mode sweep, ${peers.size} peers, digest sources ${it?.sourceIds?.size ?: 0}") }
            for (peer in peers) probes.poke(peer.deviceId, mode)
            scope.launch { cancellableCatching { GoogleLinkCoordinator.publishSelfPresenceIfLinked() } }
            if (mode == PresenceMode.Full && !skipFcmDispatch) maybeDispatchFcmWake()
        }
    }

    /**
     * A real network transition sends the signed hello without waiting for the sweep wake floor, but not again for
     * the same network within the floor. It waits for the LAN link to be usable, then sends twice, the second time
     * after the receivers' per-sender gap, so a receiver that was not listening yet still hears one. The sweep's own
     * wake is held back so only this one goes out.
     */
    private suspend fun announceOnNetwork() {
        scope.launch(Dispatchers.IO) {
            cancellableCatching { announceWhenLanReady() }
                .onFailure { error -> println("PeerPresenceMonitor: hello announce failed - ${error.message}") }
        }
    }

    private suspend fun announceWhenLanReady() {
        val deadline = TimeUtils.now() + LanPresenceTiming.HELLO_LAN_WAIT_MS
        while (!isActiveLanConnectivity() || LanScanBackoff.currentNetworkKey().isEmpty()) {
            if (TimeUtils.now() >= deadline) return
            delay(1_000L)
        }
        val key = LanScanBackoff.currentNetworkKey()
        val now = TimeUtils.now()
        if (!wakeGate.claimHello(key, now)) return
        WakeHelloOutbox.setRoster(mutex.withLock { repository.listDevices() })
        com.fileapex.iroh.IrohLog.add("network changed: hello sent to ${WakeHelloOutbox.recipientCount()} paired devices")
        sendWakeBroadcast()
        delay(LanPresenceTiming.HELLO_RESEND_MS)
        if (isActiveLanConnectivity() && LanScanBackoff.currentNetworkKey() == key) {
            com.fileapex.iroh.IrohLog.add("hello sent again")
            sendWakeBroadcast()
        }
    }

    private suspend fun onBackgroundWakeSignalInternal(sourceDeviceId: String?) {
        awaitShareServerReady()
        val trimmedSource = sourceDeviceId?.trim().orEmpty()
        if (trimmedSource.isNotEmpty()) {
            val peer = mutex.withLock { repository.getDevice(trimmedSource) }
            if (peer != null) {
                println(
                    "PeerPresenceMonitor: wake from ${peer.deviceId} " +
                        "(${peer.deviceName}${NetworkUtils.lastOctetLabel(peer.lastKnownIp)})"
                )
                primePeer(
                    peer,
                    includeDiscovery = false,
                    allowPassiveWait = false,
                    discoveryBudgetMs = LanPresenceTiming.LIGHT_SWEEP_DISCOVERY_BUDGET_MS,
                    bypassScanBackoff = true
                )
                refreshOnlineSnapshot()
                return
            }
        }
        refreshAll(PresenceMode.Light, skipFcmDispatch = true)
    }

    /** A hello or ack from a paired peer passed its proof; the address it came from is checked with a direct fetch. */
    suspend fun onVerifiedHello(deviceId: String, host: String, port: Int) {
        awaitShareServerReady()
        val peer = mutex.withLock { repository.getDevice(deviceId) } ?: return
        mutex.withLock { repository.touchPeerLastSeen(peer.deviceId, host, port) }
        val refreshed = mutex.withLock { repository.getDevice(peer.deviceId) } ?: peer
        primePeer(
            refreshed,
            includeDiscovery = false,
            allowPassiveWait = false,
            discoveryBudgetMs = LanPresenceTiming.LIGHT_SWEEP_DISCOVERY_BUDGET_MS,
            bypassScanBackoff = true
        )
        refreshOnlineSnapshot()
    }

    private suspend fun handleMdnsPeerDiscovered(host: String, port: Int, hintedDeviceId: String?) {
        val cleanedHost = host.trim()
        if (cleanedHost.isEmpty() || port <= 0) return
        val peer = mdnsMatcher.resolve(cleanedHost, port, hintedDeviceId) ?: return

        mutex.withLock {
            repository.touchPeerLastSeen(peer.deviceId, cleanedHost, port)
        }
        val refreshed = mutex.withLock { repository.getDevice(peer.deviceId) } ?: peer
        primePeer(
            refreshed,
            includeDiscovery = false,
            allowPassiveWait = false,
            discoveryBudgetMs = LanPresenceTiming.LIGHT_SWEEP_DISCOVERY_BUDGET_MS,
            bypassScanBackoff = true
        )
        refreshOnlineSnapshot()
    }

    /** One device's whole check: its own endpoint, discovery, Iroh fallback and online mark. Runs on that device's job only. */
    private suspend fun probeDevice(deviceId: String, mode: PresenceMode) {
        val started = digestRound
        val peer = mutex.withLock { repository.getDevice(deviceId) } ?: run { started?.finish(deviceId); return }
        val round = started?.takeIf { mode == PresenceMode.Light || deviceId in it.sourceIds }
        try {
            if (TransferActivityGuard.isTransferActiveFor(deviceId)) {
                markReachable(deviceId)
                refreshOnlineSnapshot()
                return
            }
            val freshMs = if (mode == PresenceMode.Full) {
                LanPresenceTiming.FOREGROUND_PEER_FRESH_MS
            } else {
                LanPresenceTiming.BACKGROUND_PEER_FRESH_MS
            }
            val tailnetUp = tailnetNodeUp()
            if (!isActiveLanConnectivity() && !tailnetUp) {
                outbound.irohProbe(peer, LanPresenceTiming.BACKGROUND_PEER_FRESH_MS)
                refreshOnlineSnapshot()
                return
            }
            if (tailnetUp) cancellableCatching { tailnetRoster.refresh(deviceId) }
            awaitShareServerReady()
            val isSource = round != null && deviceId in round.sourceIds
            if (round != null && !isSource) {
                // Bounded: the sources are devices that just answered, and a dead one only costs this short wait.
                withTimeoutOrNull(PresenceDigestRounds.WAIT_MS) { round.done.await() }
                if (!alreadyFresh(peer, freshMs) && digests.allowsSkip(peer, round.hints)) return
            }
            probe(peer, mode, freshMs)
            if (round != null && isSource) {
                digests.collectHints(listOf(peer), freshMs).forEach { (id, at) -> round.hints.merge(id, at) { a, b -> maxOf(a, b) } }
            }
            outbound.irohProbe(peer, freshMs)
            refreshOnlineSnapshot()
            val live = mutex.withLock { repository.getDevice(deviceId) } ?: peer
            if (mode == PresenceMode.Full && isDeviceOnline(live)) maybeBroadcastSelfIdentity(deviceId)
        } finally {
            started?.finish(deviceId)
        }
    }

    private fun alreadyFresh(peer: PairedDeviceEntity, freshMs: Long): Boolean {
        val isMetadataComplete = hasUsableEndpoint(peer) && peer.rootPath.isNotBlank() && peer.rootPath != "/"
        return isMetadataComplete &&
            !NotificationCompanion.isCompanion(peer.deviceId) &&
            wasRecentlyReachable(peer.deviceId, freshMs)
    }

    private suspend fun probe(peer: PairedDeviceEntity, mode: PresenceMode, freshMs: Long) {
        if (alreadyFresh(peer, freshMs)) return
        val full = mode == PresenceMode.Full
        primePeer(
            peer,
            includeDiscovery = full,
            allowPassiveWait = full,
            discoveryBudgetMs = if (full) {
                LanPresenceTiming.STALE_PEER_LAN_DISCOVERY_BUDGET_MS
            } else {
                LanPresenceTiming.LIGHT_SWEEP_DISCOVERY_BUDGET_MS
            },
            bypassScanBackoff = false
        )
    }

    fun verifiedEpochsSnapshot(): Map<String, Long> = book.epochMs.value

    private suspend fun maybeBroadcastSelfIdentity(deviceId: String) {
        val now = TimeUtils.now()
        val last = lastSelfBroadcastEpochMs[deviceId] ?: 0L
        if (now - last < LanPresenceTiming.SELF_BROADCAST_MIN_INTERVAL_MS) return
        lastSelfBroadcastEpochMs[deviceId] = now
        cancellableCatching { FileApexServices.pairingCoordinator.broadcastSelfIdentity(setOf(deviceId)) }
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
        book.markSnapshot()
    }

    fun clearOnlineSnapshot() {
        book.clearSnapshot()
    }

    suspend fun validatePeerOnDemand(peer: PairedDeviceEntity): Boolean {
        scope.launch(Dispatchers.IO) {
            cancellableCatching { sendWakeBroadcast() }
        }
        val reached = primePeer(
            peer,
            includeDiscovery = true,
            allowPassiveWait = true,
            discoveryBudgetMs = LanPresenceTiming.STALE_PEER_LAN_DISCOVERY_BUDGET_MS,
            bypassScanBackoff = true
        )
        refreshOnlineSnapshot()
        return reached
    }

    /** True when some route type could exist: a LAN here, Tailscale up, or Iroh switched on. */
    fun canAttemptRoutes(): Boolean = isActiveLanConnectivity() || isTailscaleEnabled() || IrohRuntime.isUsable()

    /**
     * Outbound host:port for every call to a peer, from [routes]. Features use [endpointIfOnline] or [probeNow], never this:
     * LAN, then Tailscale if the peer is online there, then Iroh. When none answers, the stored LAN address
     * is returned unverified so a peer that was only slow to answer the quick check can still be dialed.
     * A caller whose request fails on the returned address calls [reportRouteFailure] before asking again.
     */
    suspend fun endpointIfOnline(
        peer: PairedDeviceEntity,
        use: RouteUse = RouteUse.Request
    ): PeerLanReachabilityVerdict.Direct? {
        val live = mutex.withLock { repository.getDevice(peer.deviceId) } ?: peer
        return PeerGate.ifOnline(isDeviceOnline(live)) { resolveOutboundEndpoint(live, use) }
    }

    /** For an action the user started (open, send, browse, wake): the peer is tried even when it shows offline. */
    suspend fun probeNow(
        peer: PairedDeviceEntity,
        use: RouteUse = RouteUse.Request
    ): PeerLanReachabilityVerdict.Direct? = resolveOutboundEndpoint(peer, use)

    private suspend fun resolveOutboundEndpoint(
        peer: PairedDeviceEntity,
        use: RouteUse = RouteUse.Request
    ) = outbound.resolveOutboundEndpoint(peer, use)

    /** The request to [host] failed: skip that kind of route for this peer for a while. */
    fun reportRouteFailure(peer: PairedDeviceEntity, host: String) {
        routes.routeFailed(peer, routeKindForHost(host))
    }

    suspend fun prepareForTransfer(targets: List<MultiCopyDeviceOption>) {
        val remote = targets.filter { !it.isLocal }
        if (remote.isEmpty()) return
        val needsPrime = remote.filter { target ->
            val peer = mutex.withLock { repository.getDevice(target.deviceId) } ?: return@filter true
            routes.routeFor(peer, RouteUse.Bulk) == null
        }
        if (needsPrime.isEmpty()) return
        primePeersForTransfer(needsPrime)
    }

    suspend fun primePeersForTransfer(targets: List<MultiCopyDeviceOption>) {
        if (targets.isEmpty()) return
        primer.primeForTransfer(targets, scope)
        refreshOnlineSnapshot()
    }

    private fun wasRecentlyReachable(deviceId: String, withinMs: Long) = book.wasRecentlyReachable(deviceId, withinMs)

    private suspend fun primePeer(
        peer: PairedDeviceEntity,
        includeDiscovery: Boolean,
        allowPassiveWait: Boolean,
        discoveryBudgetMs: Long,
        bypassScanBackoff: Boolean = false
    ) = primer.primePeer(peer, includeDiscovery, allowPassiveWait, discoveryBudgetMs, bypassScanBackoff)

    internal fun hasUsableEndpoint(peer: PairedDeviceEntity): Boolean =
        resolvePeerEndpoint(peer, tailnetNodeUp()) != null || IrohRuntime.canReach(peer.deviceId)

    private val nodeStateClaims = NodeStateClaims()
    private val storedEndpoint = StoredEndpointProbe(object : StoredEndpointHost {
        override suspend fun probeHealth(host: String, port: Int, timeoutMs: Long, companion: Boolean?) =
            client.probeHealth(host, port, timeoutMs, companion)
        override suspend fun recordEndpointMismatch(peerId: String, host: String, answeredAs: String) {
            mutex.withLock { repository.recordEndpointMismatch(peerId, host, answeredAs) }
        }
        override suspend fun markReachable(deviceId: String) = this@PeerPresenceMonitor.markReachable(deviceId)
        override fun onBulletinVersion(peer: PairedDeviceEntity, version: Long) {
            FileApexServices.bulletinSyncEngine.onPeerBulletinVersion(peer, version)
        }
        override fun pullRemovals(peer: PairedDeviceEntity, endpoint: ResolvedPeerEndpoint, theirs: String) {
            scope.launch { pullers.removals.pull(peer, endpoint, theirs) }
        }
        override fun reconcileCluster(peer: PairedDeviceEntity, endpoint: ResolvedPeerEndpoint, theirs: String) {
            scope.launch { pullers.cluster.pull(peer, endpoint, theirs) }
        }
        override suspend fun touchLastSeenEpoch(deviceId: String) {
            mutex.withLock { repository.touchPeerLastSeenEpoch(deviceId) }
        }
        override suspend fun touchLastSeen(deviceId: String, host: String, port: Int) {
            mutex.withLock { repository.touchPeerLastSeen(deviceId, host, port) }
        }
        override suspend fun fetchNodeState(host: String, port: Int, timeoutMs: Long) =
            client.fetchPeerNodeState(host, port, timeoutMs)
        override suspend fun applyNodeState(state: PeerNodeState, rosterDeviceId: String) {
            mutex.withLock { repository.applyPeerNodeState(state, rosterDeviceId = rosterDeviceId, observedDirectly = true) }
        }
        override suspend fun supersedeHardwareTwins(state: PeerNodeState) {
            FileApexServices.peerAdmission.supersedeHardwareTwins(state)
        }
    }, nodeStateClaims)

    private val primer = PeerPrimer(object : PrimerHost {
        override suspend fun liveDevice(deviceId: String) = mutex.withLock { repository.getDevice(deviceId) }
        override fun hasUsableEndpoint(peer: PairedDeviceEntity) = this@PeerPresenceMonitor.hasUsableEndpoint(peer)
        override suspend fun tryStoredEndpoint(
            peer: PairedDeviceEntity,
            attempts: Int,
            retryMs: Long,
            timeoutMs: Long,
            fetchNodeState: Boolean
        ) = storedEndpoint.tryStoredEndpoint(peer, attempts, retryMs, timeoutMs, fetchNodeState)
        override fun shouldScan(deviceId: String, networkKey: String) = lanScanBackoff.shouldScan(deviceId, networkKey)
        override fun recordScanFailure(deviceId: String, networkKey: String) = lanScanBackoff.recordFailure(deviceId, networkKey)
        override suspend fun discover(target: PairedDeviceEntity, budgetMs: Long) =
            PeerLanDiscovery.discoverPeerState(peer = target, client = client, budgetMs = budgetMs)
        override suspend fun applyDiscovered(state: PeerNodeState, rosterDeviceId: String) {
            mutex.withLock { repository.applyPeerNodeState(state, rosterDeviceId = rosterDeviceId, observedDirectly = true) }
        }
        override suspend fun markReachable(deviceId: String, discoveredId: String) =
            this@PeerPresenceMonitor.markReachable(deviceId, discoveredId)
    })

    private val mdnsMatcher = MdnsPeerMatcher(object : MdnsRosterHost {
        override suspend fun device(deviceId: String) = mutex.withLock { repository.getDevice(deviceId) }
        override suspend fun devices() = mutex.withLock { repository.listDevices() }
        override suspend fun fetchNodeState(host: String, port: Int, timeoutMs: Long) =
            client.fetchPeerNodeState(host, port, timeoutMs)
        override suspend fun applyNodeState(state: PeerNodeState, rosterDeviceId: String) {
            mutex.withLock { repository.applyPeerNodeState(state, rosterDeviceId = rosterDeviceId, observedDirectly = true) }
        }
    })

    private val pullers = RemovalPullers(repository, client)

    private suspend fun markReachable(vararg deviceIds: String, epochMs: Long = TimeUtils.now(), overLan: Boolean = true, via: RouteKind? = null) {
        book.record(deviceIds, epochMs, via ?: if (overLan) RouteKind.Lan else null)
        for (id in deviceIds) {
            val trimmed = id.trim()
            if (trimmed.isNotEmpty()) {
                if (overLan) {
                    lanScanBackoff.recordSuccess(trimmed)
                } else {
                    lanScanBackoff.recordRemoteReach(
                        trimmed,
                        LanScanBackoff.currentNetworkKey(),
                        LanPresenceTiming.REMOTE_ROUTE_SCAN_HOLD_MS
                    )
                }
                digests.skips.directContact(trimmed)
                val dev = repository.getDevice(trimmed)
                if (dev != null && dev.lastKnownIp.isNotBlank() && dev.port > 0) {
                    repository.touchPeerLastSeen(trimmed, dev.lastKnownIp, dev.port, epochMs)
                    if ((dev.rootPath.isBlank() || dev.rootPath == "/") && nodeStateClaims.claim(dev, null)) {
                        scope.launch(Dispatchers.IO) {
                            cancellableCatching {
                                val state = client.fetchPeerNodeState(
                                    dev.lastKnownIp,
                                    dev.port,
                                    LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS
                                )
                                mutex.withLock {
                                    repository.applyPeerNodeState(state, rosterDeviceId = trimmed, observedDirectly = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun publishStableOnlineIds(devices: List<PairedDeviceEntity>) {
        val nextOnline = devices.filter { isDeviceOnline(it) }.map { it.deviceId }.toSet()
        book.publishOnline(nextOnline)
    }

    companion object {
        const val OFFLINE_GRACE_MS = LanPresenceTiming.OFFLINE_GRACE_MS
        private const val SERVER_READY_ATTEMPTS = 25
        private const val SERVER_READY_POLL_MS = 100L
        private const val SERVER_SETTLE_MS = 250L
    }
}
