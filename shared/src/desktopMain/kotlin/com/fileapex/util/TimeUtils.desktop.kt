package com.fileapex.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal actual fun formatEpochMsToLocal(epochMs: Long, zoneId: String): String {
    val zoned = Instant.ofEpochMilli(epochMs).atZone(ZoneId.of(zoneId))
    return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zoned)
}
