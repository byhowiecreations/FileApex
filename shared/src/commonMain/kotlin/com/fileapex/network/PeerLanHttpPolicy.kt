package com.fileapex.network

import com.fileapex.platform.isActiveLanConnectivity
import com.fileapex.util.NetworkUtils

/**
 * Single source of truth for peer LAN HTTP routing.
 * Peer traffic is Wi‑Fi/Ethernet only — never cellular or OS default-route Ktor.
 */
object PeerLanHttpPolicy {
    /** Non-throwing check used by background probes (presence sweep, health ping). */
    fun canRoute(host: String): Boolean {
        if (!NetworkUtils.isPrivateLanPeerHost(host)) return false
        if (!isActiveLanConnectivity()) return false
        if (LanInterfaceBinding.lanBindCandidates().isEmpty()) return false
        return true
    }

    fun ensureRoute(host: String) {
        require(NetworkUtils.isPrivateLanPeerHost(host)) {
            "Peer host must be a private LAN address: $host"
        }
        if (LanInterfaceBinding.lanBindCandidates().isEmpty()) {
            error(deviceDetailsWifiRequiredMessage())
        }
        require(isActiveLanConnectivity()) {
            deviceDetailsWifiRequiredMessage()
        }
    }

    fun deviceDetailsWifiRequiredMessage(): String =
        PeerReachabilityMessages.localWifiRequired()

    fun unreachableMessage(host: String, port: Int): String =
        "Peer unreachable over Wi-Fi at $host:$port. " +
            "Confirm both devices are on the same local network."
}
