package com.fileapex.friends

import com.fileapex.friends.transfer.*
import com.fileapex.iroh.IrohStream
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class FriendsBatchTest {
    private class Pipe(private val inbound: Channel<ByteArray>, private val outbound: Channel<ByteArray>) : IrohStream {
        private var pending = ByteArray(0)
        override suspend fun read(max: Int): ByteArray? {
            if (pending.isEmpty()) pending = inbound.receiveCatching().getOrNull() ?: return null
            val n = minOf(max, pending.size)
            return pending.copyOf(n).also { pending = pending.copyOfRange(n, pending.size) }
        }
        override suspend fun write(bytes: ByteArray) { outbound.send(bytes.copyOf()) }
        override suspend fun finish() { outbound.close() }
        override fun abort() { outbound.close() }
    }

    private fun pair(): Pair<IrohStream, IrohStream> {
        val a = Channel<ByteArray>(Channel.UNLIMITED); val b = Channel<ByteArray>(Channel.UNLIMITED)
        return Pipe(b, a) to Pipe(a, b)
    }

    private val dir = Files.createTempDirectory("friends-batch").toFile().also { it.deleteOnExit() }
    private val bid = "a1".repeat(16)
    private fun tid(n: Int) = "%032x".format(n)
    private fun batch(count: Int = 3, total: Long = 3000, id: String = bid) = FriendBatch(id, count, total, listOf("a.txt", "b.txt"))
    private fun offer(n: Int, size: Long = 1000, batch: String = bid) = FriendOffer(tid(n), size, "f$n.bin", batchId = batch)

    // wire

    @Test fun batchRoundTrips() {
        val b = batch()
        assertEquals(b, FriendsBatchWire.decode(FriendsBatchWire.encode(b)))
    }

    @Test fun hostileBatchFramesAreRejected() {
        fun frame(vararg lines: String) = lines.joinToString("\n").encodeToByteArray()
        assertNull(FriendsBatchWire.decode(ByteArray(0)))
        assertNull(FriendsBatchWire.decode(frame("b2", bid, "3", "3000")))
        assertNull(FriendsBatchWire.decode(frame("b1", "short", "3", "3000")))
        assertNull(FriendsBatchWire.decode(frame("b1", bid, "0", "3000")))
        assertNull(FriendsBatchWire.decode(frame("b1", bid, "${FriendsBatchWire.MAX_FILES + 1}", "3000")))
        assertNull(FriendsBatchWire.decode(frame("b1", bid, "3", "-1")))
        assertNull(FriendsBatchWire.decode(frame("b1", bid, "x", "3000")))
    }

    @Test fun namesAreCleanedAndLimited() {
        val raw = frame("b1", bid, "9", "10", "../../etc/passwd", "a\u0007b", "c", "d", "e")
        val got = FriendsBatchWire.decode(raw)!!
        assertEquals(FriendsBatchWire.MAX_NAMES, got.firstNames.size)
        assertTrue(got.firstNames.none { it.contains('/') || it.contains('\u0007') })
    }

    private fun frame(vararg lines: String) = lines.joinToString("\n").encodeToByteArray()

    @Test fun offerCarriesTheBatchIdOnlyWhenSet() {
        val plain = FriendsWire.decodeOffer(FriendsWire.encodeOffer(FriendOffer(tid(1), 5, "x.bin")))!!
        assertEquals("", plain.batchId)
        assertTrue(FriendsWire.encodeOffer(FriendOffer(tid(1), 5, "x.bin")).decodeToString().startsWith("v2\n"))
        val tagged = FriendsWire.decodeOffer(FriendsWire.encodeOffer(offer(1)))!!
        assertEquals(bid, tagged.batchId)
        assertNull(FriendsWire.decodeOffer("v3\n${tid(1)}\n5\n1048576\nnot-a-batch-id\nx.bin".encodeToByteArray()))
    }

    // grants

    @Test fun aGrantAcceptsMatchingOffersUntilTheCountIsUsed() {
        val g = FriendBatchGrants(); g.grant("k", batch(count = 2, total = 5000))
        assertTrue(g.allows("k", offer(1))); assertTrue(g.allows("k", offer(2)))
        assertFalse(g.allows("k", offer(3)))
    }

    @Test fun aGrantStopsWhenTheBytesAreUsed() {
        val g = FriendBatchGrants(); g.grant("k", batch(count = 5, total = 1500))
        assertTrue(g.allows("k", offer(1, 1000)))
        assertFalse(g.allows("k", offer(2, 1000)))
        assertTrue(g.allows("k", offer(3, 500)))
    }

    @Test fun anInterruptedFileCanBeOfferedAgainWithoutUsingTheGrantTwice() {
        val g = FriendBatchGrants(); g.grant("k", batch(count = 1, total = 1000))
        assertTrue(g.allows("k", offer(1))); assertTrue(g.allows("k", offer(1)))
        assertFalse(g.allows("k", offer(2)))
    }

    @Test fun aGrantBelongsToOneFriendAndOneBatch() {
        val g = FriendBatchGrants(); g.grant("k", batch())
        assertFalse(g.allows("other", offer(1)))
        assertFalse(g.allows("k", offer(1, batch = "b2".repeat(16))))
        assertFalse(g.allows("k", offer(1, batch = "")))
    }

    @Test fun aGrantExpires() {
        var now = 0L
        val g = FriendBatchGrants(now = { now }, ttlMs = 1000); g.grant("k", batch())
        now = 999; assertTrue(g.allows("k", offer(1)))
        now = 1001; assertFalse(g.allows("k", offer(2)))
    }

    @Test fun revokeRemovesEveryGrantOfAFriend() {
        val g = FriendBatchGrants(); g.grant("k", batch()); g.grant("k", batch(id = "c3".repeat(16))); g.grant("o", batch(id = "d4".repeat(16)))
        g.revoke("k")
        assertFalse(g.allows("k", offer(1))); assertFalse(g.allows("k", offer(1, batch = "c3".repeat(16))))
        assertTrue(g.allows("o", offer(1, batch = "d4".repeat(16))))
    }

    @Test fun grantsAreBounded() {
        val g = FriendBatchGrants()
        repeat(FriendBatchGrants.MAX_GRANTS + 20) { g.grant("k", batch(id = "%032x".format(it + 1))) }
        assertTrue(g.size() <= FriendBatchGrants.MAX_GRANTS)
    }

    // negotiation

    private fun receiver(
        decide: suspend (FriendBatch) -> Boolean, free: Long = Long.MAX_VALUE, grants: FriendBatchGrants = FriendBatchGrants(), acceptTimeoutMs: Long = 120_000
    ) = FriendBatchReceiver(grants, decide, { free }, acceptTimeoutMs)

    @Test fun acceptedBatchOpensAGrant() = runBlocking {
        val (s, r) = pair(); val grants = FriendBatchGrants()
        val rx = async { receiver({ true }, grants = grants).handle("k", r) }
        assertEquals(BatchAnswer.Accepted, FriendBatchNegotiation.announce(s, batch()))
        rx.await()
        assertTrue(grants.allows("k", offer(1)))
    }

    @Test fun refusedBatchOpensNothing() = runBlocking {
        val (s, r) = pair(); val grants = FriendBatchGrants()
        val rx = async { receiver({ false }, grants = grants).handle("k", r) }
        assertEquals(BatchAnswer.Refused, FriendBatchNegotiation.announce(s, batch()))
        rx.await()
        assertFalse(grants.allows("k", offer(1)))
    }

    @Test fun anUnansweredBatchIsRefusedAfterTheTimeout() = runBlocking {
        val (s, r) = pair()
        val rx = async { receiver({ awaitCancellation() }, acceptTimeoutMs = 150).handle("k", r) }
        assertEquals(BatchAnswer.Refused, FriendBatchNegotiation.announce(s, batch()))
        rx.await()
    }

    @Test fun aBatchBiggerThanTheFreeSpaceIsRefusedWithoutAsking() = runBlocking {
        var asked = false
        val (s, r) = pair()
        val rx = async { receiver({ asked = true; true }, free = 1000).handle("k", r) }
        assertEquals(BatchAnswer.Refused, FriendBatchNegotiation.announce(s, batch(total = 10_000_000)))
        rx.await(); assertFalse(asked)
    }

    @Test fun aReceiverThatDoesNotKnowBatchesMeansUnsupported() = runBlocking {
        val (s, r) = pair()
        r.abort()
        assertEquals(BatchAnswer.Unsupported, FriendBatchNegotiation.announce(s, batch()))
    }

    @Test fun aGarbageAnnouncementIsDroppedWithoutAGrant() = runBlocking {
        val (s, r) = pair(); val grants = FriendBatchGrants()
        val rx = async { receiver({ true }, grants = grants).handle("k", r) }
        FriendsWire.writeFrame(s, "garbage".encodeToByteArray())
        rx.await()
        assertEquals(0, grants.size())
    }

    // sender side

    @Test fun senderAsksOnceForManyFiles() = runBlocking {
        var opened = 0
        val sb = FriendSendBatch(batch()) { opened++; val (s, r) = pair(); launch { receiver({ true }).handle("k", r) }; s }
        val ids = (1..4).map { async { sb.id() } }.awaitAll()
        assertEquals(1, opened)
        assertTrue(ids.all { it == bid })
    }

    @Test fun senderFallsBackToSeparateOffersForAnOldReceiver() = runBlocking {
        val sb = FriendSendBatch(batch()) { val (s, r) = pair(); r.abort(); s }
        assertEquals("", sb.id())
    }

    @Test fun senderGetsNullWhenTheFriendSaysNo() = runBlocking {
        val sb = FriendSendBatch(batch()) { val (s, r) = pair(); launch { receiver({ false }).handle("k", r) }; s }
        assertNull(sb.id())
    }

    @Test fun senderFallsBackWhenTheConnectionCannotBeOpened() = runBlocking {
        val sb = FriendSendBatch(batch()) { throw java.io.IOException("down") }
        assertEquals("", sb.id())
    }

    // end to end

    @Test fun grantedFilesArriveWithoutAskingAgain() = runBlocking {
        val grants = FriendBatchGrants(); grants.grant("k", batch(count = 2, total = 6000))
        val q = File(dir, "q-e2e"); var asked = 0
        suspend fun recv(s: IrohStream) = FriendsReceiver(q, 10_000_000) { o -> if (grants.allows("k", o)) true else { asked++; false } }.handle(s, "k")
        for (n in 1..2) {
            val f = File(dir, "src$n").also { it.writeBytes(Random(n).nextBytes(2500)) }
            val (s, r) = pair(); val rx = async { recv(r) }
            assertEquals(FriendSendResult.Done, FriendsSender.send(s, f, tid(n), batchId = bid))
            assertTrue(rx.await() is FriendReceiveResult.Received)
        }
        val f3 = File(dir, "src3").also { it.writeBytes(ByteArray(10)) }
        val (s, r) = pair(); val rx = async { recv(r) }
        assertEquals(FriendSendResult.Refused, FriendsSender.send(s, f3, tid(3), batchId = bid))
        rx.await()
        assertEquals(1, asked)
    }
}
