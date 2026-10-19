package com.fileapex.data.bulletin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BulletinStampClockTest {
    @Test
    fun aStampFollowsTheClockWhenItMovesForward() {
        val clock = BulletinStampClock()
        assertEquals(1_000L, clock.next(1_000L))
        assertEquals(2_000L, clock.next(2_000L))
    }

    @Test
    fun twoStampsInTheSameMillisecondStillDiffer() {
        val clock = BulletinStampClock()
        val a = clock.next(5_000L)
        val b = clock.next(5_000L)
        assertTrue(b > a)
    }

    @Test
    fun aClockSetBackNeverProducesALowerStamp() {
        val clock = BulletinStampClock()
        val ahead = clock.next(9_000_000L)
        val afterCorrection = clock.next(1_000L)
        assertTrue(afterCorrection > ahead)
    }

    @Test
    fun stampsStartAboveWhatTheDatabaseAlreadyHolds() {
        val clock = BulletinStampClock()
        clock.observe(7_000_000L)
        assertTrue(clock.next(1_000L) > 7_000_000L)
    }

    @Test
    fun observingALowerValueChangesNothing() {
        val clock = BulletinStampClock()
        clock.next(4_000L)
        clock.observe(100L)
        assertEquals(4_001L, clock.next(1L))
    }
}
