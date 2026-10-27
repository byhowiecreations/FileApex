package com.fileapex.domain.presence

import com.fileapex.util.TimeUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReachabilityBookTest {
    @Test
    fun recordKeepsTheNewestTimeAndMarksAFreshDeviceOnline() = runBlocking {
        val book = ReachabilityBook()
        val now = TimeUtils.now()
        book.record(arrayOf(" A "), now)
        book.record(arrayOf("A"), now - 5_000L)
        assertEquals(now, book.epochMs.value["A"])
        assertEquals(setOf("A"), book.onlineIds.value)
    }

    @Test
    fun anOldTimestampNeverPutsADeviceInTheOnlineSet() = runBlocking {
        val book = ReachabilityBook()
        book.record(arrayOf("A"), TimeUtils.now() - 24 * 60 * 60_000L)
        assertEquals(mapOf("A" to book.epochMs.value["A"]), book.epochMs.value)
        assertTrue(book.onlineIds.value.isEmpty())
    }

    @Test
    fun blankIdsAreIgnored() = runBlocking {
        val book = ReachabilityBook()
        book.record(arrayOf("", "  "), TimeUtils.now())
        assertTrue(book.epochMs.value.isEmpty())
    }

    @Test
    fun publishOnlineReplacesTheSetAndClearSnapshotEmptiesIt() = runBlocking {
        val book = ReachabilityBook()
        book.publishOnline(setOf("A", "B"))
        assertEquals(setOf("A", "B"), book.onlineIds.value)
        book.markSnapshot()
        assertTrue(book.snapshotEpochMs.value > 0L)
        book.clearSnapshot()
        assertTrue(book.onlineIds.value.isEmpty())
        assertEquals(0L, book.snapshotEpochMs.value)
    }

    @Test
    fun recentlyReachableUsesTheWindow() = runBlocking {
        val book = ReachabilityBook()
        book.record(arrayOf("A"), TimeUtils.now() - 10_000L)
        assertTrue(book.wasRecentlyReachable("A", 60_000L))
        assertFalse(book.wasRecentlyReachable("A", 1_000L))
        assertFalse(book.wasRecentlyReachable("Z", 60_000L))
    }
}
