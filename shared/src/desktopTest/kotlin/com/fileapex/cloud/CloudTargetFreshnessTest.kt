package com.fileapex.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTargetFreshnessTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun aRecordRefreshedRecentlyIsFresh() {
        assertFalse(CloudTargetFreshness.isStale(now - 10 * 60 * 1000, now))
        assertFalse(CloudTargetFreshness.isStale(now - 6 * day, now))
    }

    @Test
    fun aRecordOlderThanAWeekIsStale() {
        assertTrue(CloudTargetFreshness.isStale(now - 8 * day, now))
    }

    @Test
    fun anUnknownAgeIsNotCalledStale() {
        assertFalse(CloudTargetFreshness.isStale(0L, now))
    }
}
