package com.fileapex.security.tls

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.network.FileApexClient
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

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
    private val nextAttempt = HashMap<String, Long>()
    private val offeredPins = HashMap<String, String>()
    private val lock = kotlinx.coroutines.sync.Mutex()

    /** A caller just failed to reach [deviceId]: drop its back-off and try the pin exchange now. */
    fun nudge(deviceId: String) {
        scope.launch {
            lock.withLock { nextAttempt.remove(deviceId) }
            runCatching { tick() }.onFailure { if (it is CancellationException) throw it }
        }
    }

    fun ensureStarted() {
        current = this
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                runCatching { tick() }
                    .onFailure { error -> if (error is CancellationException) throw error }
                delay(TICK_MS)
            }
        }
    }

    internal suspend fun tick() = lock.withLock { tickLocked() }

    private suspend fun tickLocked() {
        if (LocalTlsInfo.pin.isEmpty() || LocalTlsInfo.port <= 0) return
        val online = onlineDeviceIds()
        for (device in repository.listDevices()) {
            if (device.tlsPin.isNotEmpty() || device.deviceId !in online) continue
            if (!NetworkUtils.isUsableLanIpv4(device.lastKnownIp) || device.port <= 0) continue
            if (now() < (nextAttempt[device.deviceId] ?: 0L)) continue
            val delayMs = if (device.publicKey.isBlank()) offerFingerprint(device) else announce(device)
            nextAttempt[device.deviceId] = now() + delayMs
        }
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
        val state = runCatching { client.fetchPeerNodeState(device.lastKnownIp, device.port) }.getOrNull()
            ?: return RETRY_MS
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
        private const val RETRY_MS = 60_000L
        private const val DONE_MS = 10 * 60_000L
        private const val OLD_PEER_MS = 30 * 60_000L
        private val PIN_FORMAT = Regex("[0-9a-f]{64}")
    }
}


