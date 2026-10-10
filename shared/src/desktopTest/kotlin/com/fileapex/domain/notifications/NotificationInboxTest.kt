package com.fileapex.domain.notifications

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationInboxTest {

    @After
    fun reset() = NotificationInbox.clear()

    private fun note(key: String, kind: NotificationKind, remove: Boolean = false) =
        NotificationPayload(remove = remove, key = key, kind = kind)

    @Test
    fun countsFollowKindsAndOtherIsEverythingElse() {
        NotificationInbox.apply(note("a", NotificationKind.MESSAGE))
        NotificationInbox.apply(note("b", NotificationKind.EMAIL))
        NotificationInbox.apply(note("c", NotificationKind.OTHER))
        NotificationInbox.apply(note("d", NotificationKind.OTHER))
        assertEquals(NotificationCounts(messages = 1, emails = 1, other = 2), NotificationInbox.counts(NotificationInbox.items.value))
    }

    @Test
    fun repostReplacesAndRemovalClears() {
        NotificationInbox.apply(note("a", NotificationKind.MESSAGE))
        NotificationInbox.apply(note("a", NotificationKind.MESSAGE))
        assertEquals(1, NotificationInbox.items.value.size)
        NotificationInbox.apply(note("a", NotificationKind.MESSAGE, remove = true))
        assertEquals(0, NotificationInbox.items.value.size)
    }

    @Test
    fun activeKeysDropWhatTheOtherDeviceNoLongerShows() {
        NotificationInbox.apply(note("a", NotificationKind.MESSAGE))
        NotificationInbox.apply(note("b", NotificationKind.EMAIL))
        NotificationInbox.apply(NotificationPayload(key = "c", kind = NotificationKind.MESSAGE, activeKeys = listOf("b", "c")))
        assertEquals(listOf("c", "b"), NotificationInbox.items.value.map { it.key })
        NotificationInbox.apply(NotificationPayload(remove = true, key = "c", activeKeys = emptyList()))
        assertEquals(0, NotificationInbox.items.value.size)
    }

    @Test
    fun inboxIsBounded() {
        repeat(500) { NotificationInbox.apply(note("k$it", NotificationKind.OTHER)) }
        assertEquals(200, NotificationInbox.items.value.size)
    }

    @Test
    fun snapshotReplacesWhateverWasMissedWhileOffline() {
        NotificationInbox.apply(note("old", NotificationKind.MESSAGE))
        NotificationInbox.replaceAll(listOf(note("new1", NotificationKind.EMAIL), note("new1", NotificationKind.EMAIL), note("new2", NotificationKind.OTHER)))
        assertEquals(listOf("new1", "new2"), NotificationInbox.items.value.map { it.key })
        NotificationInbox.replaceAll(emptyList())
        assertEquals(0, NotificationInbox.items.value.size)
    }

    @Test
    fun sanitizedCapsEveryPeerSuppliedField() {
        val long = "x".repeat(5_000)
        val clean = NotificationPayload(
            key = long, title = long, text = long, conversation = long, subText = long,
            lines = List(40) { long }, activeKeys = List(500) { long }
        ).sanitized()
        assertEquals(1_000, clean.key.length)
        assertEquals(1_000, clean.conversation.length)
        assertEquals(10, clean.lines.size)
        assertEquals(5_000, clean.lines.first().length)
        assertEquals(5_000, clean.text.length)
        assertEquals(1_000, clean.subText.length)
        assertEquals(8_000, NotificationPayload(key = "k", text = "x".repeat(20_000)).sanitized().text.length)
        assertEquals(200, clean.activeKeys?.size)
    }

    private fun from(device: String, key: String, kind: NotificationKind = NotificationKind.EMAIL, active: List<String>? = null) =
        NotificationPayload(key = key, kind = kind, sourceDeviceId = device, activeKeys = active)

    @Test
    fun countsAreKeptPerPhone() {
        NotificationInbox.apply(from("fold", "g1"))
        NotificationInbox.apply(from("razr", "g2"))
        val items = NotificationInbox.items.value
        assertEquals(1, NotificationInbox.counts(items, "fold").emails)
        assertEquals(1, NotificationInbox.counts(items, "razr").emails)
        assertEquals(2, NotificationInbox.counts(items).emails)
    }

    @Test
    fun onePhonesActiveListDoesNotDropAnotherPhonesItems() {
        NotificationInbox.apply(from("razr", "r1"))
        NotificationInbox.apply(from("fold", "f1", active = listOf("f1")))
        assertEquals(setOf("r1", "f1"), NotificationInbox.items.value.map { it.key }.toSet())
    }

    @Test
    fun snapshotReplacesOnlyItsOwnPhone() {
        NotificationInbox.apply(from("razr", "r1"))
        NotificationInbox.apply(from("fold", "stale"))
        NotificationInbox.replaceAll(listOf(NotificationPayload(key = "f1", kind = NotificationKind.EMAIL)), "fold")
        assertEquals(setOf("r1", "f1"), NotificationInbox.items.value.map { it.key }.toSet())
        assertEquals(1, NotificationInbox.counts(NotificationInbox.items.value, "fold").emails)
    }

    @Test
    fun sanitizingClearsAPeerSuppliedSource() {
        assertEquals("", NotificationPayload(key = "k", sourceDeviceId = "spoofed").sanitized().sourceDeviceId)
    }
}
