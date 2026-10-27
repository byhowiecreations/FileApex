package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.di.FileApexServices
import com.fileapex.iroh.IrohReachabilityCache
import com.fileapex.iroh.IrohRuntime
import com.fileapex.iroh.IrohSweepPolicy
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.network.PeerLanHttpPolicy
import com.fileapex.platform.hasUsableNetwork
import com.fileapex.platform.isActiveLanConnectivity
import com.fileapex.tailscale.TailscaleNodeRuntime
import com.fileapex.tailscale.tailnetPeerIsOnline
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import java.util.concurrent.ConcurrentHashMap

internal interface RouteHost {
    suspend fun liveDevice(deviceId: String): PairedDeviceEntity?
    suspend fun markReachableOverLan(deviceId: String)
    suspend fun markReachableRemote(deviceId: String, via: RouteKind)
    fun noteRoute(deviceId: String, kind: RouteKind)
    suspend fun touchLastSeenEpoch(deviceId: String)
    fun msSinceReached(deviceId: String): Long?
    fun recordRemoteReach(deviceId: String, via: RouteKind)
    suspend fun fetchNodeState(host: String, port: Int, timeoutMs: Long): PeerNodeState
    suspend fun pingHealth(host: String, port: Int, timeoutMs: Long): Boolean
}

internal class PeerOutboundRoutes(private val host: RouteHost) {
    /** deviceId to (verified on LAN, epoch ms the answer expires). */
    private val lanDirectVerdicts = ConcurrentHashMap<String, Pair<Boolean, Long>>()
    private val tailnetAnswers = IrohReachabilityCache(LAN_DIRECT_HIT_TTL_MS, LAN_DIRECT_MISS_TTL_MS, TimeUtils::now)

    /**
     * The one route order (LAN, Tailscale, Iroh) for calls to a peer. The tailnet check is its own ping with
     * its own result, because a shared "reached recently" flag also counts LAN and Iroh traffic.
     */
    val routes: PeerRouteResolver by lazy {
        PeerRouteResolver(probes = object : PeerRouteProbes {
            override fun networkAvailable() = hasUsableNetwork()

            override suspend fun lan(peer: PairedDeviceEntity): PeerRoute? =
                verifiedLanEndpoint(peer)?.let { PeerRoute(RouteKind.Lan, it.host, it.port) }

            override fun tailnetEnabled() = isTailscaleEnabled()

            override fun tailnetPeerOnline(peer: PairedDeviceEntity) =
                tailnetPeerIsOnline(TailscaleNodeRuntime.observedTailnetPeers(), peer.deviceId, peer.deviceName)

            override suspend fun tailnet(peer: PairedDeviceEntity): PeerRoute? = tailnetRoute(peer)

            override fun irohUsable(peer: PairedDeviceEntity) = IrohRuntime.canReach(peer.deviceId)

            override suspend fun iroh(peer: PairedDeviceEntity): PeerRoute? =
                irohVerdict(peer)?.let { PeerRoute(RouteKind.Iroh, it.host, it.port) }
        }, onRouteChange = { peer, route ->
            if (IrohRuntime.isEnabled()) {
                val via = when (route?.kind) {
                    RouteKind.Lan -> "LAN"
                    RouteKind.Tailnet -> "Tailscale"
                    RouteKind.Iroh -> "Iroh"
                    null -> "none"
                }
                com.fileapex.iroh.IrohLog.add("route to ${com.fileapex.iroh.IrohLog.short(peer.deviceId)}: $via")
            }
        })
    }

    suspend fun resolveOutboundEndpoint(
        peer: PairedDeviceEntity,
        use: RouteUse = RouteUse.Request
    ): PeerLanReachabilityVerdict.Direct? {
        val live = host.liveDevice(peer.deviceId) ?: peer
        routes.routeFor(live, use)?.let { return PeerLanReachabilityVerdict.Direct(it.host, it.port) }
        if (use == RouteUse.AddressExchange || !isActiveLanConnectivity()) return null
        val ip = live.lastKnownIp.trim()
        val port = live.port
        if (ip.isNotEmpty() && NetworkUtils.isPrivateLanPeerHost(ip) && port > 0) {
            return PeerLanReachabilityVerdict.Direct(ip, port)
        }
        return null
    }

    /** The Iroh bridge endpoint when the peer answered through Iroh. Marks it reachable for presence. */
    private suspend fun irohVerdict(peer: PairedDeviceEntity): PeerLanReachabilityVerdict.Direct? {
        if (!IrohRuntime.canReach(peer.deviceId)) return null
        val endpoint = IrohRuntime.reachableEndpoint(peer) ?: return null
        host.markReachableRemote(peer.deviceId, RouteKind.Iroh)
        host.touchLastSeenEpoch(peer.deviceId)
        return PeerLanReachabilityVerdict.Direct(endpoint.host, endpoint.port)
    }

    /** The tailnet route when Tailscale is on, reports the peer online and the peer answers a short ping. */
    private suspend fun tailnetRoute(peer: PairedDeviceEntity): PeerRoute? {
        if (!isTailscaleEnabled()) return null
        if (!tailnetPeerIsOnline(TailscaleNodeRuntime.observedTailnetPeers(), peer.deviceId, peer.deviceName)) return null
        val endpoint = resolvePeerEndpoint(peer, tailnetUp = true)?.takeIf { it.tailnet } ?: return null
        val answers = tailnetAnswers.check(peer.deviceId) {
            host.pingHealth(endpoint.host, endpoint.port, LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS)
        }
        if (answers) host.recordRemoteReach(peer.deviceId, RouteKind.Tailnet)
        return if (answers) PeerRoute(RouteKind.Tailnet, endpoint.host, endpoint.port) else null
    }

    /** A peer the LAN pass did not reach is tried through Iroh; this is that one peer's own attempt. */
    suspend fun irohProbe(peer: PairedDeviceEntity, freshMs: Long) {
        if (!IrohRuntime.isUsable()) return
        // A resting endpoint is started by the reconcile loop or an explicit request, never by a presence check.
        if (!IrohRuntime.isUp() && FileApexServices.settings.irohRestWhenIdle.value) return
        val worth = IrohSweepPolicy.shouldProbe(
            canReach = IrohRuntime.canReach(peer.deviceId),
            msSinceReached = host.msSinceReached(peer.deviceId),
            freshMs = freshMs,
            onLan = isActiveLanConnectivity()
        )
        if (worth) cancellableCatching { if (tailnetRoute(peer) == null) irohVerdict(peer) }
    }

    /**
     * The tsnet userspace path is CPU bound and far slower than the local network, so a peer on
     * the same LAN is used directly even while Tailscale is on. The peer has to answer on its
     * stored LAN address with its own device id; a private address alone proves nothing because
     * another network can reuse it. Both answers are cached briefly so a queue of sends pays for one probe.
     * A peer on the local subnet answers in milliseconds; a stale address costs one short timeout, then
     * the miss is remembered and the next route (Tailscale, then Iroh) is tried straight away.
     */
    suspend fun verifiedLanEndpoint(peer: PairedDeviceEntity): PeerLanReachabilityVerdict.Direct? {
        val ip = peer.lastKnownIp.trim()
        val port = peer.port
        if (port <= 0 || ip.isEmpty() || !NetworkUtils.isPrivateLanPeerHost(ip)) return null
        if (!isActiveLanConnectivity() || !PeerLanHttpPolicy.canRoute(ip)) return null
        // A peer outside every local /24 cannot be answering on the LAN, so skip the probe entirely.
        if (NetworkUtils.lanBindCandidates().none { NetworkUtils.sameIpv4Slash24(it, ip) }) return null
        val now = TimeUtils.now()
        val cached = lanDirectVerdicts[peer.deviceId]
        if (cached != null && cached.second > now) {
            if (!cached.first) return null
            host.noteRoute(peer.deviceId, RouteKind.Lan)
            return PeerLanReachabilityVerdict.Direct(ip, port)
        }
        val state = cancellableCatching {
            host.fetchNodeState(ip, port, LanPresenceTiming.DEVICE_DETAILS_PING_TIMEOUT_MS)
        }.getOrNull()
        val verified = state != null && sameDevice(peer, state)
        val ttl = if (verified) LAN_DIRECT_HIT_TTL_MS else LAN_DIRECT_MISS_TTL_MS
        lanDirectVerdicts[peer.deviceId] = verified to (TimeUtils.now() + ttl)
        if (!verified) return null
        host.markReachableOverLan(peer.deviceId)
        return PeerLanReachabilityVerdict.Direct(ip, port)
    }

    companion object {
        const val LAN_DIRECT_HIT_TTL_MS = 30_000L
        const val LAN_DIRECT_MISS_TTL_MS = 15_000L

        fun sameDevice(peer: PairedDeviceEntity, state: PeerNodeState): Boolean {
            if (state.deviceId.trim() == peer.deviceId) return true
            val hash = peer.publicKeyHash.trim()
            return hash.isNotEmpty() && hash == state.publicKeyHash.trim()
        }
    }
}
