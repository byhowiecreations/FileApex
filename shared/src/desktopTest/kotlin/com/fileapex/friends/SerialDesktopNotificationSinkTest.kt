package com.fileapex.friends

import com.fileapex.platform.SerialDesktopNotificationSink
import com.fileapex.platform.DesktopNotificationDelivery
import org.junit.Assert.*
import org.junit.Test

class SerialDesktopNotificationSinkTest {
    private class Delivery : DesktopNotificationDelivery {
        val shown = mutableListOf<String>()
        val replies = mutableListOf<(Boolean) -> Unit>()
        var closes = 0
        override fun show(title: String, body: String, done: (Boolean) -> Unit) { shown += title; replies += done }
        override fun close() { closes++ }
    }

    @Test fun resolvedNotificationCannotOpenOrDismissTheNextOffer() {
        val native = Delivery()
        val sink = SerialDesktopNotificationSink(native)
        var first = 0
        var second = 0
        sink.post("a", "first", "a.txt") { first++ }
        sink.post("b", "second", "b.txt") { second++ }
        assertEquals(listOf("first"), native.shown)
        val stale = native.replies.first()
        sink.remove("a")
        assertEquals(1, native.closes)
        assertEquals(listOf("first", "second"), native.shown)
        stale(true)
        assertEquals(0, first)
        assertEquals(0, second)
        native.replies.last()(true)
        native.replies.last()(true)
        assertEquals(1, second)
    }

    @Test fun queuedNoticesAreBoundedAndRemovedOnesAreNeverShown() {
        val native = Delivery()
        val sink = SerialDesktopNotificationSink(native)
        repeat(100) { sink.post("$it", "$it", "file.txt") {} }
        sink.remove("99")
        var consumed = 0
        while (consumed < native.replies.size) native.replies[consumed++](false)
        assertTrue(native.shown.size <= 64)
        assertFalse("99" in native.shown)
    }

    @Test fun shutdownDropsTheQueueAndAllLateCallbacks() {
        val native = Delivery()
        val sink = SerialDesktopNotificationSink(native)
        var opened = 0
        sink.post("a", "first", "a.txt") { opened++ }
        sink.post("b", "second", "b.txt") { opened++ }
        sink.dispose()
        native.replies.first()(true)
        sink.post("c", "third", "c.txt") { opened++ }
        assertEquals(listOf("first"), native.shown)
        assertEquals(1, native.closes)
        assertEquals(0, opened)
    }
}
