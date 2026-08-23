package com.fileapex.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal actual fun formatEpochMsToLocal(epochMs: Long, zoneId: String): String {
    val zoned = Instant.ofEpochMilli(epochMs).atZone(ZoneId.of(zoneId))
    return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zoned)
}

internal actual fun localizeNoteListStamp(epochMs: Long, zoneId: String): NoteListStamp {
    val zoned = Instant.ofEpochMilli(epochMs).atZone(ZoneId.of(zoneId))
    return NoteListStamp(
        dayKey = zoned.toLocalDate().toString(),
        dateHeader = com.fileapex.i18n.formatLocalizedDate(epochMs, zoneId),
        timeLabel = com.fileapex.i18n.formatLocalizedTime(epochMs, zoneId)
    )
}
