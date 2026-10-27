package com.fileapex.data.bulletin

import org.junit.Assert.assertEquals
import org.junit.Test

class BulletinUnreadTest {
    private fun row(
        id: String,
        from: String = "phone",
        stamp: Long = 100,
        type: Int = BulletinContentType.TEXT,
        deleted: Boolean = false,
        content: String = "hi",
        timestamp: Long = 50
    ) = MessageEntity(id, from, "Name $from", content, type, timestamp, isDeleted = deleted, updatedAt = stamp)

    @Test
    fun onlyOthersNewPostsCountAsUnread() {
        val rows = listOf(
            row("a", stamp = 200),
            row("mine", from = "me", stamp = 200),
            row("old", stamp = 90),
            row("gone", stamp = 200, deleted = true),
            row("battery", stamp = 200, type = BulletinContentType.BATTERY_LOW)
        )
        assertEquals(1, BulletinUnread.count(rows, selfId = "me", seenAt = 100))
    }

    @Test
    fun aPostThatArrivesLateCountsEvenWhenItWasWrittenBeforeTheLastVisit() {
        val late = row("late", stamp = 500, timestamp = 10)
        assertEquals(1, BulletinUnread.count(listOf(late), selfId = "me", seenAt = 400))
    }

    @Test
    fun oneLowBatteryAlertPerDeviceWithItsReportedLevel() {
        val rows = listOf(
            row("b1", from = "p1", type = BulletinContentType.BATTERY_LOW, content = "The battery level is 15%.", timestamp = 1000),
            row("b2", from = "p1", type = BulletinContentType.BATTERY_LOW, content = "The battery level is 12%.", timestamp = 2000),
            row("b3", from = "p2", type = BulletinContentType.BATTERY_LOW, content = "The battery is low.", timestamp = 1500),
            row("mine", from = "me", type = BulletinContentType.BATTERY_LOW, timestamp = 3000),
            row("cleared", from = "p3", type = BulletinContentType.BATTERY_LOW, deleted = true)
        )
        val alerts = BulletinUnread.batteryLow(rows, selfId = "me")
        assertEquals(listOf("p1", "p2"), alerts.map { it.deviceId }.sorted())
        val p1 = alerts.first { it.deviceId == "p1" }
        assertEquals(12, p1.levelAtReport)
        assertEquals(2000L, p1.reportedAtEpochMs)
        assertEquals(null, alerts.first { it.deviceId == "p2" }.levelAtReport)
    }

    @Test
    fun headerAlertsRotateOnlyThroughWhatExists() {
        assertEquals(emptyList<HeaderAlert>(), HeaderAlerts.active(unread = 0, batteryLow = 0))
        assertEquals(listOf(HeaderAlert.NewMessages(3)), HeaderAlerts.active(unread = 3, batteryLow = 0))
        assertEquals(
            listOf(HeaderAlert.NewMessages(1), HeaderAlert.BatteryLow(2)),
            HeaderAlerts.active(unread = 1, batteryLow = 2)
        )
    }

    @Test
    fun theRotationWrapsAndSurvivesALineDisappearing() {
        val two = HeaderAlerts.active(unread = 1, batteryLow = 1)
        assertEquals(two[0], HeaderAlerts.at(two, 0))
        assertEquals(two[1], HeaderAlerts.at(two, 1))
        assertEquals(two[0], HeaderAlerts.at(two, 2))
        assertEquals(null, HeaderAlerts.at(emptyList(), 5))
    }
}
