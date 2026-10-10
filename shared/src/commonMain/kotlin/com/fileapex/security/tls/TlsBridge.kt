package com.fileapex.security.tls

import java.util.concurrent.ConcurrentHashMap

/** A peer authenticated by its pinned client certificate on the TLS front. */
data class BridgedPeer(val deviceId: String, val pin: String, val remoteIp: String)

/**
 * The TLS front forwards each connection to the loopback bridge port from a unique local source port.
 * The server maps that source port back to the authenticated peer. Nothing is read from request
 * headers, and a local process that connects to the bridge port directly has no entry here.
 */
object TlsBridgeRegistry {
    private val peers = ConcurrentHashMap<Int, BridgedPeer>()

    fun register(sourcePort: Int, peer: BridgedPeer) {
        peers[sourcePort] = peer
    }

    fun unregister(sourcePort: Int) {
        peers.remove(sourcePort)
    }

    fun lookup(sourcePort: Int): BridgedPeer? = peers[sourcePort]

    fun clear() = peers.clear()
}

interface TlsPeerDirectory {
    /** Device id whose stored pin (or pending alternate pin) equals [pin]; null for strangers. */
    fun deviceIdForPin(pin: String): String?
}

interface TlsFront {
    /** Loopback port the HTTP server must also listen on. Decrypted traffic is forwarded there. */
    val bridgePort: Int

    /** Starts the TLS listener and returns the bound port, or throws. */
    fun start(): Int
    fun stop()
}

/** Platforms register a factory at startup; null result means no TLS (HTTP only). */
object TlsFrontProvider {
    @Volatile
    var factory: ((bindHost: String) -> TlsFront?)? = null
}
