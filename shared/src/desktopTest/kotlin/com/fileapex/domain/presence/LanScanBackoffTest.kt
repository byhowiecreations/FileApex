package com.fileapex.domain.presence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanScanBackoffTest {
    private companion object {
        const val HOME = "172.16.16"
        const val OFFICE = "192.168.1"
    }

    private var now = 1_000_000L
    private val backoff = LanScanBackoff(baseMs = 30 * 60_000L, maxMs = 4 * 60 * 60_000L, now = { now })

    @Test
    fun firstScanIsAllowed() {
        assertTrue(backoff.shouldScan("peer-a", HOME))
    }

    @Test
    fun failedScanSuppressesTheNextSweep() {
        backoff.recordFailure("peer-a", HOME)

        now += 5 * 60_000L
        assertFalse(backoff.shouldScan("peer-a", HOME))
        now += 24 * 60_000L
        assertFalse(backoff.shouldScan("peer-a", HOME))
        now += 60_000L
        assertTrue(backoff.shouldScan("peer-a", HOME))
    }

    @Test
    fun consecutiveFailuresDoubleTheWaitUpToTheCap() {
        backoff.recordFailure("peer-a", HOME)
        now += 30 * 60_000L
        assertTrue(backoff.shouldScan("peer-a", HOME))

        backoff.recordFailure("peer-a", HOME)
        now += 59 * 60_000L
        assertFalse(backoff.shouldScan("peer-a", HOME))
        now += 60_000L
        assertTrue(backoff.shouldScan("peer-a", HOME))

        repeat(6) {
            backoff.recordFailure("peer-a", HOME)
            now += 4 * 60 * 60_000L
            assertTrue(backoff.shouldScan("peer-a", HOME))
        }
    }

    @Test
    fun successClearsTheBackoff() {
        backoff.recordFailure("peer-a", HOME)
        backoff.recordSuccess("peer-a")

        assertTrue(backoff.shouldScan("peer-a", HOME))
    }

    @Test
    fun peersAreTrackedSeparately() {
        backoff.recordFailure("peer-a", HOME)

        assertFalse(backoff.shouldScan("peer-a", HOME))
        assertTrue(backoff.shouldScan("peer-b", HOME))
    }

    @Test
    fun aDifferentNetworkScansAtOnce() {
        backoff.recordFailure("peer-a", HOME)

        assertTrue(backoff.shouldScan("peer-a", OFFICE))
    }

    @Test
    fun theSameNetworkAfterAScreenOnStaysSuppressed() {
        backoff.recordFailure("peer-a", HOME)
        now += 3 * 60_000L

        assertFalse(backoff.shouldScan("peer-a", HOME))
    }

    @Test
    fun aFailureOnANewNetworkStartsAtTheBaseWait() {
        repeat(3) {
            backoff.recordFailure("peer-a", HOME)
            now += 4 * 60 * 60_000L
        }
        backoff.recordFailure("peer-a", OFFICE)

        now += 30 * 60_000L
        assertTrue(backoff.shouldScan("peer-a", OFFICE))
    }

    @Test
    fun aRestartKeepsTheWait() {
        var saved = ""
        val first = LanScanBackoff(30 * 60_000L, 4 * 60 * 60_000L, { now }, saved, { saved = it })
        first.recordFailure("peer-a", HOME)

        now += 5 * 60_000L
        val restarted = LanScanBackoff(30 * 60_000L, 4 * 60 * 60_000L, { now }, saved, { saved = it })
        assertFalse(restarted.shouldScan("peer-a", HOME))
        assertTrue(restarted.shouldScan("peer-a", OFFICE))
        now += 26 * 60_000L
        assertTrue(restarted.shouldScan("peer-a", HOME))
    }

    @Test
    fun aSuccessIsForgottenAfterARestart() {
        var saved = ""
        val first = LanScanBackoff(30 * 60_000L, 4 * 60 * 60_000L, { now }, saved, { saved = it })
        first.recordFailure("peer-a", HOME)
        first.recordSuccess("peer-a")

        val restarted = LanScanBackoff(30 * 60_000L, 4 * 60 * 60_000L, { now }, saved, { saved = it })
        assertTrue(restarted.shouldScan("peer-a", HOME))
    }

    @Test
    fun damagedOrFarFutureSavedStateIsIgnored() {
        val maxMs = 4 * 60 * 60_000L
        val farFuture = "peer-a\t$HOME\t3\t${now + 2 * maxMs}"
        val damaged = "garbage\npeer-b\t$HOME\tx\ty"
        val restarted = LanScanBackoff(30 * 60_000L, maxMs, { now }, "$farFuture\n$damaged", {})

        assertTrue(restarted.shouldScan("peer-a", HOME))
        assertTrue(restarted.shouldScan("peer-b", HOME))
    }

    @Test
    fun aReachOverAnotherRouteHoldsTheScanWithoutClearingTheWait() {
        backoff.recordRemoteReach("peer-a", HOME, holdMs = 15 * 60_000L)

        now += 14 * 60_000L
        assertFalse(backoff.shouldScan("peer-a", HOME))
        now += 2 * 60_000L
        assertTrue(backoff.shouldScan("peer-a", HOME))
    }

    @Test
    fun aReachOverAnotherRouteDoesNotShortenAnExistingWait() {
        backoff.recordFailure("peer-a", HOME)
        backoff.recordFailure("peer-a", HOME)

        backoff.recordRemoteReach("peer-a", HOME, holdMs = 15 * 60_000L)

        now += 59 * 60_000L
        assertFalse(backoff.shouldScan("peer-a", HOME))
    }

    @Test
    fun aReachOverAnotherRouteOnANewNetworkScansAtOnceAfterTheHold() {
        backoff.recordRemoteReach("peer-a", HOME, holdMs = 15 * 60_000L)

        assertTrue(backoff.shouldScan("peer-a", OFFICE))
    }

    @Test
    fun aLanReachStillClearsAHold() {
        backoff.recordRemoteReach("peer-a", HOME, holdMs = 15 * 60_000L)
        backoff.recordSuccess("peer-a")

        assertTrue(backoff.shouldScan("peer-a", HOME))
    }
}
