package com.fileapex.data.bulletin

data class BatteryLowAlert(
    val deviceId: String,
    val deviceName: String,
    val reportedAtEpochMs: Long,
    val levelAtReport: Int?
)

sealed interface HeaderAlert {
    data class NewMessages(val count: Int) : HeaderAlert
    data class BatteryLow(val count: Int) : HeaderAlert
    data object CriticalNotice : HeaderAlert
}

object BulletinUnread {
    private val LEVEL = Regex("(\\d{1,3})%")

    /**
     * Posts from other devices that reached this device after [seenAt]. [MessageEntity.updatedAt] is this device's own
     * store time, so a post written long ago by a device that was away still counts when it arrives. Battery alerts
     * have their own line.
     */
    fun count(messages: List<MessageEntity>, selfId: String, seenAt: Long): Int = unreadIds(messages, selfId, seenAt).size

    fun unreadIds(messages: List<MessageEntity>, selfId: String, seenAt: Long): Set<String> = messages
        .filter { !it.isDeleted && it.originDeviceId != selfId && it.updatedAt > seenAt && it.contentType != BulletinContentType.BATTERY_LOW }
        .map { it.id }.toSet()

    /** Counts only, for a log line when the unread number jumps: tells a reset marker from rows that were just rewritten. */
    fun jumpNote(messages: List<MessageEntity>, selfId: String, seenAt: Long, now: Long): String {
        val minute = 60_000L
        val newest = messages.maxOfOrNull { it.updatedAt }
        return "unread=${count(messages, selfId, seenAt)} rows=${messages.size} markerAgeMin=${(now - seenAt) / minute} " +
            "newestChangeAgeMin=${newest?.let { (now - it) / minute } ?: -1} fromOthers=${messages.count { it.originDeviceId != selfId }} " +
            "changedLast5Min=${messages.count { it.updatedAt >= now - 5 * minute }}"
    }

    fun batteryLow(messages: List<MessageEntity>, selfId: String): List<BatteryLowAlert> = messages
        .filter { !it.isDeleted && it.originDeviceId != selfId && it.contentType == BulletinContentType.BATTERY_LOW }
        .groupBy { it.originDeviceId }
        .map { (deviceId, posts) ->
            val newest = posts.maxBy { it.timestamp }
            BatteryLowAlert(
                deviceId = deviceId,
                deviceName = newest.senderName,
                reportedAtEpochMs = newest.timestamp,
                levelAtReport = LEVEL.find(newest.content)?.groupValues?.get(1)?.toIntOrNull()
            )
        }

    /** The newest stamp seen, so opening the Bulletin Board clears everything that has arrived so far. */
    fun latestStamp(messages: List<MessageEntity>): Long = messages.maxOfOrNull { it.updatedAt } ?: 0L
}

object HeaderAlerts {
    fun active(unread: Int, batteryLow: Int, criticalNotice: Boolean = false): List<HeaderAlert> = buildList {
        if (criticalNotice) add(HeaderAlert.CriticalNotice)
        if (unread > 0) add(HeaderAlert.NewMessages(unread))
        if (batteryLow > 0) add(HeaderAlert.BatteryLow(batteryLow))
    }

    /** The alert to show at step [index] of the rotation; wraps around, null when there is nothing to show. */
    fun at(alerts: List<HeaderAlert>, index: Int): HeaderAlert? = if (alerts.isEmpty()) null else alerts[index.mod(alerts.size)]
}
