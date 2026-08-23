package com.fileapex.util

import com.fileapex.platform.currentTimeMillis
import com.fileapex.i18n.formatLocalizedDateTime

/**
 * Prefer [now] instead of [System.currentTimeMillis] / platform [currentTimeMillis].
 */
object TimeUtils {
    fun now(): Long = currentTimeMillis()

    fun millisSince(epochMs: Long): Long = (now() - epochMs).coerceAtLeast(0L)

    fun remainingMs(startedAtEpochMs: Long, minDurationMs: Long): Long =
        (minDurationMs - millisSince(startedAtEpochMs)).coerceAtLeast(0L)

    fun isWithinWindow(epochMs: Long, windowMs: Long): Boolean =
        epochMs > 0L && millisSince(epochMs) <= windowMs

    /**
     * 20-minute AlarmManager interval for Android FGS recovery ([ServiceWatchdogScheduler]).
     * Not used for cloud presence; that timer is
     * [com.fileapex.domain.presence.LanPresenceTiming.FIRESTORE_PRESENCE_HEARTBEAT_MS].
     */
    const val SERVICE_WATCHDOG_ALARM_INTERVAL_MS: Long = 20 * 60 * 1000L

    const val SERVICE_WATCHDOG_IMMEDIATE_ALARM_DELAY_MS: Long = 30_000L

    /**
     * Max age of an FGS liveness heartbeat before recovery treats the share server as dead.
     * Slightly longer than [SERVICE_WATCHDOG_ALARM_INTERVAL_MS] so a healthy FGS is not restarted
     * on every alarm tick; heartbeats refresh on [onStartCommand] / re-assert paths only.
     */
    const val SHARE_SERVER_HEARTBEAT_STALE_MS: Long = 25 * 60 * 1000L

    fun nextAlarmEpochMs(intervalMs: Long = SERVICE_WATCHDOG_ALARM_INTERVAL_MS): Long =
        now() + intervalMs.coerceAtLeast(1L)

    fun immediateWatchdogAlarmEpochMs(
        delayMs: Long = SERVICE_WATCHDOG_IMMEDIATE_ALARM_DELAY_MS
    ): Long = now() + delayMs.coerceAtLeast(1L)

    /**
     * Formats a UTC epoch millis instant into a local offset datetime using [zoneId].
     * Default zone is America/New_York so DST is handled by the TZDB, not a hardcoded offset.
     */
    fun formatUtcToLocal(
        epochMs: Long,
        zoneId: String = DEFAULT_ZONE_ID
    ): String = formatEpochMsToLocal(epochMs, zoneId)

    /** Pre-localized so ViewModels do not do time-math. */
    fun formatLastSeenLabel(epochMs: Long, zoneId: String = DEFAULT_ZONE_ID): String? {
        if (epochMs <= 0L) return null
        return com.fileapex.i18n.AppI18n.t("last_seen", formatLocalizedDateTime(epochMs, zoneId))
    }

    const val DEFAULT_ZONE_ID: String = "America/New_York"

    /** Notes list: `MM/dd` group header and `h:mm a` on the bubble. */
    fun noteListStamp(epochMs: Long, zoneId: String = DEFAULT_ZONE_ID): NoteListStamp =
        localizeNoteListStamp(epochMs, zoneId)
}

data class NoteListStamp(
    val dayKey: String,
    val dateHeader: String,
    val timeLabel: String
)

/** Logs UTC epoch plus localized wall time to catch double-conversion bugs. */
object TimestampDiagnostics {
    fun logMutation(
        field: String,
        epochMsUtc: Long,
        zoneId: String = TimeUtils.DEFAULT_ZONE_ID
    ) {
        val local = TimeUtils.formatUtcToLocal(epochMsUtc, zoneId)
        println("TimestampDiagnostics: $field UTC=$epochMsUtc -> Local($zoneId)=$local")
    }

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

internal expect fun localizeNoteListStamp(epochMs: Long, zoneId: String): NoteListStamp
