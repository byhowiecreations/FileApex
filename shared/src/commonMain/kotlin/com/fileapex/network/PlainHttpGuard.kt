package com.fileapex.network

import com.fileapex.security.tls.PeerTlsRoutes
import com.fileapex.security.tls.TlsTransportPolicy
import com.fileapex.util.NetworkUtils

/**
 * A request for a route that only a pinned peer may call never goes out as plain HTTP: without a TLS route for the
 * address it is not sent, and the caller sees the peer as unreachable until the route exists.
 */
internal object PlainHttpGuard {
    fun refuses(host: String, port: Int, path: String): Boolean =
        TlsTransportPolicy.requiresTls(path) &&
            RouteAuthPolicy.accessFor(path) == RouteAccess.Peer &&
            PeerTlsRoutes.lookup(host, port) == null

    /** True, with a log line, when the request must not be sent. */
    fun blocks(host: String, port: Int, path: String): Boolean {
        if (!refuses(host, port, path)) return false
        println("PeerBoundHttp: not sending ${path.substringBefore('?')} to ${NetworkUtils.lastOctetLabel(host)}:$port as plain HTTP, no TLS route yet")
        return true
    }
}
