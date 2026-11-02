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

/** Pins what must stay true when several transfers share one friend link. */
class FriendsTransferConcurrencyTest {
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

    private val dir = Files.createTempDirectory("friends-conc").toFile().also { it.deleteOnExit() }
    private fun source(folder: String, size: Int, seed: Int) =
        File(File(dir, folder).also { it.mkdirs() }, "same name.bin").also { it.writeBytes(Random(seed).nextBytes(size)) }
    private fun tid(n: Int) = "%032x".format(n)

    @Test fun anOverflowSizedOfferIsRefusedBeforeAsking() = runBlocking {
        val (sender, receiver) = pair()
        var asked = false
        val receiving = async { FriendsReceiver(File(dir, "overflow"), Long.MAX_VALUE) { asked = true; false }.handle(receiver, "k") }
        FriendsWire.writeFrame(sender, FriendsWire.encodeOffer(FriendOffer(tid(90), Long.MAX_VALUE, "huge.bin")))
        assertEquals(FriendReceiveResult.Refused, withTimeout(5_000L) { receiving.await() })
        assertFalse("Adding the safety margin must not overflow into accepting a huge offer", asked)
    }

    @Test fun simultaneousReceivesReserveSharedDiskSpaceAndReleaseItAfterInterruption() = runBlocking {
        withTimeout(5_000L) {
            val size = 40L * 1024 * 1024
            val free = 100L * 1024 * 1024
            suspend fun announced(n: Int): Triple<IrohStream, Deferred<FriendReceiveResult>, Boolean> {
                val (sender, receiver) = pair()
                val receiving = async {
                    FriendsReceiver(File(dir, "space$n"), Long.MAX_VALUE, freeBytes = { free }) { true }.handle(receiver, "k")
                }
                FriendsWire.writeFrame(sender, FriendsWire.encodeOffer(FriendOffer(tid(n), size, "space.bin")))
                val accepted = FriendsWire.decodeReply(FriendsWire.readFrame(sender))!!.first
                return Triple(sender, receiving, accepted)
            }
            val first = announced(91)
            try {
                assertTrue(first.third)
                val second = announced(92)
                assertFalse("Both receives must not reserve the same free bytes", second.third)
                assertEquals(FriendReceiveResult.Refused, second.second.await())
            } finally { first.first.abort() }
            assertEquals(FriendReceiveResult.Interrupted, first.second.await())
            val next = announced(93)
            try { assertTrue("Interrupted work must return its reservation", next.third) }
            finally { next.first.abort() }
            assertEquals(FriendReceiveResult.Interrupted, next.second.await())
        }
    }

    @Test fun twoTransfersFromOneFriendWithTheSameFileNameBothArrive() = runBlocking {
        val q = File(dir, "q-two")
        val a = source("a", 2_500_000, 1); val b = source("b", 1_700_000, 2)
        val (s1, r1) = pair(); val (s2, r2) = pair()
        val rx1 = async { FriendsReceiver(q, 10_000_000) { true }.handle(r1, "friend") }
        val rx2 = async { FriendsReceiver(q, 10_000_000) { true }.handle(r2, "friend") }
        val tx1 = async { FriendsSender.send(s1, a, tid(1)) }
        val tx2 = async { FriendsSender.send(s2, b, tid(2)) }
        assertEquals(FriendSendResult.Done, tx1.await()); assertEquals(FriendSendResult.Done, tx2.await())
        val f1 = (rx1.await() as FriendReceiveResult.Received).file
        val f2 = (rx2.await() as FriendReceiveResult.Received).file
        assertNotEquals(f1.canonicalPath, f2.canonicalPath)
        assertTrue(a.readBytes().contentEquals(f1.readBytes()) || a.readBytes().contentEquals(f2.readBytes()))
        assertTrue(b.readBytes().contentEquals(f1.readBytes()) || b.readBytes().contentEquals(f2.readBytes()))
        assertEquals(0, q.listFiles()!!.count { it.name.endsWith(".part") })
    }

    @Test fun partFilesAreKeyedByFriendAndTransferId() {
        val names = setOf(
            FriendsReceiver.partName("friend1", tid(1)), FriendsReceiver.partName("friend1", tid(2)),
            FriendsReceiver.partName("friend2", tid(1))
        )
        assertEquals(3, names.size)
    }

    @Test fun anUnansweredOfferIsRefusedAfterTheTimeout() = runBlocking {
        val q = File(dir, "q-timeout"); val (s, r) = pair()
        val rx = async { FriendsReceiver(q, 10_000_000, acceptTimeoutMs = 150) { awaitCancellation() }.handle(r, "k") }
        assertEquals(FriendSendResult.Refused, FriendsSender.send(s, source("c", 1000, 3), tid(3)))
        assertEquals(FriendReceiveResult.Refused, rx.await())
        assertEquals(0, q.listFiles()?.size ?: 0)
    }

    @Test fun aLeftoverPartLongerThanTheOfferStartsOver() = runBlocking {
        val q = File(dir, "q-stale").also { it.mkdirs() }
        File(q, FriendsReceiver.partName("k", tid(4))).writeBytes(ByteArray(5_000_000))
        val src = source("d", 1_200_000, 4); val (s, r) = pair()
        val rx = async { FriendsReceiver(q, 10_000_000) { true }.handle(r, "k") }
        assertEquals(FriendSendResult.Done, FriendsSender.send(s, src, tid(4)))
        assertTrue(src.readBytes().contentEquals((rx.await() as FriendReceiveResult.Received).file.readBytes()))
    }

    @Test fun twoFilesWithOneNameKeepTheirOwnCopyWhenSentOneAfterAnother() = runBlocking {
        val q = File(dir, "q-seq"); val a = source("e", 5000, 5); val b = source("f", 6000, 6)
        val got = mutableListOf<File>()
        for ((n, f) in listOf(a, b).withIndex()) {
            val (s, r) = pair()
            val rx = async { FriendsReceiver(q, 10_000_000) { true }.handle(r, "k") }
            assertEquals(FriendSendResult.Done, FriendsSender.send(s, f, tid(10 + n)))
            got += (rx.await() as FriendReceiveResult.Received).file
        }
        assertEquals(2, got.map { it.name }.toSet().size)
        assertTrue(a.readBytes().contentEquals(got[0].readBytes()))
        assertTrue(b.readBytes().contentEquals(got[1].readBytes()))
    }

    @Test fun corruptedResumableBytesAreNeverPublishedAsACompleteFile() = runBlocking {
        val q = File(dir, "q-corrupt-resume").also { it.mkdirs() }
        val src = source("corrupt-resume", 1_500_000, 10)
        File(q, FriendsReceiver.partName("friend", tid(20))).writeBytes(ByteArray(FriendsWire.DATA_CHUNK))
        val (s, r) = pair()
        val rx = async { FriendsReceiver(q, 10_000_000) { true }.handle(r, "friend") }
        val sent = FriendsSender.send(s, src, tid(20))
        assertNotEquals(FriendSendResult.Done, sent)
        assertEquals(FriendReceiveResult.HashMismatch, rx.await())
        assertTrue(q.listFiles().orEmpty().isEmpty())
        assertEquals(1_500_000L, src.length())
    }

    @Test fun aSourceChangedAfterTheOfferIsRejectedByTheWholeFileChecksum() = runBlocking {
        val q = File(dir, "q-source-change")
        val src = source("source-change", 100_000, 11)
        val (s, r) = pair()
        val rx = async { FriendsReceiver(q, 10_000_000) { true }.handle(r, "friend") }
        val sent = FriendsSender.send(s, src, tid(21), onPhase = { phase ->
            if (phase is FriendSendPhase.Sending) src.writeBytes(ByteArray(100_000))
        })
        assertNotEquals(FriendSendResult.Done, sent)
        assertEquals(FriendReceiveResult.HashMismatch, rx.await())
        assertTrue(q.listFiles().orEmpty().isEmpty())
    }

    @Test fun legacyOffersStartFromZeroInsteadOfTrustingAnOldPartial() = runBlocking {
        val q = File(dir, "q-legacy-resume").also { it.mkdirs() }
        File(q, FriendsReceiver.partName("friend", tid(22))).writeBytes(ByteArray(FriendsWire.DATA_CHUNK))
        val (s, r) = pair()
        val rx = async { FriendsReceiver(q, 10_000_000) { true }.handle(r, "friend") }
        try {
            FriendsWire.writeFrame(s, FriendsWire.encodeOffer(FriendOffer(tid(22), 1_500_000, "legacy.bin")))
            assertEquals(0L, FriendsWire.decodeReply(FriendsWire.readFrame(s))!!.second)
        } finally { s.abort(); rx.cancelAndJoin() }
    }

    @Test fun cancellationAtVerificationDoesNotPublishAFile() = runBlocking {
        val q = File(dir, "q-verify-cancel")
        val src = source("verify-cancel", 100_000, 12)
        val (s, r) = pair()
        lateinit var receiving: Job
        val rx = async {
            try {
                FriendsReceiver(q, 10_000_000, onEvent = { if (it is FriendReceiveEvent.Verifying) receiving.cancel() }) { true }.handle(r, "friend")
            } finally { r.abort() }
        }
        receiving = rx
        FriendsSender.send(s, src, tid(23))
        rx.join()
        assertTrue(q.listFiles().orEmpty().none { !it.name.endsWith(".part") })
        assertTrue(src.exists())
    }

    @Test fun simultaneousOffersCannotWriteToTheSamePartialFile() = runBlocking {
        val q = File(dir, "q-duplicate-id")
        val src = source("duplicate-id", 100_000, 13)
        val offered = CompletableDeferred<Unit>()
        val accept = CompletableDeferred<Unit>()
        val (s1, r1) = pair()
        val rx1 = async { FriendsReceiver(q, 10_000_000) { offered.complete(Unit); accept.await(); true }.handle(r1, "friend") }
        val tx1 = async { FriendsSender.send(s1, src, tid(24)) }
        offered.await()
        val (s2, r2) = pair()
        val rx2 = async { FriendsReceiver(q, 10_000_000) { true }.handle(r2, "friend") }
        assertEquals(FriendSendResult.Refused, FriendsSender.send(s2, src, tid(24)))
        assertEquals(FriendReceiveResult.Refused, rx2.await())
        accept.complete(Unit)
        assertEquals(FriendSendResult.Done, tx1.await())
        assertTrue(src.readBytes().contentEquals((rx1.await() as FriendReceiveResult.Received).file.readBytes()))
    }
}
