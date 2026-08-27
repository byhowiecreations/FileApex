package com.fileapex.data.device

object RosterIdentity {
    fun samePhysicalDevice(
        incomingId: String,
        incomingPublicKeyHash: String,
        otherId: String,
        otherPublicKeyHash: String,
        staleRosterId: String = ""
    ): Boolean {
        val incoming = incomingId.trim()
        val other = otherId.trim()
        if (incoming.isNotEmpty() && incoming == other) return true
        val stale = staleRosterId.trim()
        if (stale.isNotEmpty() && other == stale) return true
        val incomingHash = incomingPublicKeyHash.trim()
        val otherHash = otherPublicKeyHash.trim()
        return incomingHash.isNotEmpty() && incomingHash == otherHash
    }

    fun shouldDetachStolenEndpoint(
        sameEndpoint: Boolean,
        samePhysicalDevice: Boolean
    ): Boolean = sameEndpoint && !samePhysicalDevice
}
