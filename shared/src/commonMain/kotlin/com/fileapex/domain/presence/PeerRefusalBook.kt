package com.fileapex.domain.presence

import java.util.concurrent.ConcurrentHashMap

/**
 * Which roster peers refused this device's TLS certificate lately. One peer refusing can mean that peer lost its own
 * record; when two or more refuse and they outnumber the peers that still accept, the cluster has dropped this device
 * (it was removed, or its certificate changed) and the peers that still accept are only slow to catch up. No removal
 * answer can reach it, because the handshake is refused first.
 */
internal class PeerRefusalBook(
    private val windowMs: Long = WINDOW_MS,
    private val minPeers: Int = MIN_PEERS
) {
    private val refusedAt = ConcurrentHashMap<String, Long>()
    private val acceptedAt = ConcurrentHashMap<String, Long>()

    /** Records a refusal; true when it makes the roster stale. */
    fun refused(peerId: String, nowMs: Long): Boolean {
        refusedAt[peerId] = nowMs
        acceptedAt.remove(peerId)
        val refusing = refusedAt.count { (_, at) -> nowMs - at <= windowMs }
        val accepting = acceptedAt.count { (_, at) -> nowMs - at <= windowMs }
        println("PeerRefusals: $refusing peers refuse this device, $accepting accept it")
        return refusing >= minPeers && refusing > accepting
    }

    fun accepted(peerId: String, nowMs: Long) {
        acceptedAt[peerId] = nowMs
        refusedAt.remove(peerId)
    }

    fun refusedRecently(peerId: String, nowMs: Long): Boolean =
        refusedAt[peerId]?.let { nowMs - it <= windowMs } == true

    fun clear() {
        refusedAt.clear()
        acceptedAt.clear()
    }

    companion object {
        const val WINDOW_MS = 10L * 60L * 1000L
        const val MIN_PEERS = 2
    }
}

/** The process-wide book. The HTTP layer reports refusals by address; [resolve] turns an address into a roster peer. */
internal object PeerRefusals {
    val book = PeerRefusalBook()
    var routeLookup: (host: String, port: Int) -> String? = { host, port ->
        com.fileapex.security.tls.PeerTlsRoutes.lookup(host, port)?.deviceId
    }

    @Volatile
    private var byAddress: Map<String, String> = emptyMap()

    /** Roster addresses by device id, for peers whose TLS pin is not stored yet (a device that just joined). */
    fun updateRoster(deviceIdByAddress: Map<String, String>) {
        byAddress = deviceIdByAddress.mapKeys { it.key.trim() }
    }

    val defaultResolve: (host: String, port: Int) -> String? = { host, port ->
        routeLookup(host, port) ?: byAddress[host.trim()]
    }

    var resolve: (host: String, port: Int) -> String? = defaultResolve
    /** Refusals are only a hint: an old build or a changed key refuses too. Only a signed removal makes a device forget its cluster. */
    val defaultOnStale: () -> Unit = { println("PeerRefusals: several peers refuse this device (hint only, nothing is changed)") }
    var onStale: () -> Unit = defaultOnStale
    var now: () -> Long = com.fileapex.util.TimeUtils::now

    fun onRefusedHost(host: String, port: Int) {
        val peerId = resolve(host, port) ?: return
        println("PeerRefusals: ${com.fileapex.util.NetworkUtils.lastOctetLabel(host)} refuses this device")
        if (book.refused(peerId, now())) {
            book.clear()
            onStale()
        }
    }

    fun onAcceptedHost(host: String, port: Int) {
        resolve(host, port)?.let { book.accepted(it, now()) }
    }

    /** A peer that passes TLS but answers `401 revoked` has dropped this device just like one that refuses the handshake. */
    fun onAnswer(host: String, port: Int, statusCode: Int, body: String) {
        if (statusCode == 401 && body.contains("revoked", ignoreCase = true)) {
            println("PeerRefusals: ${com.fileapex.util.NetworkUtils.lastOctetLabel(host)} answered revoked")
            onRefusedHost(host, port)
        } else {
            onAcceptedHost(host, port)
        }
    }
}
