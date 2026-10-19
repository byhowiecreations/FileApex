package com.fileapex.data.bulletin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulletinDeltaTest {
    private val day = 24 * 60 * 60 * 1000L
    private fun change(timeMs: Long, id: String) =
        BulletinDelta.Change(timeMs, BulletinSyncItem(payloadType = BulletinPayloadType.MESSAGE, payloadId = id, body = "{}"))

    @Test
    fun aCursorOlderThanThirtyDaysIsStale() {
        val now = 100 * day
        assertTrue(BulletinDelta.isStale(since = now - 31 * day, nowMs = now))
        assertFalse(BulletinDelta.isStale(since = now - 29 * day, nowMs = now))
    }

    @Test
    fun noCursorIsNotStaleButIsAFullSnapshot() {
        assertFalse(BulletinDelta.isStale(since = 0L, nowMs = 100 * day))
    }

    @Test
    fun pageIsOrderedByChangeTimeAndCursorIsTheLastItem() {
        val page = BulletinDelta.page(listOf(change(30, "c"), change(10, "a"), change(20, "b")), since = 0L, limit = 10)
        assertEquals(listOf("a", "b", "c"), page.items.map { it.payloadId })
        assertEquals(30L, page.cursor)
        assertFalse(page.more)
    }

    @Test
    fun aFullPageReportsMoreAndTheCursorStopsAtTheLastItemSent() {
        val page = BulletinDelta.page((1..5).map { change(it * 10L, "m$it") }, since = 0L, limit = 3)
        assertEquals(listOf("m1", "m2", "m3"), page.items.map { it.payloadId })
        assertEquals(30L, page.cursor)
        assertTrue(page.more)
    }

    @Test
    fun anEmptyDeltaKeepsTheCursor() {
        val page = BulletinDelta.page(emptyList(), since = 777L, limit = 10)
        assertTrue(page.items.isEmpty())
        assertEquals(777L, page.cursor)
        assertFalse(page.more)
    }

    @Test
    fun manyChangesAtTheSameTimeStillMakeProgress() {
        val page = BulletinDelta.page((1..5).map { change(50L, "m$it") }, since = 50L, limit = 3)
        assertEquals(3, page.items.size)
        assertTrue(page.cursor > 50L)
    }

    @Test
    fun olderBuildsHaveNoDeltaEndpoint() {
        assertFalse(BulletinDelta.supportedBy(clientVersion = "0.19.0", clientVersionCode = 206))
        assertFalse(BulletinDelta.supportedBy(clientVersion = "0.18.2", clientVersionCode = 2026205))
        assertTrue(BulletinDelta.supportedBy(clientVersion = "0.19.1", clientVersionCode = 207))
        assertTrue(BulletinDelta.supportedBy(clientVersion = "0.19.1", clientVersionCode = 2026207))
        assertTrue(BulletinDelta.supportedBy(clientVersion = "0.20.0", clientVersionCode = 0))
        assertFalse(BulletinDelta.supportedBy(clientVersion = "", clientVersionCode = 0))
        assertFalse(BulletinDelta.supportedBy(clientVersion = "docker", clientVersionCode = 0))
    }

    @Test
    fun anOfflineDeltaPeerIsNotQueuedButAnOfflineOlderPeerIs() {
        assertTrue(BulletinDelta.queueForPeer(supportsDelta = true, isOnline = true))
        assertFalse(BulletinDelta.queueForPeer(supportsDelta = true, isOnline = false))
        assertTrue(BulletinDelta.queueForPeer(supportsDelta = false, isOnline = false))
        assertTrue(BulletinDelta.queueForPeer(supportsDelta = false, isOnline = true))
    }

    @Test
    fun weAskForADeltaOnlyWhenThePeerIsAheadOfOurCursor() {
        assertTrue(BulletinDelta.shouldPull(peerVersion = 500L, cursor = 400L))
        assertTrue(BulletinDelta.shouldPull(peerVersion = 500L, cursor = 0L))
        assertFalse(BulletinDelta.shouldPull(peerVersion = 500L, cursor = 500L))
        assertFalse(BulletinDelta.shouldPull(peerVersion = 0L, cursor = 0L))
    }

    @Test
    fun aSnapshotIsSentForNoCursorAStaleCursorOrACursorFromTheFuture() {
        val now = 100 * day
        assertTrue(BulletinDelta.isSnapshot(since = 0L, latestChange = now, nowMs = now))
        assertTrue(BulletinDelta.isSnapshot(since = now - 40 * day, latestChange = now, nowMs = now))
        assertTrue(BulletinDelta.isSnapshot(since = now + 5, latestChange = now, nowMs = now))
        assertFalse(BulletinDelta.isSnapshot(since = now - day, latestChange = now, nowMs = now))
    }
}
