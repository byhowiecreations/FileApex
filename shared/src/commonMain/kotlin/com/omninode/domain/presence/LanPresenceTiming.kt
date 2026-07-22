package com.omninode.domain.presence

/**
 * Single source of truth for LAN presence broadcast and offline-grace timing.
 */
object LanPresenceTiming {
    /** Periodic LAN [SELF_METADATA] broadcast interval while on Wi-Fi/Ethernet. */
    const val SELF_METADATA_BROADCAST_MS = 300_000L

    /** Offline badge grace — one missed 5-minute heartbeat plus network jitter. */
    const val OFFLINE_GRACE_MS = 360_000L

    /** Local-only UI re-evaluation interval (no outbound network I/O). */
    const val ONLINE_SNAPSHOT_REFRESH_MS = 60_000L
}
