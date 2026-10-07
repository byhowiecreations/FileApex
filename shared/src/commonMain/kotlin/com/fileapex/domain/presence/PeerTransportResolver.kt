package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.di.FileApexServices
import com.fileapex.tailscale.TailscaleNodeRuntime
import com.fileapex.tailscale.TailscalePhase
import com.fileapex.tailscale.isTailscaleIPv4
import com.fileapex.util.NetworkUtils

data class ResolvedPeerEndpoint(
    val host: String,
    val port: Int,
    val tailnet: Boolean
)

/**
 * True only when the user turned Tailscale on and the node is Up.
 * A disabled switch returns before the node is consulted, so LAN sockets stay on their own path.
 * Direct versus relay is chosen inside Server.Dial, not by comparing subnets here.
 */
fun isTailscaleEnabled(): Boolean {
    if (!FileApexServices.settings.tailscaleEnabled.value) return false
    return TailscaleNodeRuntime.state.value.phase == TailscalePhase.Up
}

fun tailnetNodeUp(): Boolean = isTailscaleEnabled()

/**
 * When [tailnetUp] is false, only the stored private LAN address is returned.
 * When it is true and this peer has a verified tailnet address, that address is dialed
 * through tsnet. Server.Dial picks a direct path or a relay. A missing tailnet address
 * still returns the LAN address so the peer is not dropped.
 * Callers that move file data go through PeerPresenceMonitor.resolveOutboundEndpoint, which
 * prefers a verified LAN address because the userspace tailnet path is much slower.
 */
fun resolvePeerEndpoint(peer: PairedDeviceEntity, tailnetUp: Boolean): ResolvedPeerEndpoint? {
    val port = peer.port
    if (port <= 0) return null
    val tailnetIp = peer.tailnetIpv4.trim()
    if (tailnetUp && isTailscaleIPv4(tailnetIp)) {
        return ResolvedPeerEndpoint(host = tailnetIp, port = port, tailnet = true)
    }
    val lan = peer.lastKnownIp.trim()
    if (lan.isEmpty() || lan == "127.0.0.1" || lan == "0.0.0.0") return null
    if (!NetworkUtils.isPrivateLanPeerHost(lan)) return null
    return ResolvedPeerEndpoint(host = lan, port = port, tailnet = false)
}
