package com.fileapex.friends

import org.junit.Assert.*
import org.junit.Test

class DesktopFriendNotificationsTest {
    private class Sink : DesktopFriendNotificationSink {
        val shown = linkedMapOf<String, () -> Unit>()
        val removed = mutableListOf<String>()
        override fun post(id: String, title: String, body: String, onOpen: () -> Unit) { shown[id] = onOpen }
        override fun remove(id: String) { removed += id }
    }

    @Test fun offerClickOpensTheQueueOnlyWhileTheOfferIsLive() {
        val sink = Sink()
        var live = true
        var opened = 0
        val notifications = DesktopFriendNotifications(sink, { false }, { true }, { live }, { opened++ }, {})
        val offer = PendingFriendOffer("Aa", "Alex", "report.txt", 20)
        notifications.offerPending(offer)
        val click = sink.shown.getValue("offer:Aa")
        click()
        assertEquals(1, opened)
        live = false
        notifications.offerResolved(offer.id)
        click()
        assertEquals(1, opened)
        assertEquals(listOf("offer:Aa"), sink.removed)
    }

    @Test fun foregroundAndDisabledCompletionSettingsSuppressNativePosts() {
        val sink = Sink()
        var foreground = true
        var completionEnabled = false
        var opened: String? = null
        val notifications = DesktopFriendNotifications(sink, { foreground }, { completionEnabled }, { true }, {}, { opened = it })
        notifications.offerPending(PendingFriendOffer("a", "Alex", "report.txt", 20))
        assertTrue(sink.shown.isEmpty())
        foreground = false
        val file = FriendReceivedFile("Alex", "report.txt", "/tmp/example/report.txt")
        notifications.fileReceived(file)
        assertTrue(sink.shown.isEmpty())
        completionEnabled = true
        notifications.fileReceived(file)
        sink.shown.values.single().invoke()
        assertEquals(file.path, opened)
    }

    @Test fun collidingOfferHashesKeepIndependentNativeIdentities() {
        val sink = Sink()
        val notifications = DesktopFriendNotifications(sink, { false }, { true }, { true }, {}, {})
        listOf("Aa", "BB").forEach { notifications.offerPending(PendingFriendOffer(it, "Alex", "report.txt", 20)) }
        assertEquals(setOf("offer:Aa", "offer:BB"), sink.shown.keys)
        notifications.offerResolved("Aa")
        assertEquals(listOf("offer:Aa"), sink.removed)
    }

    @Test fun platformNotifierRoutesOffersAndCompletionThroughTheInstalledController() {
        val sink = Sink()
        val previous = desktopFriendNotifications
        try {
            desktopFriendNotifications = DesktopFriendNotifications(sink, { false }, { true }, { true }, {}, {})
            FriendsNotifier.offerPending(PendingFriendOffer("routed", "Alex", "report.txt", 20))
            FriendsNotifier.fileReceived(FriendReceivedFile("Alex", "report.txt", "/tmp/example/report.txt"))
            FriendsNotifier.offerResolved("routed")
            assertEquals(2, sink.shown.size)
            assertEquals(listOf("offer:routed"), sink.removed)
        } finally { desktopFriendNotifications = previous }
    }
}
