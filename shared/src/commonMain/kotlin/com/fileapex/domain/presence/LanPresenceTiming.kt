package com.fileapex.domain.presence

/**
 * Background FGS work is battery-first (5 min light sweeps). Foreground UI uses shorter
 * intervals. Transfers and fresh peer state skip redundant work.
 */
object LanPresenceTiming {
    /** Peer last_seen within this window shows "Ready". */
    const val PRESENCE_READY_THRESHOLD_MS = 20 * 60 * 1000L

    /** @deprecated Use [PRESENCE_READY_THRESHOLD_MS], kept for internal reachability checks. */
    const val OFFLINE_GRACE_MS = PRESENCE_READY_THRESHOLD_MS

    /**
     * 10-minute Firestore cloud presence heartbeat ([com.fileapex.cloud.CloudPresenceHeartbeat]).
     * Remote peer visibility only; not the Android FGS recovery watchdog (20 min AlarmManager).
     */
    const val FIRESTORE_PRESENCE_HEARTBEAT_MS = 10 * 60 * 1000L

    const val DEVICE_CONNECT_HANDSHAKE_MS = 2_000L

    const val FOREGROUND_REFRESH_DEBOUNCE_MS = 30_000L

    const val ON_DEMAND_HEALTH_TIMEOUT_MS = 1_500L

    const val DEVICE_DETAILS_PING_TIMEOUT_MS = 800L

    /** Node-state (identity/metadata) refresh after a successful probe. */
    const val NODE_STATE_REFRESH_TTL_MS = 15 * 60 * 1000L

    /** Refresh of a peer that reports its membership version: it is fetched when the version changes, else only this rarely. */
    const val NODE_STATE_VERSIONED_SAFETY_MS = 6 * 60 * 60 * 1000L

    /** Retry floor for node-state fetch while metadata is still missing. */
    const val NODE_STATE_MISSING_RETRY_MS = 60_000L

    /** Minimum gap between UDP wake broadcasts triggered by presence sweeps. */
    const val SWEEP_WAKE_FLOOR_MS = 10 * 60 * 1000L

    /**
     * Retry after WiFi/Ethernet transition until [com.fileapex.platform.isActiveLanConnectivity]
     * reports a bindable LAN address (DHCP often lags NetworkCallback).
     */
    val NETWORK_TRANSITION_RETRY_DELAYS_MS = longArrayOf(0L, 2_000L, 5_000L, 10_000L, 20_000L)

    const val PASSIVE_ENDPOINT_WAIT_MS = 1_500L

    /** mDNS-only follow-up when stored endpoint fails during a light sweep. */
    const val LIGHT_SWEEP_DISCOVERY_BUDGET_MS = 4_000L

    /** Full subnet sweep when stored endpoint is stale (foreground / transfer / network change). */
    const val STALE_PEER_LAN_DISCOVERY_BUDGET_MS = 12_000L

    /** First wait after a subnet scan found nothing; doubles per failed scan up to the cap. */
    const val LAN_SCAN_BACKOFF_BASE_MS = 30 * 60 * 1000L
    const val LAN_SCAN_BACKOFF_MAX_MS = 4 * 60 * 60 * 1000L

    /** A peer that answered over Tailscale or Iroh is not scanned for on the LAN for this long. */
    const val REMOTE_ROUTE_SCAN_HOLD_MS = 15 * 60 * 1000L

    /** After a network change the hello waits this long for the LAN link to be usable before it is sent. */
    const val HELLO_LAN_WAIT_MS = 20_000L

    /** The hello is sent once more this long after the first. Longer than the receivers' per-sender gap, or it is dropped. */
    const val HELLO_RESEND_MS = 12_000L

    /** A pairing candidate tried this recently is not checked again by a sweep. */
    const val CANDIDATE_RETRY_MS = 2 * 60 * 1000L

    /** Foreground poll; resume, network change and pull-to-refresh still trigger immediate sweeps. */
    const val FOREGROUND_LAN_POLL_MS = 5 * 60 * 1000L

    /** Share server running with UI in background. */
    const val BACKGROUND_LAN_POLL_MS = 5 * 60 * 1000L

    /** On a foreground sweep, skip peer if verified reachable within 45s and metadata is complete. */
    const val FOREGROUND_PEER_FRESH_MS = 45_000L

    /** In 5m background poll, skip peer if verified reachable within 4m and metadata is complete. */
    const val BACKGROUND_PEER_FRESH_MS = 4 * 60 * 1000L

    const val TRANSFER_DEFER_POLL_MS = 15_000L

    const val PEER_FRESH_SKIP_SWEEP_MS = 5 * 60 * 1000L

    const val FCM_WAKE_MIN_INTERVAL_MS = 10 * 60 * 1000L

    const val SELF_BROADCAST_MIN_INTERVAL_MS = 10 * 60 * 1000L

    const val TRANSFER_MDNS_SETTLE_MS = 750L

    const val ON_DEMAND_PRIME_ATTEMPTS = 2

    const val ON_DEMAND_PRIME_RETRY_MS = 400L

    suspend fun awaitConnectHandshakeMinDelay(startedAtEpochMs: Long, skipMinDelay: Boolean) {
        if (skipMinDelay) return
        val remaining = (DEVICE_CONNECT_HANDSHAKE_MS - (com.fileapex.util.TimeUtils.now() - startedAtEpochMs)).coerceAtLeast(0L)
        if (remaining > 0L) {
            kotlinx.coroutines.delay(remaining)
        }
    }
}
