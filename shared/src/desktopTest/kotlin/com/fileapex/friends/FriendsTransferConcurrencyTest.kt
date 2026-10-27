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
}
