package com.fileapex.data.device

/**
 * What may be stored as a paired peer's address. The Iroh bridge and the tailnet splice hand out loopback
 * endpoints for the duration of a call; saved as the peer's address, one pointed a pinned TLS route at the
 * local device itself.
 */
object PeerEndpointRules {
    fun isLoopbackOrUnspecified(host: String): Boolean {
        val value = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        return value == "localhost" || value == "::1" || value == "0.0.0.0" || value.startsWith("127.")
    }
}
