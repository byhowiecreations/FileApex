package com.fileapex.iroh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohPolicyTest {
    private val now = 10_000_000L

    @Test
    fun peerReachableAnotherWayDoesNotWakeIroh() {
        assertFalse(IrohWakePolicy.needsEndpoint(hasAddress = true, online = true, servedAtMs = null, nowMs = now))
    }

    @Test
    fun offlinePeerWithAddressWakesIroh() {
        assertTrue(IrohWakePolicy.needsEndpoint(hasAddress = true, online = false, servedAtMs = null, nowMs = now))
    }

    @Test
    fun onlinePeerWithoutAddressWakesIrohButOfflineOneDoesNot() {
        assertTrue(IrohWakePolicy.needsEndpoint(hasAddress = false, online = true, servedAtMs = null, nowMs = now))
        assertFalse(IrohWakePolicy.needsEndpoint(hasAddress = false, online = false, servedAtMs = null, nowMs = now))
    }

    @Test
    fun peerServedOverIrohRecentlyKeepsItAwakeEvenWhenOnline() {
        val recent = now - IrohWakePolicy.SERVED_KEEP_MS + 1
        assertTrue(IrohWakePolicy.needsEndpoint(hasAddress = true, online = true, servedAtMs = recent, nowMs = now))
        val old = now - IrohWakePolicy.SERVED_KEEP_MS
        assertFalse(IrohWakePolicy.needsEndpoint(hasAddress = true, online = true, servedAtMs = old, nowMs = now))
    }

    @Test
    fun noLanAndNoTailnetWakesIrohEvenWhenThePeerStillLooksOnline() {
        assertTrue(IrohWakePolicy.needsEndpoint(true, online = true, servedAtMs = null, nowMs = now, noLocalRoute = true))
        assertFalse(IrohWakePolicy.needsEndpoint(false, online = false, servedAtMs = null, nowMs = now, noLocalRoute = true))
    }

    @Test
    fun onlinePeerNotYetSentOurAddressThisRunWakesIrohOnce() {
        assertTrue(IrohWakePolicy.needsEndpoint(true, online = true, servedAtMs = null, nowMs = now, exchangePending = true))
        assertFalse(IrohWakePolicy.needsEndpoint(true, online = true, servedAtMs = null, nowMs = now, exchangePending = false))
    }

    @Test
    fun sleepsOnlyAfterTheGracePeriod() {
        assertFalse(IrohWakePolicy.shouldSleep(notNeededSinceMs = 0L, nowMs = now))
        assertFalse(IrohWakePolicy.shouldSleep(now - IrohWakePolicy.IDLE_GRACE_MS + 1, now))
        assertTrue(IrohWakePolicy.shouldSleep(now - IrohWakePolicy.IDLE_GRACE_MS, now))
    }

    @Test
    fun firstExchangeIsAlwaysDue() {
        assertTrue(IrohExchangePolicy.due(lastOutcome = null, ageMs = null, ownAddressChanged = false))
    }

    @Test
    fun answeredPeerIsAskedAgainOnlyAfterSixHoursOrWhenOurAddressMoved() {
        assertFalse(IrohExchangePolicy.due(IrohExchangePolicy.OK, 60_000L, ownAddressChanged = false))
        assertFalse(IrohExchangePolicy.due(IrohExchangePolicy.OK, IrohExchangePolicy.RETRY_MS - 1, ownAddressChanged = true))
        assertTrue(IrohExchangePolicy.due(IrohExchangePolicy.OK, IrohExchangePolicy.RETRY_MS, ownAddressChanged = true))
        assertFalse(IrohExchangePolicy.due(IrohExchangePolicy.OK, IrohExchangePolicy.REFRESH_MS - 1, ownAddressChanged = false))
        assertTrue(IrohExchangePolicy.due(IrohExchangePolicy.OK, IrohExchangePolicy.REFRESH_MS, ownAddressChanged = false))
    }

    @Test
    fun peerWithoutIrohIsAskedEveryThirtyMinutes() {
        val outcome = IrohExchangePolicy.PEER_WITHOUT_IROH
        assertFalse(IrohExchangePolicy.due(outcome, IrohExchangePolicy.PEER_WITHOUT_IROH_RETRY_MS - 1, false))
        assertTrue(IrohExchangePolicy.due(outcome, IrohExchangePolicy.PEER_WITHOUT_IROH_RETRY_MS, false))
    }

    @Test
    fun failureIsRetriedAfterFiveMinutes() {
        assertFalse(IrohExchangePolicy.due("request_failed: timeout", IrohExchangePolicy.RETRY_MS - 1, false))
        assertTrue(IrohExchangePolicy.due("request_failed: timeout", IrohExchangePolicy.RETRY_MS, false))
    }

    @Test
    fun runtimeOutcomeCodeMatchesThePolicyAndTheUi() {
        assertEquals("peer_has_iroh_off_or_old_build", IrohRuntime.PEER_WITHOUT_IROH)
    }

    @Test
    fun aNoIrohAnswerIsOnlyBelievedForAShortTimeAndNeverAfterContact() {
        val off = IrohExchangePolicy.PEER_WITHOUT_IROH
        assertTrue(IrohPeerStatus.negativeActive(off, outcomeAtMs = now, contactAtMs = 0L, nowMs = now + 1))
        assertFalse(IrohPeerStatus.negativeActive(off, now, 0L, now + IrohExchangePolicy.NEGATIVE_TTL_MS))
        assertFalse(IrohPeerStatus.negativeActive(off, now, contactAtMs = now + 5, nowMs = now + 10))
        assertFalse(IrohPeerStatus.negativeActive("ok", now, 0L, now + 1))
        assertFalse(IrohPeerStatus.negativeActive(null, 0L, 0L, now))
    }

    @Test
    fun aPeerWithoutIrohIsAskedAgainAfterFiveMinutes() {
        val off = IrohExchangePolicy.PEER_WITHOUT_IROH
        assertFalse(IrohExchangePolicy.due(off, IrohExchangePolicy.NEGATIVE_TTL_MS - 1, false))
        assertTrue(IrohExchangePolicy.due(off, IrohExchangePolicy.NEGATIVE_TTL_MS, false))
    }

    @Test
    fun aDeadOrPathlessStoredConnectionIsReplaced() {
        assertTrue(IrohConnectionPolicy.shouldReplace(storedClosed = true, storedHasSelectedPath = true))
        assertTrue(IrohConnectionPolicy.shouldReplace(storedClosed = false, storedHasSelectedPath = false))
        assertFalse(IrohConnectionPolicy.shouldReplace(storedClosed = false, storedHasSelectedPath = true))
    }

    @Test
    fun aRequestIsStalledAfterTheLimitWithNoBytes() {
        assertFalse(IrohStallPolicy.isStalled(lastActivityMs = now, nowMs = now + IrohStallPolicy.STALL_MS - 1))
        assertTrue(IrohStallPolicy.isStalled(now, now + IrohStallPolicy.STALL_MS))
    }

    @Test
    fun onALanAnOfflinePeerNotHeardFromForADayDoesNotWakeIroh() {
        assertFalse(
            IrohWakePolicy.needsEndpoint(hasAddress = true, online = false, servedAtMs = null, nowMs = now, recentlyHeard = false)
        )
        assertTrue(
            IrohWakePolicy.needsEndpoint(hasAddress = true, online = false, servedAtMs = null, nowMs = now, recentlyHeard = true)
        )
    }

    @Test
    fun withoutALanAnOldOfflinePeerStillWakesIroh() {
        assertTrue(
            IrohWakePolicy.needsEndpoint(
                hasAddress = true, online = false, servedAtMs = null, nowMs = now, noLocalRoute = true, recentlyHeard = false
            )
        )
    }

    @Test
    fun heardRecentlyMeansWithinTheOfflineKeepWindow() {
        val late = 200_000_000L
        assertTrue(IrohWakePolicy.heardRecently(lastHeardAtMs = late - IrohWakePolicy.OFFLINE_KEEP_MS + 1, nowMs = late))
        assertFalse(IrohWakePolicy.heardRecently(lastHeardAtMs = late - IrohWakePolicy.OFFLINE_KEEP_MS, nowMs = late))
        assertFalse(IrohWakePolicy.heardRecently(lastHeardAtMs = 0L, nowMs = late))
    }

    @Test
    fun addressExchangeNeedsALanOrATailnet() {
        assertTrue(IrohExchangePolicy.mayExchange(hasLan = true, hasTailnet = false))
        assertTrue(IrohExchangePolicy.mayExchange(hasLan = false, hasTailnet = true))
        assertFalse(IrohExchangePolicy.mayExchange(hasLan = false, hasTailnet = false))
    }

    @Test
    fun theReasonNamesWhyAPeerHoldsTheEndpointUp() {
        assertEquals("served a peer over Iroh", IrohWakePolicy.reason(true, true, now - 1, now))
        assertEquals("no LAN or tailnet", IrohWakePolicy.reason(true, true, null, now, noLocalRoute = true))
        assertEquals("our address not sent yet", IrohWakePolicy.reason(true, true, null, now, exchangePending = true))
        assertEquals("online, no address yet", IrohWakePolicy.reason(false, true, null, now))
        assertEquals("offline, heard within a day", IrohWakePolicy.reason(true, false, null, now))
        assertEquals(null, IrohWakePolicy.reason(true, true, null, now))
        assertEquals(null, IrohWakePolicy.reason(true, false, null, now, recentlyHeard = false))
    }

    @Test
    fun withoutALanTheSweepProbesAnyPeerNotFreshlyReached() {
        assertTrue(IrohSweepPolicy.shouldProbe(canReach = true, msSinceReached = 5 * 60_000L, freshMs = 4 * 60_000L, onLan = false))
        assertFalse(IrohSweepPolicy.shouldProbe(canReach = true, msSinceReached = 60_000L, freshMs = 4 * 60_000L, onLan = false))
        assertTrue(IrohSweepPolicy.shouldProbe(canReach = true, msSinceReached = null, freshMs = 4 * 60_000L, onLan = false))
    }

    @Test
    fun onALanAPeerReachedThereRecentlyIsNotProbedOverIroh() {
        // 45 s and 4 min are the sweep freshness windows; the LAN rule must outlast them.
        assertFalse(IrohSweepPolicy.shouldProbe(true, msSinceReached = 5 * 60_000L, freshMs = 45_000L, onLan = true))
        assertFalse(IrohSweepPolicy.shouldProbe(true, msSinceReached = IrohSweepPolicy.LAN_SKIP_MS - 1, freshMs = 45_000L, onLan = true))
        assertTrue(IrohSweepPolicy.shouldProbe(true, msSinceReached = IrohSweepPolicy.LAN_SKIP_MS, freshMs = 45_000L, onLan = true))
        assertTrue(IrohSweepPolicy.shouldProbe(true, msSinceReached = null, freshMs = 45_000L, onLan = true))
    }

    @Test
    fun aPeerWithoutAnIrohAddressIsNeverProbed() {
        assertFalse(IrohSweepPolicy.shouldProbe(canReach = false, msSinceReached = null, freshMs = 45_000L, onLan = false))
        assertFalse(IrohSweepPolicy.shouldProbe(canReach = false, msSinceReached = null, freshMs = 45_000L, onLan = true))
    }

    @Test
    fun theServedHoldIsNoLongerThanTheIdleGrace() {
        assertTrue(IrohWakePolicy.SERVED_KEEP_MS <= IrohWakePolicy.IDLE_GRACE_MS)
    }
}
