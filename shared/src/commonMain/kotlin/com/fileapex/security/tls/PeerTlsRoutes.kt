package com.fileapex.security.tls

import com.fileapex.data.db.PairedDeviceEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Where to reach a pinned peer over TLS. A route means plain HTTP to that peer is never used. [preamble] is written
 * on the raw socket before the handshake: the session token the local tailnet pipe wants first.
 */
data class PeerTlsRoute(val deviceId: String, val pins: Set<String>, val tlsPort: Int, val preamble: String = "")

data class PeerTlsEndpoint(val host: String, val httpPort: Int, val route: PeerTlsRoute)

object PeerTlsRoutes {
    private val byEndpoint = AtomicReference<Map<String, PeerTlsRoute>>(emptyMap())
    private val tailnetPipes = java.util.concurrent.ConcurrentHashMap<Int, PeerTlsRoute>()
    private val tailnetCandidates = java.util.concurrent.ConcurrentHashMap<String, PeerTlsRoute>()

    /** Mac: pushes the table to the native client, which dials peers itself. */
    @Volatile
    var onChange: ((List<PeerTlsEndpoint>) -> Unit)? = null

    fun snapshot(): List<PeerTlsEndpoint> = byEndpoint.get().map { (key, route) ->
        PeerTlsEndpoint(key.substringBeforeLast(':'), key.substringAfterLast(':').toInt(), route)
    }

    fun update(devices: List<PairedDeviceEntity>) {
        val map = HashMap<String, PeerTlsRoute>()
        for (device in devices) {
            if (device.isRemoved || device.tlsPin.isEmpty() || device.port <= 0) continue
            val pins = setOfNotNull(device.tlsPin, device.tlsPinAlt.ifEmpty { null })
            val ip = device.lastKnownIp.trim()
            if (ip.isNotEmpty() && device.tlsPort in 1..65535) {
                map[key(ip, device.port)] = PeerTlsRoute(device.deviceId, pins, device.tlsPort)
            }
            // Over the tailnet the peer's TLS front answers on its share port, so a tailnet caller is pinned like a LAN one.
            val tailnetIp = device.tailnetIpv4.trim()
            if (tailnetIp.isNotEmpty()) map[key(tailnetIp, device.port)] = PeerTlsRoute(device.deviceId, pins, device.port)
        }
        byEndpoint.set(map)
        onChange?.invoke(snapshot())
    }

    /**
     * Lets [device] be tried at a tailnet address it is not recorded at yet: the pinned handshake is what proves the node
     * there holds the device's key. Null when the device has no pin to prove it with. Close the result when done.
     */
    fun tryTailnetAddress(device: PairedDeviceEntity, ipv4: String): AutoCloseable? {
        if (device.isRemoved || device.tlsPin.isEmpty() || device.port <= 0 || ipv4.isBlank()) return null
        val key = key(ipv4, device.port)
        tailnetCandidates[key] = PeerTlsRoute(device.deviceId, setOfNotNull(device.tlsPin, device.tlsPinAlt.ifEmpty { null }), device.port)
        return AutoCloseable { tailnetCandidates.remove(key) }
    }

    /** The local pipe on [pipePort] reaches [route]'s peer through the tailnet; TLS runs over it after [token] is written. */
    fun viaTailnetPipe(pipePort: Int, route: PeerTlsRoute, token: String) {
        tailnetPipes[pipePort] = route.copy(tlsPort = pipePort, preamble = token)
    }

    /** An Iroh bridge port is a local pipe to the peer, never a TLS endpoint, whatever a stored row says. */
    fun lookup(host: String, httpPort: Int): PeerTlsRoute? = when {
        com.fileapex.iroh.irohLoopbackOrNull(host, httpPort) != null -> null
        host.trim() == "127.0.0.1" && tailnetPipes.containsKey(httpPort) -> tailnetPipes[httpPort]
        else -> key(host, httpPort).let { byEndpoint.get()[it] ?: tailnetCandidates[it] }
    }

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
 * The confirmations the user owes after a TLS handshake failed a pin check.
 * A prompt only ever records a pin when the user accepts it.
 */
object PeerTlsStatus {
    private val pending = MutableStateFlow<List<TlsPinPrompt>>(emptyList())
    val prompts: StateFlow<List<TlsPinPrompt>> = pending.asStateFlow()

    /** Set by [TlsPinAnnouncer]: tries to settle a changed key without the user, then calls back with whether it did. */
    @Volatile
    var resolver: ((TlsPinPrompt, (Boolean) -> Unit) -> Unit)? = null

    /** Set by [TlsPinAnnouncer]: runs after the user trusted a changed key, to tell the other cluster devices. */
    @Volatile
    var onUserConfirmed: (suspend (TlsPinPrompt) -> Unit)? = null

    private val resolving = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * A paired peer showed a key other than the stored one. It is settled silently when a vouch, a rotation proof or the
     * peer's pairing key allows; the user is asked only when none of them does.
     */
    fun keyChangeObserved(deviceId: String, observedPin: String, port: Int) {
        val prompt = TlsPinPrompt(deviceId, TlsPromptKind.KEY_CHANGED, observedPin, port)
        val settle = resolver
        if (settle == null) {
            requestConfirmation(prompt)
            return
        }
        if (!resolving.add("$deviceId|$observedPin")) return
        settle(prompt) { settled ->
            resolving.remove("$deviceId|$observedPin")
            if (!settled) requestConfirmation(prompt)
        }
    }

    /** One prompt per device; a newer offer replaces an older one. */
    fun requestConfirmation(prompt: TlsPinPrompt) {
        pending.value = pending.value.filterNot { it.deviceId == prompt.deviceId } + prompt
    }

    fun dismiss(deviceId: String) {
        pending.value = pending.value.filterNot { it.deviceId == deviceId }
    }
}
