package com.fileapex.network

import com.fileapex.platform.isActiveLanConnectivity
import com.fileapex.util.NetworkUtils

/**
 * Single source of truth for peer LAN HTTP routing.
 * Peer traffic is Wi‑Fi/Ethernet only — never cellular or OS default-route Ktor.
 */
object PeerLanHttpPolicy {
    fun ensureRoute(host: String) {
        require(NetworkUtils.isPrivateLanPeerHost(host)) {
            "Peer host must be a private LAN address: $host"
        }
        require(isActiveLanConnectivity()) {
            "Wi-Fi or Ethernet is required for peer connections"
        }
        require(LanInterfaceBinding.lanBindCandidates().isNotEmpty()) {
            "No Wi-Fi or Ethernet interface is available for peer traffic"
        }
    }

    fun unreachableMessage(host: String, port: Int): String =
        "Peer unreachable over Wi-Fi at $host:$port"
}
