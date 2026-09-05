package com.fileapex.domain.presence

/**
 * Background FGS work is battery-first (5 min light sweeps). Foreground UI uses shorter
 * intervals. Transfers and fresh peer state skip redundant work.
 */
object LanPresenceTiming {
    /** Peer last_seen within this window shows "Ready". */
    const val PRESENCE_READY_THRESHOLD_MS = 20 * 60 * 1000L

    /** @deprecated Use [PRESENCE_READY_THRESHOLD_MS] — kept for internal reachability checks. */
    const val OFFLINE_GRACE_MS = PRESENCE_READY_THRESHOLD_MS

    /**
     * 10-minute Firestore cloud presence heartbeat ([com.fileapex.cloud.CloudPresenceHeartbeat]).
     * Remote peer visibility only; not the Android FGS recovery watchdog (20 min AlarmManager).
     */
    const val FIRESTORE_PRESENCE_HEARTBEAT_MS = 10 * 60 * 1000L

    const val DEVICE_CONNECT_HANDSHAKE_MS = 2_000L

    /** Local UI re-evaluation when grace windows expire (no network I/O). */
    const val ONLINE_SNAPSHOT_REFRESH_MS = 60_000L

    const val FOREGROUND_REFRESH_DEBOUNCE_MS = 30_000L

    const val ON_DEMAND_HEALTH_TIMEOUT_MS = 1_500L

    const val DEVICE_DETAILS_PING_TIMEOUT_MS = 800L

    /**
     * Retry after WiFi/Ethernet transition until [com.fileapex.platform.isActiveLanConnectivity]
     * reports a bindable LAN address (DHCP often lags NetworkCallback).
     */
    val NETWORK_TRANSITION_RETRY_DELAYS_MS = longArrayOf(0L, 2_000L, 5_000L, 10_000L, 20_000L)

    const val DEVICE_DETAILS_RECENT_REACHABILITY_MS = 120_000L

    const val PASSIVE_ENDPOINT_WAIT_MS = 1_500L

    /** mDNS-only follow-up when stored endpoint fails during a light sweep. */
    const val LIGHT_SWEEP_DISCOVERY_BUDGET_MS = 4_000L

    /** Full subnet sweep when stored endpoint is stale (foreground / transfer / network change). */
    const val STALE_PEER_LAN_DISCOVERY_BUDGET_MS = 12_000L

    /** @deprecated Use [LIGHT_SWEEP_DISCOVERY_BUDGET_MS] */
    @Deprecated("Use LIGHT_SWEEP_DISCOVERY_BUDGET_MS", ReplaceWith("LIGHT_SWEEP_DISCOVERY_BUDGET_MS"))
    const val LAN_DISCOVERY_BUDGET_MS = LIGHT_SWEEP_DISCOVERY_BUDGET_MS

    const val FOREGROUND_LAN_POLL_MS = 60_000L

    /** Share server running with UI in background. */
    const val BACKGROUND_LAN_POLL_MS = 5 * 60 * 1000L

    const val TRANSFER_DEFER_POLL_MS = 15_000L

    const val PEER_FRESH_SKIP_SWEEP_MS = 5 * 60 * 1000L

    const val FCM_WAKE_MIN_INTERVAL_MS = 10 * 60 * 1000L

    const val SELF_BROADCAST_MIN_INTERVAL_MS = 10 * 60 * 1000L

    @Deprecated("Use FOREGROUND_LAN_POLL_MS", ReplaceWith("FOREGROUND_LAN_POLL_MS"))
    const val ACTIVE_LAN_POLL_MS = FOREGROUND_LAN_POLL_MS

    @Deprecated("Use FOREGROUND_LAN_POLL_MS", ReplaceWith("FOREGROUND_LAN_POLL_MS"))
    const val DESKTOP_LAN_POLL_MS = FOREGROUND_LAN_POLL_MS

    const val TRANSFER_MDNS_SETTLE_MS = 750L

    /**
     * Skip full LAN discovery during transfer priming when the peer was health-checked recently
     * (share-sheet device list already probed fetchPeerNodeState).
     */
    const val TRANSFER_RECENT_REACHABILITY_MS = 60_000L

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
