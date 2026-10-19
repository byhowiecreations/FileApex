package com.fileapex.domain.presence

/**
 * The one rule for offline peers: a route lookup probes the network, so it runs only for a peer that is online.
 * Features never call this directly; they use PeerPresenceMonitor.endpointIfOnline (automatic work) or
 * PeerPresenceMonitor.probeNow (an action the user started).
 */
object PeerGate {
    suspend fun <T : Any> ifOnline(isOnline: Boolean, resolve: suspend () -> T?): T? =
        if (isOnline) resolve() else null
}
