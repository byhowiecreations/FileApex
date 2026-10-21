package com.fileapex.security.tls

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.bulletin.BulletinPeerBackoff
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.network.FileApexClient
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Brings paired peers onto pinned TLS without a re-pair. For each online peer that has no pin yet it
 * sends this device's pin sealed with the pair's E2EE key and stores the pin in the reply. Peers without
 * a stored clipboard key cannot be authenticated this way: the user confirms their fingerprint instead.
 * A peer that already has a pin is never contacted here.
 */
class TlsPinAnnouncer(
    private val repository: DeviceRepository,
    private val client: FileApexClient,
    private val onlineDeviceIds: () -> Set<String>,
    private val scope: CoroutineScope,
    private val now: () -> Long = TimeUtils::now
) {
    private var job: Job? = null
    private val nextAttempt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val offeredPins = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val nudgeBackoff = BulletinPeerBackoff(initialDelayMs = NUDGE_BASE_GAP_MS, maxDelayMs = NUDGE_MAX_GAP_MS)
    private val lock = Any()
    private val nudgeRunner = com.fileapex.util.PerKeyRunner(scope)

    /** A caller just failed to reach [deviceId]: drop its back-off and retry that one peer, at most once per back-off step. */
    fun nudge(deviceId: String) {
        scope.launch {
            val permitted = synchronized(lock) {
                val allowed = nudgeBackoff.canAttempt(deviceId, now())
                if (allowed) {
                    nudgeBackoff.onFailure(deviceId, now())
                    nextAttempt.remove(deviceId)
                }
                allowed
            }
            if (!permitted) return@launch
            // One run per device: another device's slow answer never holds this one back.
            nudgeRunner.launch(deviceId) {
                cancellableCatching { tickLocked(onlyDeviceId = deviceId) }
                    .onFailure { if (it is CancellationException) throw it }
            }
        }
    }

    @Synchronized fun ensureStarted() {
        current = this
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val delayMs = cancellableCatching { tick() }
                    .getOrElse { error ->
                        if (error is CancellationException) throw error
                        TICK_MS
                    }
                val inForeground = com.fileapex.domain.presence.PresenceForegroundRefresh.isForeground.value
                val actualDelay = if (!inForeground) maxOf(delayMs, STANDBY_TICK_MS) else delayMs
                delay(actualDelay)
            }
        }
    }

    internal suspend fun tick(): Long = tickLocked()

    private suspend fun tickLocked(onlyDeviceId: String? = null): Long {
        val keysNeeded = fillMissingKeys(onlyDeviceId)
        if (LocalTlsInfo.pin.isEmpty() || LocalTlsInfo.port <= 0) return IDLE_TICK_MS
        val online = onlineDeviceIds()
        var announcementsNeeded = false
        var shortestDelay = IDLE_TICK_MS
        val due = ArrayList<PairedDeviceEntity>()
        for (device in repository.listDevices()) {
            if (onlyDeviceId != null && device.deviceId != onlyDeviceId) continue
            if (device.tlsPin.isNotEmpty()) {
                nudgeBackoff.onSuccess(device.deviceId)
                continue
            }
            if (device.deviceId !in online) continue
            if (!NetworkUtils.isUsableLanIpv4(device.lastKnownIp) || device.port <= 0) continue
            announcementsNeeded = true
            val dueIn = (nextAttempt[device.deviceId] ?: 0L) - now()
            if (dueIn > 0L) {
                shortestDelay = minOf(shortestDelay, dueIn)
                continue
            }
            due += device
        }
        // Every device is asked at the same time, so one that does not answer costs only its own timeout.
        val delays = coroutineScope {
            due.map { device ->
                async {
                    val delayMs = if (device.publicKey.isBlank()) offerFingerprint(device) else announce(device)
                    nextAttempt[device.deviceId] = now() + delayMs
                    delayMs
                }
            }.awaitAll()
        }
        for (d in delays) shortestDelay = minOf(shortestDelay, d)
        return if (keysNeeded || announcementsNeeded) {
            maxOf(TICK_MS, shortestDelay)
        } else {
            IDLE_TICK_MS
        }
    }

    /** Without a peer's key none of its introductions can be opened; stored only when the id is derived from it. */
    private suspend fun fillMissingKeys(onlyDeviceId: String? = null): Boolean {
        val online = onlineDeviceIds()
        var needed = false
        val pending = ArrayList<PairedDeviceEntity>()
        for (device in repository.listDevices()) {
            if (onlyDeviceId != null && device.deviceId != onlyDeviceId) continue
            if (device.publicKey.isNotBlank() || device.deviceId !in online) continue
            if (!NetworkUtils.isUsableLanIpv4(device.lastKnownIp) || device.port <= 0) continue
            needed = true
            if (now() < (nextAttempt["key:${device.deviceId}"] ?: 0L)) continue
            pending += device
        }
        coroutineScope {
            pending.map { device ->
                async {
                    val state = cancellableCatching { client.fetchPeerNodeState(device.lastKnownIp, device.port) }
                        .onFailure { println("TlsPinAnnouncer: key fetch from ${NetworkUtils.lastOctetLabel(device.lastKnownIp)} failed (${it::class.simpleName})") }
                        .getOrNull()
                    val stored = state != null && state.deviceId.trim() == device.deviceId &&
                        repository.recordBoundPublicKey(device.deviceId, state.publicKey)
                    nextAttempt["key:${device.deviceId}"] = now() + if (stored) DONE_MS else RETRY_MS
                }
            }.awaitAll()
        }
        return needed
    }

    private suspend fun announce(device: PairedDeviceEntity): Long {
        val localId = loadLocalIdentity().deviceId
        val announcement = TlsPinAnnouncements.build(localId, device, now()) ?: return RETRY_MS
        return try {
            val reply = client.announceTlsPin(device.lastKnownIp, device.port, announcement) ?: return OLD_PEER_MS
            when (TlsPinAnnouncements.accept(repository, reply, localId, now())) {
                TlsAnnouncementOutcome.Recorded,
                TlsAnnouncementOutcome.Unchanged -> DONE_MS
                else -> RETRY_MS
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 404 from a build without TLS looks like any other failure; back off hard on it.
            println("TlsPinAnnouncer: announce to ${device.deviceId} failed - ${e::class.simpleName}")
            if (e.message.orEmpty().contains("404")) OLD_PEER_MS else RETRY_MS
        }
    }

    /** No shared key to authenticate with: surface the peer's claimed pin for the user to verify. */
    private suspend fun offerFingerprint(device: PairedDeviceEntity): Long {
        val state = cancellableCatching { client.fetchPeerNodeState(device.lastKnownIp, device.port) }.getOrNull()
            ?: return RETRY_MS
        if (state.deviceId.trim() == device.deviceId &&
            repository.recordBoundPublicKey(device.deviceId, state.publicKey)
        ) {
            return BOUND_KEY_RETRY_MS
        }
        val pin = state.tlsPin.lowercase()
        if (!PIN_FORMAT.matches(pin) || state.tlsPort !in 1..65535) return OLD_PEER_MS
        if (offeredPins[device.deviceId] != pin) {
            offeredPins[device.deviceId] = pin
            PeerTlsStatus.requestConfirmation(TlsPinPrompt(device.deviceId, TlsPromptKind.NEW_PEER_KEY, pin, state.tlsPort))
        }
        return OLD_PEER_MS
    }

    companion object {
        @Volatile private var current: TlsPinAnnouncer? = null

        /** Retry the pin exchange for a peer that just failed to answer. */
        fun nudge(deviceId: String) { current?.nudge(deviceId) }

        private const val TICK_MS = 20_000L
        private const val IDLE_TICK_MS = 300_000L
        private const val STANDBY_TICK_MS = 300_000L
        private const val NUDGE_BASE_GAP_MS = 60_000L
        private const val NUDGE_MAX_GAP_MS = 30 * 60_000L
        private const val BOUND_KEY_RETRY_MS = 1_000L
        private const val RETRY_MS = 60_000L
        private const val DONE_MS = 10 * 60_000L
        private const val OLD_PEER_MS = 30 * 60_000L
        private val PIN_FORMAT = Regex("[0-9a-f]{64}")
    }
}
