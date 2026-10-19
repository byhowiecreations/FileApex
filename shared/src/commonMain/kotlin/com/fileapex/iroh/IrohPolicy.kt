package com.fileapex.iroh

import com.fileapex.domain.peer.PeerNodeProtocols
import kotlinx.serialization.json.Json

object IrohWakePolicy {
    const val IDLE_GRACE_MS = 3 * 60_000L
    /** How long after a peer last used us over Iroh the endpoint stays up for it. */
    const val SERVED_KEEP_MS = 3 * 60_000L

    /** On a LAN an offline peer holds the endpoint up only if it was heard from this recently. */
    const val OFFLINE_KEEP_MS = 24 * 60 * 60_000L

    fun heardRecently(lastHeardAtMs: Long, nowMs: Long): Boolean =
        lastHeardAtMs > 0L && nowMs - lastHeardAtMs < OFFLINE_KEEP_MS

    /**
     * [noLocalRoute]: this device is on neither a LAN nor a tailnet, so only Iroh can reach anyone and the
     * "online" flag, which lags behind, is not to be believed. [exchangePending]: an online peer has not been
     * sent our address since this app started, so the endpoint opens once for that.
     */
    fun needsEndpoint(
        hasAddress: Boolean,
        online: Boolean,
        servedAtMs: Long?,
        nowMs: Long,
        noLocalRoute: Boolean = false,
        exchangePending: Boolean = false,
        recentlyHeard: Boolean = true
    ): Boolean = reason(hasAddress, online, servedAtMs, nowMs, noLocalRoute, exchangePending, recentlyHeard) != null

    /** Why this peer needs the endpoint, or null when it does not. Shown in the Iroh log. */
    fun reason(
        hasAddress: Boolean,
        online: Boolean,
        servedAtMs: Long?,
        nowMs: Long,
        noLocalRoute: Boolean = false,
        exchangePending: Boolean = false,
        recentlyHeard: Boolean = true
    ): String? = when {
        servedAtMs != null && nowMs - servedAtMs < SERVED_KEEP_MS -> "served a peer over Iroh"
        hasAddress && noLocalRoute -> "no LAN or tailnet"
        online && exchangePending -> "our address not sent yet"
        !hasAddress -> if (online) "online, no address yet" else null
        !online && (noLocalRoute || recentlyHeard) -> "offline, heard within a day"
        else -> null
    }

    fun shouldSleep(notNeededSinceMs: Long, nowMs: Long): Boolean =
        notNeededSinceMs != 0L && nowMs - notNeededSinceMs >= IDLE_GRACE_MS
}

/**
 * What a device tells the cluster about Iroh through [com.fileapex.domain.peer.PeerNodeState.supportedProtocols]:
 * [ON] when Iroh is built in and switched on, [OFF] when it is not. A list with neither marker comes from a build
 * that predates them, so the version rule still decides.
 */
object IrohCapability {
    const val ON = "fileapex.v1.iroh"
    const val OFF = "fileapex.v1.iroh.off"

    fun advertised(protocolsJson: String): Boolean? {
        val raw = protocolsJson.trim()
        if (raw.isEmpty() || raw == "[]") return null
        val protocols = runCatching { Json.decodeFromString<List<String>>(raw) }.getOrNull() ?: return null
        return when {
            ON in protocols -> true
            OFF in protocols -> false
            else -> null
        }
    }

    fun ownProtocols(builtIn: Boolean, enabled: Boolean): List<String> =
        PeerNodeProtocols.DEFAULT + if (builtIn && enabled) ON else OFF
}

/**
 * Which peers the presence sweep tries over Iroh. Without a LAN, any peer not freshly reached. On a LAN, only a peer
 * not reached there for [LAN_SKIP_MS]: a probe makes the other side count "served a peer over Iroh", and two phones
 * probing each other would keep both endpoints awake for good.
 */
object IrohSweepPolicy {
    /** Longer than two sweep polls, so one missed LAN check does not send a probe over Iroh. */
    const val LAN_SKIP_MS = 12 * 60_000L

    fun shouldProbe(canReach: Boolean, msSinceReached: Long?, freshMs: Long, onLan: Boolean): Boolean {
        if (!canReach) return false
        val limit = if (onLan) LAN_SKIP_MS else freshMs
        return msSinceReached == null || msSinceReached >= limit
    }
}

object IrohExchangePolicy {
    const val OK = "ok"
    const val PEER_WITHOUT_IROH = "peer_has_iroh_off_or_old_build"
    const val RETRY_MS = 5 * 60_000L
    const val REFRESH_MS = 6 * 60 * 60_000L
    const val PEER_WITHOUT_IROH_RETRY_MS = 5 * 60_000L

    /** A "peer has no Iroh" answer is only believed this long; contact from the peer clears it at once. */
    const val NEGATIVE_TTL_MS = PEER_WITHOUT_IROH_RETRY_MS

    /** An address exchange only travels over a LAN or a tailnet, never Iroh, so without either there is nothing to try. */
    fun mayExchange(hasLan: Boolean, hasTailnet: Boolean): Boolean = hasLan || hasTailnet

    fun due(lastOutcome: String?, ageMs: Long?, ownAddressChanged: Boolean): Boolean {
        if (ageMs == null || lastOutcome == null) return true
        return when (lastOutcome) {
            OK -> ageMs >= REFRESH_MS || (ownAddressChanged && ageMs >= RETRY_MS)
            PEER_WITHOUT_IROH -> ageMs >= PEER_WITHOUT_IROH_RETRY_MS
            else -> ageMs >= RETRY_MS
        }
    }
}

object IrohConnectionPolicy {
    fun shouldReplace(storedClosed: Boolean, storedHasSelectedPath: Boolean): Boolean =
        storedClosed || !storedHasSelectedPath
}

object IrohStallPolicy {
    const val STALL_MS = 120_000L

    fun isStalled(lastActivityMs: Long, nowMs: Long): Boolean = nowMs - lastActivityMs >= STALL_MS
}

object IrohPeerStatus {
    /** The peer answered "no Iroh" recently and has not been heard from since. */
    fun negativeActive(outcome: String?, outcomeAtMs: Long, contactAtMs: Long, nowMs: Long): Boolean =
        outcome == IrohExchangePolicy.PEER_WITHOUT_IROH &&
            contactAtMs <= outcomeAtMs &&
            nowMs - outcomeAtMs < IrohExchangePolicy.NEGATIVE_TTL_MS
}
