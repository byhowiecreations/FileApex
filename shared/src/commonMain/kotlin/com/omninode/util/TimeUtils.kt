package com.omninode.util

import com.omninode.platform.currentTimeMillis

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
     * Formats a UTC epoch millis instant into a local offset datetime using [zoneId].
     * Default zone follows RULES.md (America/New_York) for DST-safe localization.
     */
    fun formatUtcToLocal(
        epochMs: Long,
        zoneId: String = DEFAULT_ZONE_ID
    ): String = formatEpochMsToLocal(epochMs, zoneId)

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
