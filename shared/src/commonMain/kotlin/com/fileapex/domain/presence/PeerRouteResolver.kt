package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.tailscale.isTailscaleIPv4
import com.fileapex.util.TimeUtils
import java.util.concurrent.ConcurrentHashMap

enum class RouteKind { Lan, Tailnet, Iroh }

enum class RouteUse { Presence, Request, Bulk, AddressExchange }

data class PeerRoute(val kind: RouteKind, val host: String, val port: Int)

interface PeerRouteProbes {
    fun networkAvailable(): Boolean

    suspend fun lan(peer: PairedDeviceEntity): PeerRoute?

    fun tailnetEnabled(): Boolean

    fun tailnetPeerOnline(peer: PairedDeviceEntity): Boolean

    suspend fun tailnet(peer: PairedDeviceEntity): PeerRoute?

    fun irohUsable(peer: PairedDeviceEntity): Boolean

    suspend fun iroh(peer: PairedDeviceEntity): PeerRoute?
}

class PeerRouteResolver(
    private val probes: PeerRouteProbes,
    private val now: () -> Long = TimeUtils::now,
    private val onRouteChange: (PairedDeviceEntity, PeerRoute?) -> Unit = { _, _ -> }
) {
    private val failedUntil = ConcurrentHashMap<String, Long>()
    private val lastKind = ConcurrentHashMap<String, String>()

    suspend fun routeFor(peer: PairedDeviceEntity, use: RouteUse): PeerRoute? =
        choose(peer, use).also { route ->
            if (use == RouteUse.AddressExchange) return@also
            val kind = route?.kind?.name ?: "none"
            if (lastKind.put(peer.deviceId, kind) != kind) onRouteChange(peer, route)
        }

    private suspend fun choose(peer: PairedDeviceEntity, use: RouteUse): PeerRoute? {
        if (!probes.networkAvailable()) return null
        if (!skipped(peer, RouteKind.Lan)) {
            probes.lan(peer)?.let { return it }
        }
        if (!skipped(peer, RouteKind.Tailnet) && probes.tailnetEnabled() && probes.tailnetPeerOnline(peer)) {
            probes.tailnet(peer)?.let { return it }
        }
        if (use != RouteUse.AddressExchange && !skipped(peer, RouteKind.Iroh) && probes.irohUsable(peer)) {
            probes.iroh(peer)?.let { return it }
        }
        return null
    }

    fun routeFailed(peer: PairedDeviceEntity, kind: RouteKind) {
        failedUntil[key(peer, kind)] = now() + FAILED_ROUTE_TTL_MS
    }

    private fun skipped(peer: PairedDeviceEntity, kind: RouteKind): Boolean {
        val until = failedUntil[key(peer, kind)] ?: return false
        if (now() < until) return true
        failedUntil.remove(key(peer, kind), until)
        return false
    }

    private fun key(peer: PairedDeviceEntity, kind: RouteKind) = "${peer.deviceId}|$kind"

    companion object {
        const val FAILED_ROUTE_TTL_MS = 60_000L
    }
}

fun routeKindForHost(host: String): RouteKind = when {
    isTailscaleIPv4(host.trim()) -> RouteKind.Tailnet
    host.trim() == "127.0.0.1" -> RouteKind.Iroh
    else -> RouteKind.Lan
}
