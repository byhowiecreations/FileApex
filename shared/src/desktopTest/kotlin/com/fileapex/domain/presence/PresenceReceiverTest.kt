package com.fileapex.domain.presence

import com.fileapex.util.TimeUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceReceiverTest {
    private val now = TimeUtils.now()

    private fun receiver(book: ReachabilityBook) = PresenceReceiver(book, isPairedPeer = { it == "A" }, now = { now })

    @Test
    fun aGoodbyeFromAPairedPeerMarksItDown() = runBlocking {
        val book = ReachabilityBook()
        book.record(arrayOf("A"), now - 1_000L)
        assertTrue(receiver(book).onGoodbye("A"))
        assertTrue(book.isDown("A", lastSeenEpochMs = now - 1_000L))
        assertFalse("A" in book.onlineIds.value)
    }

    @Test
    fun aRepeatedGoodbyeChangesNothingMore() = runBlocking {
        val book = ReachabilityBook()
        book.record(arrayOf("A"), now - 1_000L)
        val r = receiver(book)
        r.onGoodbye("A")
        val after = book.epochMs.value
        r.onGoodbye("A")
        assertEquals(after, book.epochMs.value)
        assertTrue(book.isDown("A", lastSeenEpochMs = now - 1_000L))
    }

    @Test
    fun aLaterSuccessBringsThePeerBack() = runBlocking {
        val book = ReachabilityBook()
        receiver(book).onGoodbye("A")
        book.record(arrayOf("A"), now + 2_000L)
        assertFalse(book.isDown("A", lastSeenEpochMs = 0L))
        assertTrue("A" in book.onlineIds.value)
    }

    @Test
    fun anUnknownSenderIsIgnored() = runBlocking {
        val book = ReachabilityBook()
        assertFalse(receiver(book).onGoodbye("STRANGER"))
        assertFalse(book.isDown("STRANGER", lastSeenEpochMs = 0L))
    }

    @Test
    fun aBlankSenderIsIgnored() = runBlocking {
        assertFalse(receiver(ReachabilityBook()).onGoodbye("  "))
    }

    @Test
    fun onlyTheGoodbyeStateParsesFromTheWire() {
        assertEquals(PresenceSignal.Leaving, PresenceSignal.parse("leaving"))
        assertNull(PresenceSignal.parse("here"))
        assertNull(PresenceSignal.parse("LEAVING"))
        assertNull(PresenceSignal.parse("wipe"))
        assertNull(PresenceSignal.parse(""))
    }
}
