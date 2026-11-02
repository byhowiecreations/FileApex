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
    private val held = java.util.concurrent.ConcurrentHashMap<String, PeerTlsRoute>()

    /** Mac: pushes the table to the native client, which dials peers itself. */
    @Volatile
    var onChange: ((List<PeerTlsEndpoint>) -> Unit)? = null

    /** Gets one line whenever the table the clients dial from changes (last octets only), so a peer missing from it shows in the log. */
    @Volatile
    var journal: ((String) -> Unit)? = null
    private var lastJournaled: String? = null

    private fun publish() {
        val table = snapshot()
        journal?.let { write ->
            val peers = table.map { "${it.host.substring(it.host.lastIndexOf('.').coerceAtLeast(0))}:${it.httpPort}>${it.route.tlsPort}" }.sorted()
            val line = "tls routes: ${peers.size} peers" + peers.joinToString("") { " $it" }
            val changed = synchronized(this) { (line != lastJournaled).also { lastJournaled = line } }
            if (changed) write(line)
        }
        onChange?.invoke(table)
    }

    fun snapshot(): List<PeerTlsEndpoint> = (held.toMap() + byEndpoint.get()).map { (key, route) ->
        PeerTlsEndpoint(key.substringBeforeLast(':'), key.substringAfterLast(':').toInt(), route)
    }

    /** Every address a pinned peer was routed at, kept while the peer stays pinned: a peer with two addresses is reached at either. */
    private val addressesSeen = HashMap<String, MutableSet<String>>()

    fun update(devices: List<PairedDeviceEntity>) {
        val map = HashMap<String, PeerTlsRoute>()
        val current = HashMap<String, PeerTlsRoute>()
        for (device in devices) {
            if (device.isRemoved || device.tlsPin.isEmpty() || device.port <= 0) continue
            val pins = setOfNotNull(device.tlsPin, device.tlsPinAlt.ifEmpty { null })
            val ip = device.lastKnownIp.trim()
            if (ip.isNotEmpty() && device.tlsPort in 1..65535) {
                current[key(ip, device.port)] = PeerTlsRoute(device.deviceId, pins, device.tlsPort)
            }
            // Over the tailnet the peer's TLS front answers on its share port, so a tailnet caller is pinned like a LAN one.
            val tailnetIp = device.tailnetIpv4.trim()
            if (tailnetIp.isNotEmpty()) map[key(tailnetIp, device.port)] = PeerTlsRoute(device.deviceId, pins, device.port)
        }
        synchronized(addressesSeen) {
            val latest = current.values.associateBy { it.deviceId }
            addressesSeen.keys.retainAll(latest.keys)
            for ((key, route) in current) addressesSeen.getOrPut(route.deviceId) { linkedSetOf() }.add(key)
            for ((id, keys) in addressesSeen) {
                val route = latest.getValue(id)
                for (key in keys) map[key] = route
            }
            map.putAll(current)
        }
        byEndpoint.set(map)
        publish()
    }

    /**
     * Keeps a pinned route to a device the roster no longer lists, so the notice that removes it can still go over TLS:
     * the removed device refuses plain HTTP from a peer it has pinned. Close the result once the notice was sent.
     */
    fun holdRoute(deviceId: String, pins: Set<String>, host: String, httpPort: Int, tlsPort: Int): AutoCloseable {
        val key = key(host, httpPort)
        val route = PeerTlsRoute(deviceId, pins, tlsPort)
        held[key] = route
        publish()
        return AutoCloseable {
            held.remove(key, route)
            publish()
        }
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
        else -> key(host, httpPort).let { byEndpoint.get()[it] ?: held[it] ?: tailnetCandidates[it] }
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
