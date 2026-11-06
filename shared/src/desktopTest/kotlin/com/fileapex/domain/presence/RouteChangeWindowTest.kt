package com.fileapex.domain.presence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteChangeWindowTest {
    private var now = 1_000_000L
    private val minute = 60_000L
    private val window = RouteChangeWindow(clock = { now }, windowMs = 2 * minute, minGapMs = 5 * minute)

    @Test
    fun theWindowIsClosedUntilTheNetworkChanges() {
        assertFalse(window.isOpen())
    }

    @Test
    fun aNetworkChangeOpensItForTwoMinutes() {
        assertTrue(window.onNetworkChanged())
        assertTrue(window.isOpen())
        now += 2 * minute - 1
        assertTrue(window.isOpen())
        now += 1
        assertFalse(window.isOpen())
    }

    @Test
    fun aPhoneFlappingBetweenNetworksOpensItOnlyOncePerFiveMinutes() {
        assertTrue(window.onNetworkChanged())
        now += 3 * minute
        assertFalse("a second change inside the gap is ignored", window.onNetworkChanged())
        assertFalse(window.isOpen())
        now += 2 * minute
        assertTrue("the gap is over", window.onNetworkChanged())
        assertTrue(window.isOpen())
    }

    @Test
    fun aChangeInsideAnOpenWindowDoesNotExtendIt() {
        window.onNetworkChanged()
        now += minute
        window.onNetworkChanged()
        now += minute
        assertFalse(window.isOpen())
    }

    private val leaveGap = 30_000L
    private val leaving = RouteChangeWindow(clock = { now }, windowMs = 2 * minute, minGapMs = 5 * minute, leaveGapMs = leaveGap)

    @Test
    fun leavingALanOpensTheWindowEvenRightAfterTheAppStartedAndOpenedOne() {
        assertTrue(leaving.onNetworkChanged(onLan = false))
        now += 40_000L
        assertFalse(leaving.onNetworkChanged(onLan = true))
        now += 10_000L
        assertTrue("a real move off the LAN is not held back by the 5 minute gap", leaving.onNetworkChanged(onLan = false))
    }

    @Test
    fun aBounceOffAndOnTheLanInsideThirtySecondsOpensItOnlyOnce() {
        assertTrue(leaving.onNetworkChanged(onLan = false))
        now += 5_000L
        leaving.onNetworkChanged(onLan = true)
        now += 5_000L
        assertFalse(leaving.onNetworkChanged(onLan = false))
    }

    @Test
    fun arrivingOnALanNeverOpensItBecauseThePeersAreReachedThereAnyway() {
        assertFalse(leaving.onNetworkChanged(onLan = true))
        assertFalse(leaving.isOpen())
    }

    @Test
    fun repeatedEventsOffTheLanKeepTheFiveMinuteGap() {
        assertTrue(leaving.onNetworkChanged(onLan = false))
        now += minute
        assertFalse(leaving.onNetworkChanged(onLan = false))
        now += 4 * minute
        assertTrue(leaving.onNetworkChanged(onLan = false))
    }
}
