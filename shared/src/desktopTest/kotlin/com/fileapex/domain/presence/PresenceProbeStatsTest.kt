package com.fileapex.domain.presence

import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceProbeStatsTest {
    @Test
    fun everyKindOfProbeEventIsCountedSeparately() {
        val stats = PresenceProbeStats()
        stats.sent(); stats.sent(); stats.sent()
        stats.failed()
        stats.skippedByBackoff(); stats.skippedByBackoff()
        stats.forcedRound()
        assertEquals(PresenceProbeStats.Snapshot(sent = 3, failed = 1, skipped = 2, forcedRounds = 1), stats.snapshot())
    }

    @Test
    fun aNewInstanceStartsAtZero() {
        assertEquals(PresenceProbeStats.Snapshot(0, 0, 0, 0), PresenceProbeStats().snapshot())
    }
}
