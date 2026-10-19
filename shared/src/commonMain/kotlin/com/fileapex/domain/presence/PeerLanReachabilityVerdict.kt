package com.fileapex.domain.presence

/**
 * Route verdict shared by file transfer, file navigation and Device Details. [Direct] carries whatever route
 * [PeerPresenceMonitor.resolveOutboundEndpoint] chose: LAN, tailnet or the Iroh bridge on loopback.
 */
sealed interface PeerLanReachabilityVerdict {
    /** A route answered a quick health ping. */
    data class Direct(
        val host: String,
        val port: Int
    ) : PeerLanReachabilityVerdict

}
