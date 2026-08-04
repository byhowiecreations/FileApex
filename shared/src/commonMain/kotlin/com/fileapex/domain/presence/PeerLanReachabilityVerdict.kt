package com.fileapex.domain.presence

/**
 * Result of [PeerPresenceMonitor.quickAssessLanReachability] — shared by file transfer,
 * file navigation, and Device Details (LAN path vs cloud relay).
 */
sealed interface PeerLanReachabilityVerdict {
    /** Both sides on local Wi‑Fi/Ethernet and a quick health ping succeeded. */
    data class Direct(
        val host: String,
        val port: Int
    ) : PeerLanReachabilityVerdict

    /** Peer is visible (cloud presence) but not reachable on LAN — typically on cellular. */
    data object PeerOffLocalWifi : PeerLanReachabilityVerdict

    /** This device is not on Wi‑Fi/Ethernet. */
    data object LocalOffLocalWifi : PeerLanReachabilityVerdict

    /** Peer is not recently online — treat as unreachable (no long discovery sweep). */
    data object PeerOffline : PeerLanReachabilityVerdict

    val isDirect: Boolean get() = this is Direct
}
