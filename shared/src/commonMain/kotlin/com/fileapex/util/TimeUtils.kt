package com.fileapex.util

import com.fileapex.platform.currentTimeMillis

/**
 * Single source of truth for epoch-based wall-clock operations.
 * Prefer [now] everywhere instead of [System.currentTimeMillis] / platform [currentTimeMillis].
 */
object TimeUtils {
    /** Current UTC epoch milliseconds. */
    fun now(): Long = currentTimeMillis()

    /** Non-negative elapsed milliseconds since [epochMs] (0 if [epochMs] is in the future). */
    fun millisSince(epochMs: Long): Long = (now() - epochMs).coerceAtLeast(0L)

    /** True when [epochMs] is positive and within [windowMs] of [now]. */
    fun isWithinWindow(epochMs: Long, windowMs: Long): Boolean =
        epochMs > 0L && millisSince(epochMs) <= windowMs

    /**
     * **20-minute** AlarmManager interval for Android FGS recovery ([ServiceWatchdogScheduler]).
     * Not used for cloud presence — that timer is [com.fileapex.domain.presence.LanPresenceTiming.FIRESTORE_PRESENCE_HEARTBEAT_MS].
     */
    const val SERVICE_WATCHDOG_ALARM_INTERVAL_MS: Long = 20 * 60 * 1000L

    /** Delay before an immediate watchdog retry after a blocked sticky restart. */
    const val SERVICE_WATCHDOG_IMMEDIATE_ALARM_DELAY_MS: Long = 30_000L

    /**
     * Max age of an FGS liveness heartbeat before recovery treats the share server as dead.
     * Slightly longer than [SERVICE_WATCHDOG_ALARM_INTERVAL_MS] so a healthy FGS is not restarted
     * on every alarm tick; heartbeats refresh on [onStartCommand] / re-assert paths only.
     */
    const val SHARE_SERVER_HEARTBEAT_STALE_MS: Long = 25 * 60 * 1000L

    /** Epoch millis when the next periodic watchdog alarm should fire. */
    fun nextAlarmEpochMs(intervalMs: Long = SERVICE_WATCHDOG_ALARM_INTERVAL_MS): Long =
        now() + intervalMs.coerceAtLeast(1L)

    /** Epoch millis for a near-term watchdog retry (sticky restart / blocked background FGS). */
    fun immediateWatchdogAlarmEpochMs(
        delayMs: Long = SERVICE_WATCHDOG_IMMEDIATE_ALARM_DELAY_MS
    ): Long = now() + delayMs.coerceAtLeast(1L)

    /**
     * Formats a UTC epoch millis instant into a local offset datetime using [zoneId].
     * Default zone follows RULES.md (America/New_York) for DST-safe localization.
     */
    fun formatUtcToLocal(
        epochMs: Long,
        zoneId: String = DEFAULT_ZONE_ID
    ): String = formatEpochMsToLocal(epochMs, zoneId)

    /** User-facing label for offline peer last-seen timestamps (pre-localized). */
    fun formatLastSeenLabel(epochMs: Long, zoneId: String = DEFAULT_ZONE_ID): String? {
        if (epochMs <= 0L) return null
        return "Last seen ${formatUtcToLocal(epochMs, zoneId)}"
    }

    const val DEFAULT_ZONE_ID: String = "America/New_York"
}

/**
 * Audit trail for timestamp mutations (RULES.md Audit Trail Requirement).
 * Logs UTC epoch plus localized wall time to catch double-conversion bugs.
 */
object TimestampDiagnostics {
    fun logMutation(
        field: String,
        epochMsUtc: Long,
        zoneId: String = TimeUtils.DEFAULT_ZONE_ID
    ) {
        val local = TimeUtils.formatUtcToLocal(epochMsUtc, zoneId)
        println("TimestampDiagnostics: $field UTC=$epochMsUtc -> Local($zoneId)=$local")
    }

    /**
     * Capture "now" for a named mutation and emit the UTC → Local diagnostic line.
     */
    fun mutatingNow(
        field: String,
        zoneId: String = TimeUtils.DEFAULT_ZONE_ID
    ): Long {
        val epochMs = TimeUtils.now()
        logMutation(field = field, epochMsUtc = epochMs, zoneId = zoneId)
        return epochMs
    }
}

internal expect fun formatEpochMsToLocal(epochMs: Long, zoneId: String): String
