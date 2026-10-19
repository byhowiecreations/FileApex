package com.fileapex.iroh

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.db.isDockerNode
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.presence.ResolvedPeerEndpoint
import com.fileapex.domain.presence.RouteUse
import com.fileapex.domain.presence.tailnetNodeUp
import com.fileapex.network.ServerLifecycleManager
import com.fileapex.platform.hasUsableNetwork
import com.fileapex.platform.isActiveLanConnectivity
import com.fileapex.util.AddressRedaction
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class IrohPhase { Off, Idle, NoNetwork, Unavailable, Starting, Up, Failed }

data class IrohState(
    val phase: IrohPhase = IrohPhase.Off,
    val endpointId: String = "",
    val message: String = ""
)

data class IrohProbeResult(val ok: Boolean, val millis: Long, val path: String?, val message: String)

object IrohRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(IrohState())
    val state: StateFlow<IrohState> = mutableState.asStateFlow()

    private var link: IrohLinkHandle? = null
    private var bridge: IrohBridge? = null
    private var presenceJob: Job? = null
    private var attached = false
    private var startedRelay: IrohRelayConfig? = null
    private var reconcileJob: Job? = null
    private var notNeededSince = 0L
    private var lastHoldersText = ""
    private val servedViaIroh = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastExchangeAttempt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val exchangeAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val contactOkAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var lastAddressPushAt = 0L
    private val ownAddressAtExchange = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val reachability = IrohReachabilityCache(HIT_TTL_MS, MISS_TTL_MS, TimeUtils::now)
    private val names = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val hadAnswerBefore = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun attach() {
        if (attached) return
        attached = true
        IrohLog.labeler = { id -> names[id] }
        scope.launch {
            // An earlier build could save a bridge endpoint as a peer's address; put those rows right.
            cancellableCatching { FileApexServices.deviceRepository.clearLoopbackEndpoints() }
        }
        scope.launch {
            val settings = FileApexServices.settings
            combine(settings.irohEnabled, settings.irohRelayEnabled, settings.irohRelayUrl, settings.irohRestWhenIdle) { on, _, _, _ -> on }
                .collect { enabled ->
                    if (!enabled) {
                        reconcileJob?.cancel()
                        reconcileJob = null
                        shutdown(IrohPhase.Off)
                        return@collect
                    }
                    // A changed relay needs a new endpoint; it comes back at once if a device needs it.
                    if (link != null && startedRelay != currentRelay()) shutdown(IrohPhase.Idle)
                    if (state.value.phase == IrohPhase.Off) mutableState.value = IrohState(IrohPhase.Idle)
                    if (reconcileJob?.isActive != true) reconcileJob = scope.launch { reconcileLoop() }
                    reconcile()
                }
        }
    }

    /** The relay to use: the saved self-hosted one when switched on, else null for n0's public relays. */
    private fun currentRelay(): IrohRelayConfig? {
        val settings = FileApexServices.settings
        if (!settings.irohRelayEnabled.value) return null
        val checked = IrohRelayUrl.check(settings.irohRelayUrl.value) as? IrohRelayUrlCheck.Valid ?: return null
        return IrohRelayConfig(checked.url, settings.irohRelayToken())
    }

    fun isEnabled(): Boolean = FileApexServices.settings.irohEnabled.value

    /** Called when the device moves between networks, so a lost LAN wakes the endpoint at once. */
    fun onNetworkChanged() {
        if (!isEnabled()) return
        scope.launch {
            delay(NETWORK_SETTLE_MS)
            reconcile()
        }
    }

    private suspend fun reconcileLoop() {
        coroutineScope {
            launch { FileApexServices.presenceMonitor.onlineDeviceIds.collect { reconcile() } }
            launch { while (true) { delay(RECONCILE_MS); reconcile() } }
        }
    }

    suspend fun reconcile() {
        if (!isEnabled()) return
        // Coroutine entry point: a failed database read must not end the loop that keeps the endpoint up.
        cancellableCatching { reconcileOnce() }.onFailure { IrohLog.add("reconcile failed: ${it::class.simpleName}") }
    }

    /**
     * The endpoint is up whenever Iroh is on and there is a network. With "let Iroh rest" on it also rests when no
     * paired device needs it. With no network nothing is dialed, probed or kept alive.
     */
    private suspend fun reconcileOnce() {
        if (!hasUsableNetwork()) {
            if (link != null) {
                IrohLog.add("no network: Iroh closed")
                shutdown(IrohPhase.NoNetwork)
            } else if (state.value.phase != IrohPhase.NoNetwork) {
                mutableState.value = IrohState(IrohPhase.NoNetwork)
            }
            return
        }
        if (state.value.phase == IrohPhase.NoNetwork) mutableState.value = IrohState(IrohPhase.Idle)
        if (!FileApexServices.settings.irohRestWhenIdle.value || needed()) {
            notNeededSince = 0L
            if (link == null && state.value.phase != IrohPhase.Unavailable) start()
            return
        }
        if (link == null) return
        val now = TimeUtils.now()
        if (notNeededSince == 0L) notNeededSince = now
        if (IrohWakePolicy.shouldSleep(notNeededSince, now)) {
            IrohLog.add("no device needs Iroh now: going idle")
            shutdown(IrohPhase.Idle)
        }
    }

    /** The devices that need the endpoint right now, each with why. Empty means it may rest. */
    private suspend fun holders(): List<String> {
        val online = FileApexServices.presenceMonitor.onlineDeviceIds.value
        val book = peerBook()
        val now = TimeUtils.now()
        val noLocalRoute = !isActiveLanConnectivity() && !tailnetNodeUp()
        return FileApexServices.deviceRepository.listDevices().mapNotNull { device ->
            if (device.isRemoved || !supportsIroh(device)) return@mapNotNull null
            val outcome = exchangeOutcomes.value[device.deviceId]
            if (IrohPeerStatus.negativeActive(outcome, exchangeAt[device.deviceId] ?: 0L, contactOkAt[device.deviceId] ?: 0L, now)) {
                return@mapNotNull null
            }
            IrohWakePolicy.reason(
                hasAddress = book.address(device.deviceId) != null,
                online = device.deviceId in online,
                servedAtMs = servedViaIroh[device.deviceId],
                nowMs = now,
                noLocalRoute = noLocalRoute,
                exchangePending = !lastExchangeAttempt.containsKey(device.deviceId),
                recentlyHeard = IrohWakePolicy.heardRecently(
                    maxOf(device.lastSeenEpochMs, contactOkAt[device.deviceId] ?: 0L),
                    now
                )
            )?.let { "${device.deviceName} ($it)" }
        }
    }

    private suspend fun needed(): Boolean {
        val held = holders()
        val text = held.joinToString("; ")
        if (text != lastHoldersText) {
            lastHoldersText = text
            val line = if (held.isEmpty()) "nothing needs Iroh now" else "kept up for: $text"
            IrohLog.add(line)
        }
        return held.isNotEmpty()
    }

    /** Starts the endpoint when Iroh is switched on, so a caller that found no other route can use it. */
    private suspend fun ensureStarted(): IrohLinkHandle? {
        if (!isEnabled()) return null
        link?.let { return it }
        if (!hasUsableNetwork()) return null
        start()
        return link
    }

    private suspend fun start() = lock.withLock {
        if (link != null || !isEnabled()) return@withLock
        val engine = embeddedIrohEngine()
        if (engine == null) {
            mutableState.value = IrohState(IrohPhase.Unavailable, message = "not_built_in")
            return@withLock
        }
        mutableState.value = IrohState(IrohPhase.Starting)
        try {
            ServerLifecycleManager.ensureRunning()
            val relay = currentRelay()
            val started = engine.start(loadOrCreateSecret(), relay)
            startedRelay = relay
            restorePeers(started)
            val newBridge = IrohBridge(
                link = started,
                serverPort = loadLocalIdentity().sharePort,
                isPeerAllowed = ::isPairedEndpoint,
                scope = scope,
                onPeerActive = ::peerEndpointActive
            )
            newBridge.start()
            link = started
            bridge = newBridge
            refreshNames(started.id)
            IrohLog.add("running as ${IrohLog.short(started.id)}" + if (relay != null) " on the self-hosted relay" else "")
            mutableState.value = IrohState(IrohPhase.Up, endpointId = started.id)
            presenceJob = scope.launch { exchangeWithOnlinePeers() }
            started.onAddressChanged { scope.launch { pushAddress() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkageError) {
            // The native library has no build for this machine, for example an Intel Mac.
            IrohLog.add("native library missing: ${e.message.orEmpty().take(80)}")
            mutableState.value = IrohState(IrohPhase.Unavailable, message = "native_missing")
        } catch (e: Exception) {
            // JNI boundary: report the class only, never peer addresses.
            IrohLog.add("start failed: ${e::class.simpleName} ${e.message.orEmpty().take(80)}")
            mutableState.value = IrohState(IrohPhase.Failed, message = e::class.simpleName.orEmpty())
        }
    }

    private suspend fun shutdown(phase: IrohPhase) = lock.withLock {
        presenceJob?.cancel()
        presenceJob = null
        val running = bridge
        bridge = null
        link = null
        if (phase == IrohPhase.Off) {
            lastExchangeAttempt.clear()
            ownAddressAtExchange.clear()
            exchangeAt.clear()
            contactOkAt.clear()
        }
        reachability.clear()
        hadAnswerBefore.clear()
        servedViaIroh.clear()
        startedRelay = null
        notNeededSince = 0L
        if (running != null) cancellableCatching { running.stop() }
        mutableState.value = IrohState(phase)
    }

    private fun loadOrCreateSecret(): ByteArray {
        val settings = FileApexServices.settings
        val stored = runCatching { Base64.getDecoder().decode(settings.irohSecretKeyBase64()) }.getOrNull()
        if (stored != null && stored.size == SECRET_BYTES) return stored
        val fresh = ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) }
        settings.setIrohSecretKeyBase64(Base64.getEncoder().encodeToString(fresh))
        return fresh
    }

    private fun peerBook(): IrohPeerBook = IrohPeerBook.parse(FileApexServices.settings.irohPeersJson())

    private fun restorePeers(started: IrohLinkHandle) {
        val book = peerBook()
        for (deviceId in book.deviceIds()) {
            book.address(deviceId)?.let { runCatching { started.addPeer(it) } }
        }
    }

    /** True only for an endpoint that a paired, non-removed device handed us through the sealed exchange. */
    private suspend fun isPairedEndpoint(endpointId: String): Boolean {
        val deviceId = peerBook().deviceIdFor(endpointId) ?: return false
        val device = FileApexServices.deviceRepository.getDevice(deviceId) ?: return false
        return !device.isRemoved
    }

    private fun httpStatusOverBridge(port: Int, selfId: String, timeoutMs: Int = PROBE_TIMEOUT_MS): Int? {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(IrohBridge.LOOPBACK_V4, port), 5_000)
            socket.soTimeout = timeoutMs
            socket.getOutputStream().write(
                "GET /api/v1/health?from=$selfId HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".encodeToByteArray()
            )
            socket.getOutputStream().flush()
            val line = socket.getInputStream().bufferedReader().readLine() ?: return null
            return line.split(" ").getOrNull(1)?.toIntOrNull()
        }
    }

    private suspend fun refreshNames(ownId: String) {
        names[ownId] = "this device"
        val book = peerBook()
        for (device in FileApexServices.deviceRepository.listDevices()) {
            book.endpointId(device.deviceId)?.let { names[it] = device.deviceName }
            names[device.deviceId] = device.deviceName
        }
    }

    /** First eight characters of the Iroh key stored for [deviceId], for the settings page. */
    fun peerKeyLabel(deviceId: String): String? = peerBook().endpointId(deviceId)?.take(8)

    fun ownKeyLabel(): String? = link?.id?.take(8)

    /** First eight characters of this device's own id, as other devices list it. */
    fun ownDeviceIdLabel(): String = loadLocalIdentity().deviceId.take(8)

    private fun remember(deviceId: String, address: String) {
        val updated = peerBook().with(deviceId, address)
        FileApexServices.settings.setIrohPeersJson(updated.toJson())
        link?.let { runCatching { it.addPeer(address) } }
        reachability.forget(deviceId)
        scope.launch { cancellableCatching { FileApexServices.deviceRepository.getDevice(deviceId)?.let { d -> peerBook().endpointId(deviceId)?.let { names[it] = d.deviceName }; names[d.deviceId] = d.deviceName } } }
    }

    /** Called by the server route: a paired peer sent its address and is owed ours. Null when Iroh is off. */
    suspend fun answerExchange(message: IrohAddressMessage): AddressAnswer? {
        // A resting endpoint wakes for a peer that asks; answering "Iroh off" would make the peer give up on us.
        val running = ensureStarted() ?: return null
        val repository = FileApexServices.deviceRepository
        val sender = repository.getDevice(message.senderDeviceId)
        val answer = IrohAddressExchange.answer(
            message = message,
            sender = sender,
            localDeviceId = loadLocalIdentity().deviceId,
            ownAddress = running.address(),
            nowMs = TimeUtils.now()
        )
        if (answer is AddressAnswer.Reply && sender != null) {
            remember(sender.deviceId, answer.learned)
            // A peer that sends its address plainly has Iroh, whatever an earlier answer said.
            noteContact(sender.deviceId)
        }
        return answer
    }

    private val exchangeOutcomes = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Last exchange outcome per device id: `ok` or a short reason, so a failure is visible in the UI. */
    val lastExchange: StateFlow<Map<String, String>> = exchangeOutcomes.asStateFlow()

    private fun record(deviceId: String, outcome: String): String {
        val clean = AddressRedaction.redact(outcome)
        exchangeOutcomes.update { it + (deviceId to clean) }
        exchangeAt[deviceId] = TimeUtils.now()
        IrohLog.add("exchange with ${IrohLog.short(deviceId)}: $clean")
        return clean
    }

    /**
     * The peer was heard from over Iroh: it has Iroh, so a "no Iroh" or failed exchange no longer applies, and
     * its traffic counts as activity for the optional rest mode.
     */
    private fun noteContact(deviceId: String) {
        val now = TimeUtils.now()
        contactOkAt[deviceId] = now
        servedViaIroh[deviceId] = now
        exchangeOutcomes.update { current ->
            val outcome = current[deviceId]
            if (outcome == null || outcome == IrohExchangePolicy.OK) current else current + (deviceId to IrohExchangePolicy.OK)
        }
    }

    private fun peerEndpointActive(endpointId: String) {
        peerBook().deviceIdFor(endpointId)?.let(::noteContact)
    }

    /** Our address changed (new network or home relay): tell every online peer that has Iroh, at most every 30 s. */
    private suspend fun pushAddress() {
        if (!canExchange()) return
        val now = TimeUtils.now()
        if (now - lastAddressPushAt < ADDRESS_PUSH_MIN_MS) return
        lastAddressPushAt = now
        val online = FileApexServices.presenceMonitor.onlineDeviceIds.value
        for (device in FileApexServices.deviceRepository.listDevices()) {
            if (device.isRemoved || device.deviceId !in online || !supportsIroh(device)) continue
            exchangeWith(device)
        }
    }

    /** Sends our address to [device] over the LAN and stores the one it sends back. Returns `ok` or the reason. */
    suspend fun exchangeWith(device: PairedDeviceEntity): String {
        lastExchangeAttempt[device.deviceId] = TimeUtils.now()
        val running = ensureStarted() ?: return record(device.deviceId, "iroh_off")
        val endpoint = FileApexServices.presenceMonitor.endpointIfOnline(device, RouteUse.AddressExchange)
            ?: return record(device.deviceId, "no_lan_route")
        val selfId = loadLocalIdentity().deviceId
        val stamp = IrohAddressExchange.newStamp()
        val ask = IrohAddressExchange.buildAsk(selfId, device, running.address(), stamp, TimeUtils.now())
            ?: return record(device.deviceId, "no_pair_key")
        val reply = try {
            FileApexServices.client.exchangeIrohAddress(endpoint.host, endpoint.port, ask)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return record(device.deviceId, "request_failed: ${e.message.orEmpty().take(80)}")
        } ?: return record(device.deviceId, PEER_WITHOUT_IROH)
        val address = IrohAddressExchange.open(reply, device, stamp, selfId)
            ?: return record(device.deviceId, "reply_rejected")
        remember(device.deviceId, address)
        ownAddressAtExchange[device.deviceId] = running.address()
        return record(device.deviceId, IrohExchangePolicy.OK)
    }

    private suspend fun exchangeWithOnlinePeers() {
        coroutineScope {
            launch { FileApexServices.presenceMonitor.onlineDeviceIds.collect { exchangeRound(it) } }
            // A "no Iroh" answer expires, so a quiet presence flow must not leave it unasked.
            launch {
                while (true) {
                    delay(EXCHANGE_TICK_MS)
                    exchangeRound(FileApexServices.presenceMonitor.onlineDeviceIds.value)
                }
            }
        }
    }

    private suspend fun exchangeRound(online: Set<String>) {
        if (!canExchange()) return
        for (deviceId in online) {
            val now = TimeUtils.now()
            if (!exchangeDue(deviceId, now)) continue
            lastExchangeAttempt[deviceId] = now
            val device = FileApexServices.deviceRepository.getDevice(deviceId) ?: continue
            if (device.isRemoved || !supportsIroh(device)) continue
            exchangeWith(device)
        }
    }

    private fun canExchange(): Boolean =
        IrohExchangePolicy.mayExchange(hasLan = isActiveLanConnectivity(), hasTailnet = tailnetNodeUp())

    private fun exchangeDue(deviceId: String, now: Long): Boolean {
        val last = lastExchangeAttempt[deviceId]
        return IrohExchangePolicy.due(
            lastOutcome = exchangeOutcomes.value[deviceId],
            ageMs = last?.let { now - it },
            ownAddressChanged = link?.address() != ownAddressAtExchange[deviceId]
        )
    }

    /**
 * Docker nodes and builds older than 0.19.0 have no Iroh route, so they are never asked. A Docker node is
 * recognised by the version string the daemon always reports (`docker`), never by its name, which users choose.
 */
    const val PEER_WITHOUT_IROH = IrohExchangePolicy.PEER_WITHOUT_IROH

    fun supportsIroh(device: PairedDeviceEntity): Boolean =
        !device.isDockerNode() && (IrohCapability.advertised(device.supportedProtocolsJson) ?: versionAllows(device))

    /** The protocol list this device advertises, with the Iroh marker that matches its own state. */
    fun advertisedProtocols(): List<String> =
        IrohCapability.ownProtocols(builtIn = state.value.phase != IrohPhase.Unavailable, enabled = isEnabled())

    private fun versionAllows(device: PairedDeviceEntity): Boolean {
        // Some builds report the year in front (2026204 is build 204), so compare the build number alone.
        val code = device.clientVersionCode.let { if (it >= 1_000_000) it % 1000 else it }
        if (code >= MIN_VERSION_CODE) return true
        // The version code is 0 on some stored rows, so the name decides: 0.19.0 and newer have Iroh.
        // A blank version stays allowed; the exchange outcome then tells whether the peer has Iroh.
        val match = Regex("(\\d+)\\.(\\d+)").find(device.clientVersion) ?: return code == 0
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toInt()
        return major > 0 || minor >= 19
    }

    /** `direct` or `relay` for the live connection to [deviceId], or null when none is open. */
    fun livePath(deviceId: String): String? {
        val peerId = peerBook().endpointId(deviceId) ?: return null
        return link?.pathKind(peerId)
    }

    fun isUp(): Boolean = state.value.phase == IrohPhase.Up

    /** True when Iroh is switched on and able to run, even while the endpoint is asleep. */
    fun isUsable(): Boolean = isEnabled() && state.value.phase != IrohPhase.Unavailable

    /** True when Iroh is usable and [deviceId] has handed us an address. Says nothing about reachability. */
    fun canReach(deviceId: String): Boolean = isUsable() && peerBook().address(deviceId) != null

    /**
     * The loopback endpoint that carries HTTP to [device] over Iroh, or null when the peer did not answer.
     * The answer is cached, so a queue of sends costs one probe. The first connect through a relay can take
     * several seconds, hence the long timeout.
     */
    suspend fun reachableEndpoint(device: PairedDeviceEntity): ResolvedPeerEndpoint? {
        val peerId = peerBook().endpointId(device.deviceId) ?: return null
        val running = ensureStarted() ?: return null
        val activeBridge = bridge ?: return null
        val selfId = loadLocalIdentity().deviceId
        val port = activeBridge.portFor(peerId)
        // A cached answer must not hide a connection that has gone away: reconnecting here, before the
        // caller's short request timeout starts, keeps a cold relay connect out of the request itself.
        try {
            running.connect(peerId)
        } catch (e: java.io.IOException) {
            reachability.forget(device.deviceId)
            return null
        }
        // An open connection with a live path is already proof the peer is there; asking it again would only
        // spend radio time. The HTTP probe is for a peer we have not talked to, or whose connection dropped.
        val reachable = reachability.check(device.deviceId) {
            if (running.isConnected(peerId) && hadAnswerBefore.contains(device.deviceId)) return@check true
            withContext(Dispatchers.IO) {
                try {
                    running.connect(peerId)
                    (httpStatusOverBridge(port, selfId, REACHABILITY_TIMEOUT_MS) in 200..299).also { ok ->
                        if (ok) hadAnswerBefore.add(device.deviceId)
                    }
                } catch (e: java.io.IOException) {
                    false
                }
            }
        }
        if (reachable) noteContact(device.deviceId) else servedViaIroh.remove(device.deviceId)
        return if (reachable) ResolvedPeerEndpoint(host = "127.0.0.1", port = port, tailnet = false) else null
    }

    fun knownPeerIds(): Set<String> = peerBook().deviceIds()

    /** One health request to [deviceId] through the bridge. Shows whether the path is direct or relayed. */
    suspend fun probe(deviceId: String): IrohProbeResult {
        val running = ensureStarted() ?: return IrohProbeResult(false, 0, null, "off")
        val activeBridge = bridge ?: return IrohProbeResult(false, 0, null, "off")
        val peerId = peerBook().endpointId(deviceId) ?: return IrohProbeResult(false, 0, null, "no_address")
        val selfId = loadLocalIdentity().deviceId
        return withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            try {
                running.connect(peerId)
            } catch (e: java.io.IOException) {
                val millis = (System.nanoTime() - started) / 1_000_000
                return@withContext IrohProbeResult(false, millis, null, AddressRedaction.redact("connect_failed: ${e.message.orEmpty().take(100)}"))
            }
            val port = activeBridge.portFor(peerId)
            val outcome = try {
                httpStatusOverBridge(port, selfId) in 200..299
            } catch (e: java.io.IOException) {
                IrohLog.add("probe request failed: ${e::class.simpleName} ${e.message.orEmpty().take(80)}")
                false
            }
            val millis = (System.nanoTime() - started) / 1_000_000
            IrohLog.add("probe to ${IrohLog.short(peerId)}: ${if (outcome) "ok" else "no answer"} in $millis ms")
            if (outcome) noteContact(deviceId)
            IrohProbeResult(outcome, millis, running.pathKind(peerId), if (outcome) "ok" else "no_answer")
        }
    }

    private const val SECRET_BYTES = 32
    private const val RECONCILE_MS = 20_000L
    private const val ADDRESS_PUSH_MIN_MS = 30_000L
    private const val EXCHANGE_TICK_MS = 60_000L
    private const val NETWORK_SETTLE_MS = 3_000L
    const val MIN_VERSION_CODE = 206
    private const val HIT_TTL_MS = 5 * 60_000L
    private const val MISS_TTL_MS = 60_000L
    private const val REACHABILITY_TIMEOUT_MS = 12_000
    private const val PROBE_TIMEOUT_MS = 15_000
}
