package com.fileapex.security.tls

import com.fileapex.data.db.PairedDeviceEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/** Where to reach a pinned peer over TLS. A route means plain HTTP to that peer is never used. */
data class PeerTlsRoute(val deviceId: String, val pins: Set<String>, val tlsPort: Int)

data class PeerTlsEndpoint(val host: String, val httpPort: Int, val route: PeerTlsRoute)

object PeerTlsRoutes {
    private val byEndpoint = AtomicReference<Map<String, PeerTlsRoute>>(emptyMap())

    /** Mac: pushes the table to the native client, which dials peers itself. */
    @Volatile
    var onChange: ((List<PeerTlsEndpoint>) -> Unit)? = null

    fun snapshot(): List<PeerTlsEndpoint> = byEndpoint.get().map { (key, route) ->
        PeerTlsEndpoint(key.substringBeforeLast(':'), key.substringAfterLast(':').toInt(), route)
    }

    fun update(devices: List<PairedDeviceEntity>) {
        val map = HashMap<String, PeerTlsRoute>()
        for (device in devices) {
            if (device.isRemoved || device.tlsPin.isEmpty() || device.tlsPort !in 1..65535) continue
            val ip = device.lastKnownIp.trim()
            if (ip.isEmpty() || device.port <= 0) continue
            map[key(ip, device.port)] = PeerTlsRoute(
                deviceId = device.deviceId,
                pins = setOfNotNull(device.tlsPin, device.tlsPinAlt.ifEmpty { null }),
                tlsPort = device.tlsPort
            )
        }
        byEndpoint.set(map)
        onChange?.invoke(snapshot())
    }

    fun lookup(host: String, httpPort: Int): PeerTlsRoute? = byEndpoint.get()[key(host, httpPort)]

    fun isPinned(host: String, httpPort: Int): Boolean = lookup(host, httpPort) != null

    private fun key(host: String, port: Int) = "${host.trim()}:$port"
}

enum class TlsPromptKind {
    /** A peer's key was offered by an unauthenticated source; the user compares fingerprints. */
    NEW_PEER_KEY,

    /** A pinned peer now presents a different key (reinstall, factory reset, or an attacker). */
    KEY_CHANGED
}

data class TlsPinPrompt(val deviceId: String, val kind: TlsPromptKind, val pin: String, val port: Int)

object TlsFingerprint {
    /** First 64 bits of the pin in four groups, for people to compare. */
    fun short(pin: String): String = pin.lowercase().take(16).chunked(4).joinToString("-")
}

/**
 * Devices whose TLS handshake failed a pin check, and the confirmations the user owes.
 * A prompt only ever records a pin when the user accepts it.
 */
object PeerTlsStatus {
    private val mismatched = MutableStateFlow<Set<String>>(emptySet())
    val pinMismatch: StateFlow<Set<String>> = mismatched.asStateFlow()

    private val pending = MutableStateFlow<List<TlsPinPrompt>>(emptyList())
    val prompts: StateFlow<List<TlsPinPrompt>> = pending.asStateFlow()

    fun reportPinMismatch(deviceId: String) {
        mismatched.value = mismatched.value + deviceId
    }

    fun reportHealthy(deviceId: String) {
        if (deviceId in mismatched.value) mismatched.value = mismatched.value - deviceId
    }

    /** One prompt per device; a newer offer replaces an older one. */
    fun requestConfirmation(prompt: TlsPinPrompt) {
        pending.value = pending.value.filterNot { it.deviceId == prompt.deviceId } + prompt
    }

    fun dismiss(deviceId: String) {
        pending.value = pending.value.filterNot { it.deviceId == deviceId }
    }
}
