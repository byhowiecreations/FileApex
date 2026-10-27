package com.fileapex.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessExitSummaryTest {
    @Test
    fun namesTheCodesThatMatterForAKill() {
        assertEquals("SIGNALED", ProcessExitSummary.reasonName(2))
        assertEquals("LOW_MEMORY", ProcessExitSummary.reasonName(3))
        assertEquals("CRASH", ProcessExitSummary.reasonName(4))
        assertEquals("EXCESSIVE_RESOURCE_USAGE", ProcessExitSummary.reasonName(9))
        assertEquals("PACKAGE_UPDATED", ProcessExitSummary.reasonName(16))
        assertEquals("UNKNOWN(99)", ProcessExitSummary.reasonName(99))
    }

    @Test
    fun onlySystemAndVendorKillsCountAsKills() {
        assertTrue(ProcessExitSummary.isSystemKill(2))
        assertTrue(ProcessExitSummary.isSystemKill(3))
        assertTrue(ProcessExitSummary.isSystemKill(9))
        assertTrue(ProcessExitSummary.isSystemKill(13))
        assertTrue(ProcessExitSummary.isSystemKill(14))
        assertFalse(ProcessExitSummary.isSystemKill(1))
        assertFalse(ProcessExitSummary.isSystemKill(4))
        assertFalse(ProcessExitSummary.isSystemKill(10))
        assertFalse(ProcessExitSummary.isSystemKill(16))
    }

    @Test
    fun lineShowsReasonAgeMemoryAndAShortenedDescription() {
        val line = ProcessExitSummary.line(
            exitAtMs = 1_000_000L,
            nowMs = 1_000_000L + 9_000L,
            reasonCode = 2,
            status = 9,
            importance = 125,
            rssKb = 260_096L,
            description = "x".repeat(200)
        )
        assertEquals(
            "previous process exit: SIGNALED status=9 importance=125 rss=254 MB 9 s ago (kill) " + "x".repeat(80),
            line
        )
    }

    @Test
    fun anUpdateIsNotCalledAKillAndAMissingDescriptionIsOmitted() {
        val line = ProcessExitSummary.line(1_000L, 61_000L, 16, 0, 400, 0L, null)
        assertEquals("previous process exit: PACKAGE_UPDATED status=0 importance=400 rss=0 MB 1 min ago", line)
    }

    @Test
    fun recentKillsAreCountedInsideTheWindowOnly() {
        val day = 24 * 60 * 60_000L
        val now = 10 * day
        val exits = listOf(
            now - 1 * day to 2,
            now - 2 * day to 3,
            now - 3 * day to 16,
            now - 8 * day to 2
        )
        assertEquals(2, ProcessExitSummary.recentSystemKills(exits, now, 7 * day))
    }
}
