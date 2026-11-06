package com.fileapex.membership

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainStateWatchTest {
    private val headA = "a".repeat(64)
    private val headB = "b".repeat(64)
    private val headC = "c".repeat(64)

    private fun headOf(n: Int) = n.toString(16).padEnd(64, '0')

    private fun field(seq: Long, head: String, profile: String = "00000001") = ChainStateField.of(seq, head, profile)

    private class Rig(var mine: ChainStateField, var answers: Boolean = true, var adopt: ChainStateField? = null) {
        var clock = 1_000_000L
        val pulled = mutableListOf<String>()
        val watch = ChainStateWatch(
            own = { mine },
            pull = { peer ->
                pulled += peer
                if (answers) adopt?.let { mine = it }
                answers
            },
            now = { clock },
            jitterMs = { 0L }
        )
    }

    private fun rig(seq: Long = 5, head: String = "a".repeat(64)) = Rig(ChainStateField.of(seq, head, "00000001"))

    @Test
    fun anEqualStateChangesNothing() = runBlocking {
        val r = rig()
        r.watch.note("p", field(5, headA))
        r.watch.note("p", field(5, headA))
        assertTrue(r.pulled.isEmpty())
        assertEquals(2L, r.watch.counters().equalReads)
    }

    @Test
    fun aPeerAheadIsPulledOnceAndNotAgainWhileItsStateStaysTheSame() = runBlocking {
        val r = rig().also { it.adopt = field(6, headB) }
        r.watch.note("p", field(6, headB))
        assertEquals(listOf("p"), r.pulled)
        r.watch.note("p", field(6, headB))
        assertEquals(listOf("p"), r.pulled)
    }

    @Test
    fun aPeerBehindIsNotPulled() = runBlocking {
        val r = rig()
        r.watch.note("p", field(4, headB))
        assertTrue(r.pulled.isEmpty())
    }

    @Test
    fun aPeerWithNoChainIsNeverASource() = runBlocking {
        val r = rig()
        r.watch.note("p", field(0, ""))
        assertTrue(r.pulled.isEmpty())
    }

    @Test
    fun weWithNoChainPullFromAnyPeerAbove() = runBlocking {
        val r = Rig(field(0, "")).also { it.adopt = field(3, headB) }
        r.watch.note("p", field(3, headB))
        assertEquals(listOf("p"), r.pulled)
    }

    @Test
    fun aForkIsPulledOncePerDistinctHeadThenBacksOffOnTheTable() = runBlocking {
        val r = rig()
        r.watch.note("p", field(5, headB))
        assertEquals(1, r.pulled.size)
        r.clock += 11_000
        r.watch.note("p", field(5, headB))
        assertEquals("still unequal and inside the first minute", 1, r.pulled.size)
        r.clock += 61_000
        r.watch.note("p", field(5, headB))
        assertEquals("after 1 minute", 2, r.pulled.size)
        r.clock += 4 * 60_000
        r.watch.note("p", field(5, headB))
        assertEquals("5 minutes not yet over since the second pull", 2, r.pulled.size)
        r.clock += 61_000
        r.watch.note("p", field(5, headB))
        assertEquals("after 5 minutes", 3, r.pulled.size)
        r.clock += 29 * 60_000
        r.watch.note("p", field(5, headB))
        assertEquals(3, r.pulled.size)
        r.clock += 2 * 60_000
        r.watch.note("p", field(5, headB))
        assertEquals("after 30 minutes", 4, r.pulled.size)
        r.clock += 61 * 60_000
        r.watch.note("p", field(5, headB))
        assertEquals("then hourly", 5, r.pulled.size)
        r.clock += 11_000
        r.watch.note("p", field(5, headC))
        assertEquals("a new distinct head pulls at once", 6, r.pulled.size)
    }

    @Test
    fun noPeerIsPulledMoreThanOnceEveryTenSeconds() = runBlocking {
        val r = rig()
        r.watch.note("p", field(6, headB))
        r.clock += 5_000
        r.watch.note("p", field(7, headC))
        assertEquals(1, r.pulled.size)
        r.clock += 6_000
        r.watch.note("p", field(7, headC))
        assertEquals(2, r.pulled.size)
    }

    @Test
    fun aPeerWhoseStateChangesMoreThanSixTimesAnHourIsFlaggedAndOnlyBackedOff() = runBlocking {
        val r = rig(seq = 100)
        repeat(8) { n ->
            r.watch.note("p", field(100, headOf(n + 1)))
            r.clock += 12_000
        }
        assertTrue(r.watch.counters().flapping.contains("p"))
        val before = r.pulled.size
        r.watch.note("p", field(100, headOf(99)))
        assertEquals(before, r.pulled.size)
    }

    @Test
    fun twoPullsAtMostRunAtOnce() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val running = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val watch = ChainStateWatch(
            own = { field(5, headA) },
            pull = { peak.accumulateAndGet(running.incrementAndGet()) { x, y -> maxOf(x, y) }; gate.await(); running.decrementAndGet(); true },
            now = { 1_000_000L },
            jitterMs = { 0L }
        )
        val jobs = (1..4).map { n -> launch(Dispatchers.Default) { watch.note("p$n", field(6, headOf(n))) } }
        delay(300)
        gate.complete(Unit)
        jobs.joinAll()
        assertEquals(2, peak.get())
    }

    @Test
    fun aPeerHeardRecentlyCountsAsAnsweringWithoutAPull() = runBlocking {
        val r = rig()
        r.watch.note("p", field(5, headA))
        val answering = r.watch.tickPeers(listOf("p", "q"))
        assertTrue("p" in answering)
        assertEquals(listOf("q"), r.pulled)
        assertTrue("q" in answering)
    }

    @Test
    fun aPeerWithNoStateIsPulledOnTheTableAndHeardAfterASuccess() = runBlocking {
        val r = rig()
        assertEquals(setOf("q"), r.watch.tickPeers(listOf("q")))
        assertEquals(1, r.pulled.size)
        r.clock += 60_000
        r.watch.tickPeers(listOf("q"))
        assertEquals("heard a minute ago: no pull", 1, r.pulled.size)
        r.clock += 12 * 60_000
        r.watch.tickPeers(listOf("q"))
        assertEquals("not heard for the contact window: pulled again", 2, r.pulled.size)
    }

    @Test
    fun anUnreachablePeerIsRetriedOnTheTableAndIsNotAnswering() = runBlocking {
        val r = rig().also { it.answers = false }
        assertFalse("q" in r.watch.tickPeers(listOf("q")))
        r.clock += 30_000
        r.watch.tickPeers(listOf("q"))
        assertEquals(1, r.pulled.size)
        r.clock += 40_000
        r.watch.tickPeers(listOf("q"))
        assertEquals(2, r.pulled.size)
        r.clock += 4 * 60_000
        r.watch.tickPeers(listOf("q"))
        assertEquals(2, r.pulled.size)
        r.clock += 90_000
        r.watch.tickPeers(listOf("q"))
        assertEquals(3, r.pulled.size)
    }

    @Test
    fun theSafetyNetPullsEveryPeerEverySixHoursAndCountsWhatItHealed() = runBlocking {
        val r = rig().also { it.adopt = field(6, headB) }
        assertTrue(r.watch.safetyNetDue())
        r.watch.runSafetyNet(listOf("p", "q"))
        assertEquals(listOf("p", "q"), r.pulled)
        assertEquals(1L, r.watch.counters().heals)
        assertFalse(r.watch.safetyNetDue())
        r.clock += 6 * 3_600_000L + 1
        assertTrue(r.watch.safetyNetDue())
    }

    @Test
    fun theSafetyNetWaitsForAPeerToBeOnlineInsteadOfSpendingItself() = runBlocking {
        val r = rig()
        r.watch.runSafetyNet(emptyList())
        assertTrue("nobody was asked, so the net is still due", r.watch.safetyNetDue())
        r.watch.runSafetyNet(listOf("p"))
        assertFalse(r.watch.safetyNetDue())
    }

    @Test
    fun aPeerThatStopsAnsweringHealthIsAskedByTheNextTickWhateverItsLastContact() = runBlocking {
        val r = rig()
        r.watch.note("p", field(5, headA))
        r.watch.tickPeers(listOf("p"))
        assertTrue("heard a moment ago: no pull", r.pulled.isEmpty())
        r.watch.unreached("p")
        r.watch.tickPeers(listOf("p"))
        assertEquals(listOf("p"), r.pulled)
    }

    @Test
    fun aPeerHeardLongAgoIsNotJustLostSoAFailedProbeKeepsItOnTheTable() = runBlocking {
        val r = rig()
        r.watch.note("p", field(5, headA))
        r.clock += 13 * 60_000L
        r.answers = false
        r.watch.tickPeers(listOf("p"))
        val before = r.pulled.size
        r.watch.unreached("p")
        r.watch.tickPeers(listOf("p"))
        assertEquals("heard 13 minutes ago and failing since: the table holds", before, r.pulled.size)
    }

    @Test
    fun aPeerLongAwayStaysOnTheTableAndOnlyOneThatJustStoppedAnsweringIsAskedAtOnce() = runBlocking {
        val r = rig().also { it.answers = false }
        repeat(3) { r.watch.tickPeers(listOf("q")); r.clock += 61 * 60_000L }
        r.watch.tickPeers(listOf("q"))
        val before = r.pulled.size
        r.watch.unreached("q")
        r.watch.tickPeers(listOf("q"))
        assertEquals("a peer already away is not asked again by a failed probe: the table holds", before, r.pulled.size)
        r.answers = true
        r.clock += 61 * 60_000L
        r.watch.tickPeers(listOf("q"))
        r.watch.note("q", field(5, headA))
        r.watch.unreached("q")
        r.clock += 1_000
        r.watch.tickPeers(listOf("q"))
        assertTrue("one that was just heard is asked at the next tick", r.pulled.size > before + 1)
    }

    @Test
    fun tableOfEntriesStaysBounded() = runBlocking {
        val r = rig()
        repeat(500) { n -> r.watch.note("peer$n", field(5, headA)) }
        assertTrue(r.watch.trackedPeers() <= ChainStateWatch.MAX_PEERS)
    }
}
