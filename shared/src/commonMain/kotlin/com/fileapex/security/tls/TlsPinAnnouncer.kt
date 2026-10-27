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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        PeerTlsStatus.resolver = { prompt, done ->
            scope.launch {
                val settled = try {
                    resolveKeyChange(prompt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("TlsPinAnnouncer: key change check failed - ${e::class.simpleName}")
                    false
                }
                done(settled)
            }
        }
        PeerTlsStatus.onUserConfirmed = { prompt -> withContext(Dispatchers.IO) { vouchForConfirmation(prompt) } }
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
        val result = exchange(device)
        return when (result.outcome) {
            TlsAnnouncementOutcome.Recorded,
            TlsAnnouncementOutcome.Unchanged,
            TlsAnnouncementOutcome.Rotated -> DONE_MS
            null -> result.retryMs
            else -> RETRY_MS
        }
    }

    private class Exchange(val outcome: TlsAnnouncementOutcome?, val retryMs: Long = RETRY_MS)

    /** Sends this device's key to [device] and applies the key it answers with; no outcome when no answer came. */
    private suspend fun exchange(device: PairedDeviceEntity): Exchange {
        val localId = loadLocalIdentity().deviceId
        val announcement = TlsPinAnnouncements.build(localId, device, now()) ?: return Exchange(null)
        return try {
            val reply = client.announceTlsPin(device.lastKnownIp, device.port, announcement)
                ?: return Exchange(null, OLD_PEER_MS)
            Exchange(TlsPinAnnouncements.accept(repository, reply, localId, now()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 404 from a build without TLS looks like any other failure; back off hard on it.
            println("TlsPinAnnouncer: announce to ${device.deviceId} failed - ${e::class.simpleName}")
            Exchange(null, if (e.message.orEmpty().contains("404")) OLD_PEER_MS else RETRY_MS)
        }
    }

    /**
     * A paired peer presented a key other than the stored one. In this order: another device of the cluster already
     * confirmed it; the peer's pairing key vouches for its key; the peer proves with its OLD key that it is the same
     * device. True when nothing is left to ask the user.
     */
    internal suspend fun resolveKeyChange(prompt: TlsPinPrompt): Boolean {
        val observed = prompt.pin.lowercase()
        val id = prompt.deviceId
        val device = repository.getDevice(id)
        if (device == null || device.isRemoved || device.tlsPin.equals(observed, ignoreCase = true)) return true
        if (TlsVouches.voucherFor(id, observed, now()) != null) return adopt(id, observed, prompt.port)
        val reachable = NetworkUtils.isUsableLanIpv4(device.lastKnownIp) && device.port > 0
        if (device.publicKey.isNotBlank() && reachable) {
            when (exchange(device).outcome) {
                // The peer says, authenticated, which key is its own. If that is not the one that answered us, whoever
                // answered is not the peer: the connection stays refused and there is nothing for the user to trust.
                TlsAnnouncementOutcome.Rotated, TlsAnnouncementOutcome.Recorded, TlsAnnouncementOutcome.Unchanged -> return true
                else -> Unit
            }
        }
        if (reachable) {
            val state = cancellableCatching { client.fetchPeerNodeState(device.lastKnownIp, device.port) }.getOrNull()
            val pins = setOfNotNull(device.tlsPin, device.tlsPinAlt.ifEmpty { null })
            if (state != null && state.deviceId.trim() == id &&
                TlsRotation.verify(TlsRotation.decode(state.tlsRotation), pins, observed, now())
            ) {
                return adopt(id, observed, prompt.port)
            }
        }
        return false
    }

    private suspend fun adopt(deviceId: String, pin: String, port: Int): Boolean {
        repository.recordTlsPin(deviceId, pin, port)
        PeerTlsStatus.dismiss(deviceId)
        return true
    }

    /** The user trusted a changed key here: the other online devices of the cluster learn it and need not ask. */
    internal suspend fun vouchForConfirmation(prompt: TlsPinPrompt) {
        val localId = loadLocalIdentity().deviceId
        val online = onlineDeviceIds()
        for (recipient in repository.listDevices()) {
            if (recipient.isRemoved || recipient.deviceId == prompt.deviceId || recipient.deviceId == localId) continue
            if (recipient.deviceId !in online || !NetworkUtils.isUsableLanIpv4(recipient.lastKnownIp) || recipient.port <= 0) continue
            val vouch = TlsPinAnnouncements.buildVouch(localId, recipient, prompt.deviceId, prompt.pin, prompt.port, now()) ?: continue
            cancellableCatching { client.announceTlsPin(recipient.lastKnownIp, recipient.port, vouch) }
                .onFailure { if (it is CancellationException) throw it }
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
