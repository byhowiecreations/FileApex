package com.fileapex.domain.pairing

import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.network.PairingBeaconTransport
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HostPairingBroadcastState(
    val active: Boolean = false,
    val timedOut: Boolean = false,
    val remainingMs: Long = 0L,
    val remainingLabel: String = "1:00"
)

/**
 * Host pairing-mode broadcast and joiner LAN discovery for the 6-digit pairing flow.
 */
object LanPairingDiscovery {
    private val lock = Any()
    private var activeCode: String? = null
    private var lastUsedCode: String? = null
    private var startedAtMs: Long = 0L
    private var hostTimedOut = false
    private var discoveryRefs = 0

    private val seen = LinkedHashMap<String, SeenBeacon>()

    private val _hostState = MutableStateFlow(HostPairingBroadcastState())
    val hostState: StateFlow<HostPairingBroadcastState> = _hostState.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<PairingBeacon>>(emptyList())
    val discoveredPeers: StateFlow<List<PairingBeacon>> = _discoveredPeers.asStateFlow()

    fun lastUsedCode(): String? = synchronized(lock) { lastUsedCode }

    fun tickHost(): HostPairingBroadcastState {
        var stopTransport = false
        val state = synchronized(lock) {
            if (activeCode == null) {
                HostPairingBroadcastState(timedOut = hostTimedOut, remainingLabel = "0:00")
            } else {
                val remaining = (PairingBeacon.HOST_TTL_MS - TimeUtils.millisSince(startedAtMs))
                    .coerceAtLeast(0L)
                if (remaining <= 0L) {
                    expireHostLocked()
                    stopTransport = true
                    HostPairingBroadcastState(timedOut = true, remainingLabel = "0:00")
                } else {
                    HostPairingBroadcastState(
                        active = true,
                        remainingMs = remaining,
                        remainingLabel = formatRemaining(remaining)
                    )
                }
            }
        }
        if (stopTransport) {
            PairingBeaconTransport.stopBroadcast()
        }
        _hostState.value = state
        return state
    }

    fun startHost(payload: PairingPayload) {
        val beacon = beaconFromPayload(payload) ?: return
        synchronized(lock) {
            stopHostLocked(timedOut = false)
            activeCode = beacon.pairingCode
            lastUsedCode = beacon.pairingCode
            startedAtMs = TimeUtils.now()
            hostTimedOut = false
        }
        PairingBeaconTransport.sendBeaconOnce(beacon)
        PairingBeaconTransport.startBroadcast(beacon)
        _hostState.value = HostPairingBroadcastState(
            active = true,
            remainingMs = PairingBeacon.HOST_TTL_MS,
            remainingLabel = formatRemaining(PairingBeacon.HOST_TTL_MS)
        )
    }

    fun stopHost() {
        synchronized(lock) { stopHostLocked(timedOut = false) }
        PairingBeaconTransport.stopBroadcast()
        _hostState.value = HostPairingBroadcastState(remainingLabel = "0:00")
    }

    fun matchesActiveCode(code: String): Boolean {
        val digits = PairingBeacon.digitsOnly(code)
        if (digits.length != 6) return false
        synchronized(lock) {
            return activeCode != null && !hostTimedOut && digits == activeCode
        }
    }

    fun onHostPairingAccepted() {
        stopHost()
    }

    fun startDiscovery() {
        val shouldStart = synchronized(lock) {
            discoveryRefs++
            discoveryRefs == 1
        }
        if (shouldStart) {
            PairingBeaconTransport.startListener { beacon -> onBeaconReceived(beacon) }
        }
    }

    fun stopDiscovery() {
        val shouldStop = synchronized(lock) {
            if (discoveryRefs == 0) return
            discoveryRefs--
            discoveryRefs == 0
        }
        if (shouldStop) {
            PairingBeaconTransport.stopListener()
            synchronized(lock) { seen.clear() }
            _discoveredPeers.value = emptyList()
        }
    }

    fun matchInput(input: String, peers: List<PairingBeacon> = _discoveredPeers.value): PairingPayload? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null
        PairingPayload.parseOrNull(trimmed)?.let { return it }
        val digits = PairingBeacon.digitsOnly(trimmed)
        if (digits.length != 6) return null
        return peers.firstOrNull { it.matchesCode(digits) }?.toPairingPayload()
    }

    fun pruneStalePeers() {
        val localId = runCatching { loadLocalIdentity().deviceId }.getOrNull().orEmpty()
        val now = TimeUtils.now()
        val fresh = synchronized(lock) {
            val iterator = seen.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.lastSeenMs > PairingBeacon.BEACON_STALE_MS) {
                    iterator.remove()
                }
            }
            seen.values
                .map { it.beacon }
                .filter { it.deviceId != localId }
                .sortedBy { it.deviceName.lowercase() }
        }
        _discoveredPeers.value = fresh
    }

    private fun onBeaconReceived(beacon: PairingBeacon) {
        val localId = runCatching { loadLocalIdentity().deviceId }.getOrNull().orEmpty()
        if (beacon.deviceId == localId) return
        if (!NetworkUtils.isUsableLanIpv4(beacon.ipAddress)) return
        synchronized(lock) {
            seen[beacon.deviceId] = SeenBeacon(beacon, TimeUtils.now())
        }
        pruneStalePeers()
    }

    private fun expireHostLocked() {
        activeCode = null
        hostTimedOut = true
        startedAtMs = 0L
    }

    private fun stopHostLocked(timedOut: Boolean) {
        activeCode = null
        hostTimedOut = timedOut
        startedAtMs = 0L
    }

    private fun beaconFromPayload(payload: PairingPayload): PairingBeacon? {
        val code = payload.pairingCode.filter { it.isDigit() }
        if (code.length != 6) return null
        if (payload.deviceId.isBlank() || !NetworkUtils.isUsableLanIpv4(payload.host)) return null
        return PairingBeacon(
            deviceName = payload.deviceName,
            ipAddress = payload.host,
            port = payload.port,
            pairingCode = code,
            timestamp = TimeUtils.now(),
            deviceId = payload.deviceId,
            pinRequired = payload.pinRequired
        )
    }

    private fun formatRemaining(remainingMs: Long): String {
        val totalSec = (remainingMs / 1000L).coerceAtLeast(0L)
        val minutes = totalSec / 60L
        val seconds = totalSec % 60L
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }

    private data class SeenBeacon(
        val beacon: PairingBeacon,
        val lastSeenMs: Long
    )
}
