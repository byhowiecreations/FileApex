package com.fileapex.domain.presence

import com.fileapex.util.TimeUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReachabilityBookDownTest {
    @Test
    fun aPeerMarkedDownIsDownEvenInsideTheGraceWindow() = runBlocking {
        val book = ReachabilityBook()
        val now = TimeUtils.now()
        book.record(arrayOf("A"), now - 60_000L)
        assertFalse(book.isDown("A", lastSeenEpochMs = now - 60_000L))
        book.markDown("A", now)
        assertTrue(book.isDown("A", lastSeenEpochMs = now - 60_000L))
    }

    @Test
    fun aLaterSuccessClearsTheMark() = runBlocking {
        val book = ReachabilityBook()
        val now = TimeUtils.now()
        book.markDown("A", now)
        book.record(arrayOf("A"), now + 1_000L)
        assertFalse(book.isDown("A", lastSeenEpochMs = 0L))
    }

    @Test
    fun aSuccessOlderThanTheMarkDoesNotClearIt() = runBlocking {
        val book = ReachabilityBook()
        val now = TimeUtils.now()
        book.markDown("A", now)
        book.record(arrayOf("A"), now - 5_000L)
        assertTrue(book.isDown("A", lastSeenEpochMs = now - 5_000L))
    }

    @Test
    fun aStoredLastSeenNewerThanTheMarkWins() = runBlocking {
        val book = ReachabilityBook()
        val now = TimeUtils.now()
        book.markDown("A", now)
        assertFalse(book.isDown("A", lastSeenEpochMs = now + 500L))
    }

    @Test
    fun theMarkBelongsToOneDevice() = runBlocking {
        val book = ReachabilityBook()
        val now = TimeUtils.now()
        book.markDown("A", now)
        assertFalse(book.isDown("B", lastSeenEpochMs = now - 1_000L))
    }

    @Test
    fun markingDownTakesTheDeviceOutOfTheOnlineSetAtOnce() = runBlocking {
        val book = ReachabilityBook()
        val now = TimeUtils.now()
        book.record(arrayOf("A", "B"), now)
        assertTrue("A" in book.onlineIds.value)
        book.markDown("A", now + 1L)
        assertFalse("A" in book.onlineIds.value)
        assertTrue("B" in book.onlineIds.value)
    }

    @Test
    fun aBlankIdIsIgnored() = runBlocking {
        val book = ReachabilityBook()
        book.markDown("  ", TimeUtils.now())
        assertFalse(book.isDown("  ", lastSeenEpochMs = 0L))
    }
}
