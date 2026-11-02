package com.fileapex.friends

import com.fileapex.platform.MacFriendNotificationSink
import com.fileapex.platform.FriendsNotificationNative
import com.fileapex.platform.FriendsNotificationCallback
import org.junit.Assert.*
import org.junit.Test

class MacFriendNotificationSinkTest {
    private class Native : FriendsNotificationNative {
        lateinit var click: FriendsNotificationCallback
        var posted = ""
        var removed = ""
        override fun fileapex_friends_notifications_install(callback: FriendsNotificationCallback): Int { click = callback; return 1 }
        override fun fileapex_friends_notifications_permission() = Unit
        override fun fileapex_friends_notifications_post(id: String, title: String, body: String) { posted = id }
        override fun fileapex_friends_notifications_remove(id: String) { removed = id }
    }

    @Test fun nativeClicksAreOneShotAndResolvedIdsAreInert() {
        val native = Native()
        var opened = 0
        val sink = MacFriendNotificationSink(native)
        sink.post("offer:a", "Alex", "file.txt") { opened++ }
        val first = native.posted
        assertFalse(first.contains("offer:a"))
        native.click.invoke(first)
        native.click.invoke(first)
        assertEquals(1, opened)
        sink.post("offer:b", "Alex", "file.txt") { opened++ }
        val second = native.posted
        sink.remove("offer:b")
        assertEquals(second, native.removed)
        native.click.invoke(second)
        assertEquals(1, opened)
    }
}
