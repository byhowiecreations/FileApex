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

    @Test
    fun aClearedRouteIsForgottenSoTheLabelNeverShowsAStaleOne() {
        val book = ReachabilityBook()
        book.noteRoute("A", RouteKind.Lan)
        book.noteRoute("B", RouteKind.Iroh)
        book.clearRoute("A")
        assertEquals(mapOf("B" to RouteKind.Iroh), book.via.value)
        book.clearRoute("A")
        assertEquals(mapOf("B" to RouteKind.Iroh), book.via.value)
    }

    @Test
    fun aLanRouteThatNoCheckConfirmedWithinTheFreshWindowIsDropped() = runBlocking {
        val book = ReachabilityBook()
        book.record(arrayOf("A"), TimeUtils.now() - 10 * 60_000L, RouteKind.Lan)
        book.dropStaleLanRoute("A", 45_000L)
        assertTrue(book.via.value.isEmpty())
    }

    @Test
    fun aLanRouteConfirmedJustNowIsKept() = runBlocking {
        val book = ReachabilityBook()
        book.record(arrayOf("A"), TimeUtils.now(), RouteKind.Lan)
        book.dropStaleLanRoute("A", 45_000L)
        assertEquals(mapOf("A" to RouteKind.Lan), book.via.value)
    }

    @Test
    fun onlyALanRouteIsEverDroppedForStaleness() = runBlocking {
        val book = ReachabilityBook()
        book.record(arrayOf("A"), TimeUtils.now() - 10 * 60_000L, RouteKind.Iroh)
        book.record(arrayOf("B"), TimeUtils.now() - 10 * 60_000L, RouteKind.Tailnet)
        book.dropStaleLanRoute("A", 45_000L)
        book.dropStaleLanRoute("B", 45_000L)
        assertEquals(mapOf("A" to RouteKind.Iroh, "B" to RouteKind.Tailnet), book.via.value)
    }

    @Test
    fun aRequestArrivingOverIrohNamesIrohWhenNoRouteIsKnownOrIrohIsAlreadyKnown() {
        val book = ReachabilityBook()
        book.noteInboundIroh("A")
        assertEquals(mapOf("A" to RouteKind.Iroh), book.via.value)
        book.noteInboundIroh("A")
        assertEquals(mapOf("A" to RouteKind.Iroh), book.via.value)
    }

    @Test
    fun aRequestOverIrohNeverOverridesALanOrTailscaleRouteThatIsStillKnown() {
        val book = ReachabilityBook()
        book.noteRoute("A", RouteKind.Lan)
        book.noteRoute("B", RouteKind.Tailnet)
        book.noteInboundIroh("A")
        book.noteInboundIroh("B")
        assertEquals(mapOf("A" to RouteKind.Lan, "B" to RouteKind.Tailnet), book.via.value)
    }

    private val ttl = 11 * 60_000L

    @Test
    fun aRouteNotConfirmedWithinTheLeaseExpires() {
        val book = ReachabilityBook()
        book.noteRoute("A", RouteKind.Lan, atMs = 1_000_000L)
        assertEquals(false, book.expireRoutes(ttl, nowMs = 1_000_000L + ttl - 1))
        assertEquals(mapOf("A" to RouteKind.Lan), book.via.value)
        assertEquals(true, book.expireRoutes(ttl, nowMs = 1_000_000L + ttl))
        assertTrue(book.via.value.isEmpty())
    }

    @Test
    fun confirmingTheSameRouteAgainRenewsItsLease() {
        val book = ReachabilityBook()
        book.noteRoute("A", RouteKind.Lan, atMs = 1_000_000L)
        book.noteRoute("A", RouteKind.Lan, atMs = 1_000_000L + 8 * 60_000L)
        assertEquals(false, book.expireRoutes(ttl, nowMs = 1_000_000L + 15 * 60_000L))
        assertEquals(mapOf("A" to RouteKind.Lan), book.via.value)
    }

    @Test
    fun theWatcherIsToldWhenTheNextLeaseEnds() {
        val book = ReachabilityBook()
        assertEquals(null, book.routeExpiryWaitMs(ttl, nowMs = 1_000_000L))
        book.noteRoute("A", RouteKind.Lan, atMs = 1_000_000L)
        book.noteRoute("B", RouteKind.Iroh, atMs = 1_000_000L + 3 * 60_000L)
        assertEquals(ttl - 60_000L + 1_000L, book.routeExpiryWaitMs(ttl, nowMs = 1_000_000L + 60_000L))
    }

    @Test
    fun aClearedRouteLeavesNoLeaseBehind() {
        val book = ReachabilityBook()
        book.noteRoute("A", RouteKind.Lan, atMs = 1_000_000L)
        book.clearRoute("A")
        assertEquals(null, book.routeExpiryWaitMs(ttl, nowMs = 1_000_000L))
    }
}
