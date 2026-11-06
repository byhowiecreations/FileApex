package com.fileapex.membership

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainAckBookTest {
    private var clock = 1_000_000L
    private val reports = mutableListOf<String>()

    /** A head that starts with the change hash is "later on the same chain" for these tests. */
    private val book = ChainAckBook(now = { clock }, descends = { change, theirs -> theirs.startsWith(change) }, report = { reports += it })

    @Test
    fun theLastAckClosesTheChangeAndWritesOneLineWithCountsOnly() = runBlocking {
        book.expect("c1", setOf("amanda-secret-id", "mac", "phone"))
        book.heard("amanda-secret-id", "c1")
        book.heard("mac", "c1")
        assertTrue(reports.isEmpty())
        book.heard("phone", "c1")
        assertEquals(1, reports.size)
        assertTrue(reports.single().contains("3 of 3"))
        assertFalse("no device id in the log", reports.single().contains("secret"))
        assertEquals(0, book.open())
    }

    @Test
    fun aPeerShowingAnOlderChainIsNotAnAckAndALaterOneIs() = runBlocking {
        book.expect("c2", setOf("mac"))
        book.heard("mac", "c1")
        assertEquals(setOf("mac"), book.pending())
        book.heard("mac", "c2-later")
        assertEquals(emptySet<String>(), book.pending())
    }

    @Test
    fun aRemovedDeviceCountsOnAnyAnswerBecauseItWipesInsteadOfHoldingTheChain() = runBlocking {
        book.expect("c3", setOf("mac", "gone"), removed = setOf("gone"))
        book.heard("mac", "c3")
        assertEquals(setOf("gone"), book.pending())
        book.answered("gone")
        assertEquals(1, reports.size)
    }

    @Test
    fun pendingListsTheSilentPeersOfEveryOpenChange() = runBlocking {
        book.expect("c4", setOf("a", "b"))
        book.expect("c5", setOf("b", "c"))
        book.heard("a", "c4")
        assertEquals(setOf("b", "c"), book.pending())
    }

    @Test
    fun afterFiveMinutesTheChangeIsDroppedAndTheLaggingAreCounted() = runBlocking {
        book.expect("c6", setOf("a", "b", "c"))
        book.heard("a", "c6")
        clock += 4 * 60_000
        assertEquals(0, book.expire())
        assertEquals(1, book.open())
        clock += 61_000
        assertEquals(1, book.expire())
        assertEquals(0, book.open())
        assertTrue(reports.single().contains("2 of 3") || reports.single().contains("2 not yet"))
    }

    @Test
    fun theBookIsBounded() = runBlocking {
        repeat(ChainAckBook.MAX_CHANGES + 20) { book.expect("c$it", setOf("a")) }
        assertTrue(book.open() <= ChainAckBook.MAX_CHANGES)
    }
}
