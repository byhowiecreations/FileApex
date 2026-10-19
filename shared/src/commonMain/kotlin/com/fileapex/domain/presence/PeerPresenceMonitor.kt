package com.fileapex.domain.presence

import com.fileapex.data.bulletin.BulletinPeerBackoff
import com.fileapex.data.db.isDockerNode
import com.fileapex.domain.notifications.NotificationCompanion
import com.fileapex.domain.notifications.NotificationDebugLog
import com.fileapex.cloud.FcmWakeCoordinator
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.DeviceRepository
import com.fileapex.di.FileApexServices
import com.fileapex.iroh.IrohRuntime
import com.fileapex.iroh.IrohSweepPolicy
import com.fileapex.domain.pairing.ClusterSyncRequest
import com.fileapex.domain.pairing.PeerSyncEventKind
import com.fileapex.domain.pairing.RemovalPullLedger
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.domain.transfer.MultiCopyDeviceOption
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.network.FileApexClient
import com.fileapex.network.FileApexMdnsBrowser
import com.fileapex.network.PeerLanHttpPolicy
import com.fileapex.network.ServerLifecycleManager
import com.fileapex.network.WakeHelloOutbox
import com.fileapex.network.sendWakeBroadcast
import com.fileapex.platform.hasUsableNetwork
import com.fileapex.platform.isActiveLanConnectivity
import com.fileapex.tailscale.TailscaleNodeRuntime
import com.fileapex.tailscale.TailscalePhase
import com.fileapex.tailscale.tailnetPeerIsOnline
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val reachabilityLock = Mutex()
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
    private val helloAnnounceLock = Any()
    private var lastHelloAnnounce: Pair<String, Long>? = null
    private val uiInteractiveGate = CompletableDeferred<Unit>()

    private val lastReachableEpochById = mutableMapOf<String, Long>()
    private val nodeStateFetchedAtMs = ConcurrentHashMap<String, Long>()
    private val nodeStateVersionSeen = ConcurrentHashMap<String, Long>()
    /** deviceId to (verified on LAN, epoch ms the answer expires). */
    private val lanDirectVerdicts = ConcurrentHashMap<String, Pair<Boolean, Long>>()
    private val tailnetAnswers = com.fileapex.iroh.IrohReachabilityCache(
        LAN_DIRECT_HIT_TTL_MS,
        LAN_DIRECT_MISS_TTL_MS,
        TimeUtils::now
    )

    /**
     * The one route order (LAN, Tailscale, Iroh) for calls to a peer. The tailnet check is its own ping with
     * its own result, because a shared "reached recently" flag also counts LAN and Iroh traffic.
     */
    val routes: PeerRouteResolver by lazy {
        PeerRouteResolver(probes = object : PeerRouteProbes {
            override fun networkAvailable() = hasUsableNetwork()

            override suspend fun lan(peer: PairedDeviceEntity): PeerRoute? =
                verifiedLanEndpoint(peer)?.let { PeerRoute(RouteKind.Lan, it.host, it.port) }

            override fun tailnetEnabled() = isTailscaleEnabled()

            override fun tailnetPeerOnline(peer: PairedDeviceEntity) =
                tailnetPeerIsOnline(TailscaleNodeRuntime.observedTailnetPeers(), peer.deviceId, peer.deviceName)

            override suspend fun tailnet(peer: PairedDeviceEntity): PeerRoute? = tailnetRoute(peer)

            override fun irohUsable(peer: PairedDeviceEntity) = IrohRuntime.canReach(peer.deviceId)

            override suspend fun iroh(peer: PairedDeviceEntity): PeerRoute? =
                irohVerdict(peer)?.let { PeerRoute(RouteKind.Iroh, it.host, it.port) }
        }, onRouteChange = { peer, route ->
            if (IrohRuntime.isEnabled()) {
                val via = when (route?.kind) {
                    RouteKind.Lan -> "LAN"
                    RouteKind.Tailnet -> "Tailscale"
                    RouteKind.Iroh -> "Iroh"
                    null -> "none"
                }
                com.fileapex.iroh.IrohLog.add("route to ${com.fileapex.iroh.IrohLog.short(peer.deviceId)}: $via")
            }
        })
    }
    private val lastSweepWakeAtMs = AtomicLong(0L)
    private val digestSkips = DigestSkipLog()
    private val digestSupport = BulletinPeerBackoff(DIGEST_UNSUPPORTED_RETRY_MS, DIGEST_UNSUPPORTED_RETRY_MS)
    private val digestClockSkewMs = ConcurrentHashMap<String, Long>()
    private val discoveredMdnsEndpoints = mutableMapOf<Pair<String, Int>, Long>()
    private val _reachabilityEpochMs = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val _onlineSnapshotEpochMs = MutableStateFlow(0L)

    val reachabilityEpochMs: StateFlow<Map<String, Long>> = _reachabilityEpochMs.asStateFlow()
    val onlineSnapshotEpochMs: StateFlow<Long> = _onlineSnapshotEpochMs.asStateFlow()

    private val _onlineDeviceIds = MutableStateFlow<Set<String>>(emptySet())
    val onlineDeviceIds: StateFlow<Set<String>> = _onlineDeviceIds.asStateFlow()

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
        // Desktop Main calls markUiInteractive(); Android has no window gate — fail open fast.
        val timeout = if (com.fileapex.cloud.currentPlatformLabel() == "Android") 50L else timeoutMs
        withTimeoutOrNull(timeout) { uiInteractiveGate.await() }
        if (!uiInteractiveGate.isCompleted) {
            uiInteractiveGate.complete(Unit)
        }
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
     * Battery-first LAN poll: 5 min foreground and background; defers during transfers.
     */
    fun ensureLanPollLoop() {
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
            // Do not FULL-sweep during first Compose frames — saturates CPU and Room writers.
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
        if (cleanedHost.isNotEmpty() && port > 0) {
            scope.launch {
                mutex.withLock {
                    discoveredMdnsEndpoints[cleanedHost to port] = TimeUtils.now()
                }
            }
        }
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
            if (wantsWake && lan && claimSweepWake()) {
                scope.launch(Dispatchers.IO) { cancellableCatching { sendWakeBroadcast() } }
            }
            digestRound = if (mode == PresenceMode.Light) startDigestRound(peers) else null
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
        synchronized(helloAnnounceLock) {
            val last = lastHelloAnnounce
            if (last != null && last.first == key && now - last.second < LanPresenceTiming.SWEEP_WAKE_FLOOR_MS) return
            lastHelloAnnounce = key to now
        }
        lastSweepWakeAtMs.set(now)
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
        val peer = resolveMdnsPeer(cleanedHost, port, hintedDeviceId) ?: return

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

        val state = cancellableCatching {
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
            repository.applyPeerNodeState(state, rosterDeviceId = matched.deviceId, observedDirectly = true)
        }
        return mutex.withLock { repository.getDevice(matched.deviceId) } ?: matched
    }

    /** One device's whole check: its own endpoint, discovery, Iroh fallback and online mark. Runs on that device's job only. */
    private suspend fun probeDevice(deviceId: String, mode: PresenceMode) {
        val peer = mutex.withLock { repository.getDevice(deviceId) } ?: run { digestRound?.finish(deviceId); return }
        val round = digestRound?.takeIf { mode == PresenceMode.Light || deviceId in it.sourceIds }
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
                irohProbe(peer, LanPresenceTiming.BACKGROUND_PEER_FRESH_MS)
                refreshOnlineSnapshot()
                return
            }
            if (tailnetUp) cancellableCatching { tailnetRoster.refresh(deviceId) }
            awaitShareServerReady()
            val isSource = round != null && deviceId in round.sourceIds
            if (round != null && !isSource) {
                // Bounded: the sources are devices that just answered, and a dead one only costs this short wait.
                withTimeoutOrNull(DIGEST_WAIT_MS) { round.done.await() }
                if (!alreadyFresh(peer, freshMs) && digestAllowsSkip(peer, round.hints)) return
            }
            probe(peer, mode, freshMs)
            if (isSource && round != null) {
                collectDigestHints(listOf(peer), freshMs).forEach { (id, at) -> round.hints.merge(id, at) { a, b -> maxOf(a, b) } }
            }
            irohProbe(peer, freshMs)
            refreshOnlineSnapshot()
            val live = mutex.withLock { repository.getDevice(deviceId) } ?: peer
            if (mode == PresenceMode.Full && isDeviceOnline(live)) maybeBroadcastSelfIdentity(deviceId)
        } finally {
            digestRound?.finish(deviceId)
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

    /** Devices that answered recently are asked what they have seen of the others, so the others can skip a ping. */
    private class DigestRound(val sourceIds: Set<String>) {
        val hints = ConcurrentHashMap<String, Long>()
        val done = CompletableDeferred<Unit>()
        private val waiting = java.util.concurrent.atomic.AtomicInteger(sourceIds.size)
        private val finished = ConcurrentHashMap.newKeySet<String>()

        fun finish(id: String) {
            if (id in sourceIds && finished.add(id) && waiting.decrementAndGet() == 0) done.complete(Unit)
        }
    }

    private fun startDigestRound(peers: List<PairedDeviceEntity>): DigestRound? {
        val sources = pickDigestSources(peers.sortedBy { it.deviceName.lowercase() })
        return if (sources.isEmpty()) null else DigestRound(sources.map { it.deviceId }.toSet())
    }

    fun verifiedEpochsSnapshot(): Map<String, Long> = _reachabilityEpochMs.value

    private fun pickDigestSources(orderedPeers: List<PairedDeviceEntity>): List<PairedDeviceEntity> {
        if (orderedPeers.size < DIGEST_MIN_PEERS) return emptyList()
        val now = TimeUtils.now()
        return orderedPeers.asSequence()
            .filter { it.publicKey.isNotBlank() && !it.isDockerNode() }
            .filter { hasUsableEndpoint(it) && isDeviceOnline(it) }
            .filter { !NotificationCompanion.isCompanion(it.deviceId) }
            .filter { digestSupport.canAttempt(it.deviceId, now) }
            .take(DIGEST_MAX_SOURCES)
            .toList()
    }

    private suspend fun collectDigestHints(
        sources: List<PairedDeviceEntity>,
        freshnessThresholdMs: Long
    ): Map<String, Long> {
        val localId = com.fileapex.data.identity.loadLocalIdentity().deviceId
        val hints = HashMap<String, Long>()
        for (source in sources) {
            if (!wasRecentlyReachable(source.deviceId, freshnessThresholdMs)) continue
            val live = mutex.withLock { repository.getDevice(source.deviceId) } ?: source
            val endpoint = resolveOutboundEndpoint(live) ?: continue
            val stamp = PresenceDigests.newStamp()
            val ask = PresenceDigests.buildAsk(localId, live, stamp, TimeUtils.now()) ?: continue
            val startedAt = TimeUtils.now()
            val result = cancellableCatching {
                client.fetchPresenceDigest(
                    endpoint.host,
                    endpoint.port,
                    ask,
                    LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS
                )
            }
            if (result.isFailure) continue
            val reply = result.getOrNull()
            if (reply == null) {
                digestSupport.onFailure(source.deviceId, TimeUtils.now())
                continue
            }
            val receivedAt = TimeUtils.now()
            val opened = PresenceDigests.open(reply, live, stamp, localId) ?: continue
            digestSupport.onSuccess(source.deviceId)
            val halfTrip = (receivedAt - startedAt) / 2
            digestClockSkewMs[source.deviceId] = receivedAt - halfTrip - opened.sentAtUtcMs
            for (seen in opened.seen) {
                hints.merge(seen.id, receivedAt - halfTrip - seen.ageMs) { a, b -> maxOf(a, b) }
            }
        }
        return hints
    }

    private fun digestAllowsSkip(peer: PairedDeviceEntity, hints: Map<String, Long>): Boolean {
        val reachedAt = hints[peer.deviceId] ?: return false
        if (!TimeUtils.isWithinWindow(reachedAt, PresenceDigests.MAX_ENTRY_AGE_MS)) return false
        if (!hasUsableEndpoint(peer) || !isDeviceOnline(peer)) return false
        if (peer.rootPath.isBlank() || peer.rootPath == "/") return false
        if (NotificationCompanion.isCompanion(peer.deviceId)) return false
        return digestSkips.trySkip(peer.deviceId)
    }

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
        _onlineSnapshotEpochMs.value = TimeUtils.now()
    }

    fun clearOnlineSnapshot() {
        _onlineDeviceIds.value = emptySet()
        _onlineSnapshotEpochMs.value = 0L
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
    ): PeerLanReachabilityVerdict.Direct? {
        val live = mutex.withLock { repository.getDevice(peer.deviceId) } ?: peer
        routes.routeFor(live, use)?.let { return PeerLanReachabilityVerdict.Direct(it.host, it.port) }
        if (use == RouteUse.AddressExchange || !isActiveLanConnectivity()) return null
        val host = live.lastKnownIp.trim()
        val port = live.port
        if (host.isNotEmpty() && NetworkUtils.isPrivateLanPeerHost(host) && port > 0) {
            return PeerLanReachabilityVerdict.Direct(host, port)
        }
        return null
    }

    /** The request to [host] failed: skip that kind of route for this peer for a while. */
    fun reportRouteFailure(peer: PairedDeviceEntity, host: String) {
        routes.routeFailed(peer, routeKindForHost(host))
    }

    /** The Iroh bridge endpoint when the peer answered through Iroh. Marks it reachable for presence. */
    private suspend fun irohVerdict(peer: PairedDeviceEntity): PeerLanReachabilityVerdict.Direct? {
        if (!IrohRuntime.canReach(peer.deviceId)) return null
        val endpoint = IrohRuntime.reachableEndpoint(peer) ?: return null
        markReachable(peer.deviceId, overLan = false)
        mutex.withLock { repository.touchPeerLastSeenEpoch(peer.deviceId) }
        return PeerLanReachabilityVerdict.Direct(endpoint.host, endpoint.port)
    }

    /** The tailnet route when Tailscale is on, reports the peer online and the peer answers a short ping. */
    private suspend fun tailnetRoute(peer: PairedDeviceEntity): PeerRoute? {
        if (!isTailscaleEnabled()) return null
        if (!tailnetPeerIsOnline(TailscaleNodeRuntime.observedTailnetPeers(), peer.deviceId, peer.deviceName)) return null
        val endpoint = resolvePeerEndpoint(peer, tailnetUp = true)?.takeIf { it.tailnet } ?: return null
        val answers = tailnetAnswers.check(peer.deviceId) {
            client.pingHealth(endpoint.host, endpoint.port, LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS)
        }
        if (answers) {
            lanScanBackoff.recordRemoteReach(
                peer.deviceId,
                LanScanBackoff.currentNetworkKey(),
                LanPresenceTiming.REMOTE_ROUTE_SCAN_HOLD_MS
            )
        }
        return if (answers) PeerRoute(RouteKind.Tailnet, endpoint.host, endpoint.port) else null
    }

    /** A peer the LAN pass did not reach is tried through Iroh; this is that one peer's own attempt. */
    private suspend fun irohProbe(peer: PairedDeviceEntity, freshMs: Long) {
        if (!IrohRuntime.isUsable()) return
        // A resting endpoint is started by the reconcile loop or an explicit request, never by a presence check.
        if (!IrohRuntime.isUp() && FileApexServices.settings.irohRestWhenIdle.value) return
        val now = TimeUtils.now()
        val worth = IrohSweepPolicy.shouldProbe(
            canReach = IrohRuntime.canReach(peer.deviceId),
            msSinceReached = _reachabilityEpochMs.value[peer.deviceId]?.let { now - it },
            freshMs = freshMs,
            onLan = isActiveLanConnectivity()
        )
        if (worth) cancellableCatching { if (tailnetRoute(peer) == null) irohVerdict(peer) }
    }

    /**
     * The tsnet userspace path is CPU bound and far slower than the local network, so a peer on
     * the same LAN is used directly even while Tailscale is on. The peer has to answer on its
     * stored LAN address with its own device id; a private address alone proves nothing because
     * another network can reuse it. Both answers are cached briefly so a queue of sends pays for one probe.
     * A peer on the local subnet answers in milliseconds; a stale address costs one short timeout, then
     * the miss is remembered and the next route (Tailscale, then Iroh) is tried straight away.
     */
    suspend fun verifiedLanEndpoint(peer: PairedDeviceEntity): PeerLanReachabilityVerdict.Direct? {
        val host = peer.lastKnownIp.trim()
        val port = peer.port
        if (port <= 0 || host.isEmpty() || !NetworkUtils.isPrivateLanPeerHost(host)) return null
        if (!isActiveLanConnectivity() || !PeerLanHttpPolicy.canRoute(host)) return null
        // A peer outside every local /24 cannot be answering on the LAN, so skip the probe entirely.
        if (NetworkUtils.lanBindCandidates().none { NetworkUtils.sameIpv4Slash24(it, host) }) return null
        val now = TimeUtils.now()
        val cached = lanDirectVerdicts[peer.deviceId]
        if (cached != null && cached.second > now) {
            return if (cached.first) PeerLanReachabilityVerdict.Direct(host, port) else null
        }
        val state = cancellableCatching {
            client.fetchPeerNodeState(host, port, LanPresenceTiming.DEVICE_DETAILS_PING_TIMEOUT_MS)
        }.getOrNull()
        val verified = state != null && sameDevice(peer, state)
        val ttl = if (verified) LAN_DIRECT_HIT_TTL_MS else LAN_DIRECT_MISS_TTL_MS
        lanDirectVerdicts[peer.deviceId] = verified to (TimeUtils.now() + ttl)
        if (!verified) return null
        markReachable(peer.deviceId)
        return PeerLanReachabilityVerdict.Direct(host, port)
    }

    private fun sameDevice(peer: PairedDeviceEntity, state: PeerNodeState): Boolean {
        if (state.deviceId.trim() == peer.deviceId) return true
        val hash = peer.publicKeyHash.trim()
        return hash.isNotEmpty() && hash == state.publicKeyHash.trim()
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
        scope.launch(Dispatchers.IO) {
            cancellableCatching { sendWakeBroadcast() }
        }
        val attempts = LanPresenceTiming.ON_DEMAND_PRIME_ATTEMPTS
        val retryMs = LanPresenceTiming.ON_DEMAND_PRIME_RETRY_MS
        val timeoutMs = LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS
        coroutineScope {
            targets.filter { !it.isLocal }.map { target ->
                async(Dispatchers.IO) {
                    val peer = mutex.withLock { repository.getDevice(target.deviceId) } ?: return@async
                    if (tryStoredEndpoint(peer, attempts, retryMs, timeoutMs, fetchNodeState = false)) {
                        return@async
                    }
                    cancellableCatching { FileApexMdnsBrowser.requestProbe() }
                    delay(LanPresenceTiming.TRANSFER_MDNS_SETTLE_MS)
                    val refreshed = mutex.withLock { repository.getDevice(target.deviceId) } ?: peer
                    if (tryStoredEndpoint(refreshed, attempts, retryMs, timeoutMs, fetchNodeState = false)) {
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
        refreshOnlineSnapshot()
    }

    /** Sweep-driven UDP wakes are floored; on-demand prime and connect wakes are not. */
    private fun claimSweepWake(): Boolean {
        val now = TimeUtils.now()
        val previous = lastSweepWakeAtMs.get()
        if (previous > 0L && now - previous < LanPresenceTiming.SWEEP_WAKE_FLOOR_MS) return false
        return lastSweepWakeAtMs.compareAndSet(previous, now)
    }

    /**
     * Identity/metadata refresh after a successful probe runs only when metadata is missing or the
     * last fetch is older than [LanPresenceTiming.NODE_STATE_REFRESH_TTL_MS]. Claims the slot so
     * concurrent probes don't fetch twice.
     */
    private fun claimNodeStateFetch(peer: PairedDeviceEntity, peerVersion: Long?): Boolean {
        val now = TimeUtils.now()
        val missingMetadata = peer.rootPath.isBlank() || peer.rootPath == "/" || peer.deviceName.isBlank()
        var claimed = false
        nodeStateFetchedAtMs.compute(peer.deviceId) { _, previous ->
            val stale = PeerStateRefreshPolicy.shouldFetch(
                peerVersion = peerVersion,
                seenVersion = nodeStateVersionSeen[peer.deviceId],
                ageMs = previous?.let { now - it },
                missingMetadata = missingMetadata
            )
            claimed = stale
            if (stale) now else previous
        }
        if (claimed && peerVersion != null) nodeStateVersionSeen[peer.deviceId] = peerVersion
        return claimed
    }

    private fun wasRecentlyReachable(deviceId: String, withinMs: Long): Boolean {
        val epoch = _reachabilityEpochMs.value[deviceId] ?: return false
        return TimeUtils.isWithinWindow(epoch, withinMs)
    }

    private suspend fun primePeer(
        peer: PairedDeviceEntity,
        includeDiscovery: Boolean,
        allowPassiveWait: Boolean,
        discoveryBudgetMs: Long,
        bypassScanBackoff: Boolean = false
    ): Boolean {
        val attempts = LanPresenceTiming.ON_DEMAND_PRIME_ATTEMPTS
        val retryMs = LanPresenceTiming.ON_DEMAND_PRIME_RETRY_MS
        val timeoutMs = LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS

        var current = mutex.withLock { repository.getDevice(peer.deviceId) } ?: peer
        if (!hasUsableEndpoint(current)) {
            cancellableCatching { FileApexMdnsBrowser.requestProbe() }
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
        val scanNetworkKey = LanScanBackoff.currentNetworkKey()
        if (shouldDiscover && !bypassScanBackoff && !lanScanBackoff.shouldScan(peer.deviceId, scanNetworkKey)) {
            return false
        }
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
            if (discovered == null || discovered.deviceId.trim() != peer.deviceId) {
                lanScanBackoff.recordFailure(peer.deviceId, scanNetworkKey)
            }
            if (discovered != null && discovered.deviceId.trim() == peer.deviceId) {
                mutex.withLock {
                    repository.applyPeerNodeState(discovered, rosterDeviceId = peer.deviceId, observedDirectly = true)
                }
                markReachable(peer.deviceId, discovered.deviceId.trim())
                return true
            }
        }

        val refreshed = mutex.withLock { repository.getDevice(peer.deviceId) } ?: current
        return hasUsableEndpoint(refreshed)
    }

    internal fun hasUsableEndpoint(peer: PairedDeviceEntity): Boolean =
        resolvePeerEndpoint(peer, tailnetNodeUp()) != null || IrohRuntime.canReach(peer.deviceId)

    private suspend fun tryStoredEndpoint(
        peer: PairedDeviceEntity,
        attempts: Int,
        retryMs: Long,
        timeoutMs: Long,
        fetchNodeState: Boolean = true
    ): Boolean {
        val tailnetUsable = tailnetNodeUp() &&
            tailnetPeerIsOnline(TailscaleNodeRuntime.observedTailnetPeers(), peer.deviceId, peer.deviceName)
        val endpoint = resolvePeerEndpoint(peer, tailnetUsable) ?: return false
        repeat(attempts) { attempt ->
            val companion = NotificationCompanion.pollValueFor(
                peer.deviceId, peer.isDockerNode(), peer.os, peer.platform, peer.tlsPin
            )
            val probe = client.probeHealth(endpoint.host, endpoint.port, timeoutMs, companion)
            val reached = probe.reached
            if (companion != null) NotificationDebugLog.log("sweep grant poll to ${NotificationDebugLog.short(peer.deviceId)} value=$companion reached=$reached")
            val answeredAs = probe.respondedAs
            if (reached && answeredAs != null && answeredAs != peer.deviceId) {
                // Someone answers at this address, but not the device stored for it.
                if (!endpoint.tailnet) {
                    mutex.withLock { repository.recordEndpointMismatch(peer.deviceId, endpoint.host, answeredAs) }
                }
                return false
            }
            if (reached) {
                markReachable(peer.deviceId)
                probe.bulletinVersion?.let { FileApexServices.bulletinSyncEngine.onPeerBulletinVersion(peer, it) }
                probe.removals?.let { theirs -> scope.launch { pullRemovals(peer, endpoint, theirs) } }
                mutex.withLock {
                    if (endpoint.tailnet) {
                        repository.touchPeerLastSeenEpoch(peer.deviceId)
                    } else {
                        repository.touchPeerLastSeen(peer.deviceId, endpoint.host, endpoint.port)
                    }
                }
                if (fetchNodeState && claimNodeStateFetch(peer, probe.membershipVersion)) {
                    val state = cancellableCatching {
                        client.fetchPeerNodeState(endpoint.host, endpoint.port, timeoutMs)
                    }.getOrNull()
                    if (state != null) {
                        if (state.deviceId.trim() != peer.deviceId) {
                            if (!endpoint.tailnet) {
                                mutex.withLock {
                                    repository.recordEndpointMismatch(peer.deviceId, endpoint.host, state.deviceId.trim())
                                }
                            }
                            return false
                        }
                        mutex.withLock {
                            repository.applyPeerNodeState(state, rosterDeviceId = peer.deviceId, observedDirectly = true)
                        }
                        // Same hardware under another id is the same device before a reinstall: retire the outdated row.
                        if (state.hardwareHash.isNotBlank()) {
                            cancellableCatching { FileApexServices.peerAdmission.supersedeHardwareTwins(state) }
                        }
                        markReachable(state.deviceId.trim())
                    }
                }
                return true
            }
            if (attempt < attempts - 1) {
                delay(retryMs)
            }
        }
        return false
    }

    private val removalPulls = RemovalPullLedger()

    /**
     * A peer whose removal fingerprint differs from ours holds a removal we missed, or the reverse. Read its records
     * once per distinct fingerprint and apply them under the usual rules (a record counts only when its stamp is
     * newer than what we hold for that device). A record naming this device is never taken from a read.
     */
    private suspend fun pullRemovals(peer: PairedDeviceEntity, endpoint: ResolvedPeerEndpoint, theirs: String) {
        val ours = cancellableCatching { repository.removalFingerprint() }.getOrNull() ?: return
        if (!removalPulls.shouldPull(peer.deviceId, theirs.trim(), ours)) return
        val localId = com.fileapex.data.identity.loadLocalIdentity().deviceId
        cancellableCatching {
            val records = client.fetchRemovedDevices(endpoint.host, endpoint.port)
                .filter { it.deviceId.isNotBlank() && it.deviceId != localId }
            if (records.isNotEmpty()) {
                FileApexServices.pairingCoordinator.mergeIncoming(
                    ClusterSyncRequest(
                        eventKind = PeerSyncEventKind.REMOVAL,
                        removedDevices = records,
                        clusterVersion = records.maxOf { it.membershipVersion() }
                    ),
                    authenticatedSenderId = peer.deviceId
                )
            }
        }.onFailure { error ->
            removalPulls.forget(peer.deviceId)
            println("PeerPresenceMonitor: removal read from ${peer.deviceName} failed: ${error.message}")
        }
    }

    private suspend fun markReachable(vararg deviceIds: String, epochMs: Long = TimeUtils.now(), overLan: Boolean = true) {
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
                val currentIds = _onlineDeviceIds.value
                // An old timestamp must not put a device in the online set.
                val toAdd = deviceIds.map { it.trim() }.filter { it.isNotEmpty() && !currentIds.contains(it) }
                    .takeIf { TimeUtils.isWithinWindow(epochMs, LanPresenceTiming.PRESENCE_READY_THRESHOLD_MS) }
                    .orEmpty()
                if (toAdd.isNotEmpty()) {
                    _onlineDeviceIds.value = currentIds + toAdd
                }
            }
        }
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
                digestSkips.directContact(trimmed)
                val dev = repository.getDevice(trimmed)
                if (dev != null && dev.lastKnownIp.isNotBlank() && dev.port > 0) {
                    repository.touchPeerLastSeen(trimmed, dev.lastKnownIp, dev.port, epochMs)
                    if ((dev.rootPath.isBlank() || dev.rootPath == "/") && claimNodeStateFetch(dev, null)) {
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
        reachabilityLock.withLock {
            if (_onlineDeviceIds.value != nextOnline) {
                _onlineDeviceIds.value = nextOnline
            }
        }
    }

    companion object {
        const val OFFLINE_GRACE_MS = LanPresenceTiming.OFFLINE_GRACE_MS
        private const val DIGEST_MIN_PEERS = 4
        private const val DIGEST_UNSUPPORTED_RETRY_MS = 6 * 60 * 60_000L
        private const val DIGEST_MAX_SOURCES = 2
        private const val DIGEST_WAIT_MS = 800L
        private const val SERVER_READY_ATTEMPTS = 25
        private const val LAN_DIRECT_HIT_TTL_MS = 30_000L
        private const val LAN_DIRECT_MISS_TTL_MS = 15_000L
        private const val SERVER_READY_POLL_MS = 100L
        private const val SERVER_SETTLE_MS = 250L
    }
}
