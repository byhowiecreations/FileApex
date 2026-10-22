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

class FriendsTransferTest {
    /** Two ends of an in-memory pipe; [cutAfter] bytes into a->b the pipe breaks. */
    private class Pipe(private val inbound: Channel<ByteArray>, private val outbound: Channel<ByteArray>, private val cutAfter: Long = Long.MAX_VALUE) : IrohStream {
        private var pending = ByteArray(0)
        private var written = 0L
        override suspend fun read(max: Int): ByteArray? {
            if (pending.isEmpty()) pending = inbound.receiveCatching().getOrNull() ?: return null
            val n = minOf(max, pending.size)
            return pending.copyOf(n).also { pending = pending.copyOfRange(n, pending.size) }
        }
        override suspend fun write(bytes: ByteArray) {
            if (written + bytes.size > cutAfter) { outbound.close(); throw java.io.IOException("cut") }
            written += bytes.size; outbound.send(bytes.copyOf())
        }
        override suspend fun finish() { outbound.close() }
        override fun abort() { outbound.close() }
    }

    private fun pair(cutSender: Long = Long.MAX_VALUE): Pair<IrohStream, IrohStream> {
        val a = Channel<ByteArray>(Channel.UNLIMITED); val b = Channel<ByteArray>(Channel.UNLIMITED)
        return Pipe(b, a, cutSender) to Pipe(a, b)
    }

    private val dir = Files.createTempDirectory("friends").toFile().also { it.deleteOnExit() }
    private fun source(size: Int) = File(dir, "my file.bin").also { it.writeBytes(Random(3).nextBytes(size)) }
    private val id = "ab".repeat(16)

    @Test fun acceptedFileLandsVerifiedInQuarantine() = runBlocking {
        val src = source(300_000); val q = File(dir, "q1")
        val (s, r) = pair()
        val rx = async { FriendsReceiver(q, 10_000_000) { true }.handle(r, "key1") }
        assertEquals(FriendSendResult.Done, FriendsSender.send(s, src, id))
        val res = rx.await() as FriendReceiveResult.Received
        assertTrue(src.readBytes().contentEquals(res.file.readBytes()))
        assertEquals(q.canonicalPath, res.file.parentFile.canonicalPath)
    }

    @Test fun refusedWritesNothing() = runBlocking {
        val q = File(dir, "q2"); val (s, r) = pair()
        val rx = async { FriendsReceiver(q, 10_000_000) { false }.handle(r, "key1") }
        assertEquals(FriendSendResult.Refused, FriendsSender.send(s, source(1000), id))
        assertEquals(FriendReceiveResult.Refused, rx.await())
        assertEquals(0, q.listFiles()?.size ?: 0)
    }

    @Test fun tooBigIsRefusedWithoutAsking() = runBlocking {
        var asked = false
        val (s, r) = pair()
        val rx = async { FriendsReceiver(File(dir, "q3"), 100) { asked = true; true }.handle(r, "k") }
        assertEquals(FriendSendResult.Refused, FriendsSender.send(s, source(1000), id))
        rx.await(); assertFalse(asked)
    }

    @Test fun interruptedTransferResumes() = runBlocking {
        val src = source(3_500_000); val q = File(dir, "q4")
        val (s1, r1) = pair(cutSender = 2_200_000)
        val rx1 = async { FriendsReceiver(q, 10_000_000) { true }.handle(r1, "k") }
        assertEquals(FriendSendResult.Failed, FriendsSender.send(s1, src, id))
        assertEquals(FriendReceiveResult.Interrupted, rx1.await())
        val part = q.listFiles()!!.single { it.name.endsWith(".part") }
        assertEquals(2L * FriendsWire.DATA_CHUNK, part.length())
        val (s2, r2) = pair()
        val rx2 = async { FriendsReceiver(q, 10_000_000) { true }.handle(r2, "k") }
        assertEquals(FriendSendResult.Done, FriendsSender.send(s2, src, id))
        assertTrue(src.readBytes().contentEquals((rx2.await() as FriendReceiveResult.Received).file.readBytes()))
    }

    @Test fun corruptedPieceIsRejectedAndNothingIsKept() = runBlocking {
        val q = File(dir, "q5"); val src = source(10_000)
        val (s, r) = pair()
        val rx = async { FriendsReceiver(q, 10_000_000) { true }.handle(r, "k") }
        // A sender whose piece does not match the hash it announced.
        FriendsWire.writeFrame(s, FriendsWire.encodeOffer(FriendOffer(id, 10_000, "x.bin")))
        FriendsWire.readFrame(s)
        s.write(byteArrayOf(FriendsWire.TAG_CHUNK.toByte()) + ByteArray(32)); s.write(src.readBytes()); s.finish()
        assertEquals(FriendReceiveResult.HashMismatch, rx.await())
        assertEquals(0L, q.listFiles()!!.singleOrNull { it.name.endsWith(".part") }?.length() ?: 0L)
        assertEquals(0, q.listFiles()!!.count { !it.name.endsWith(".part") })
    }

    @Test fun senderCancelDeletesTheReceiversPartFile() = runBlocking {
        val src = source(3_500_000); val q = File(dir, "q-cancel")
        val (s, r) = pair()
        val rx = async { FriendsReceiver(q, 10_000_000) { true }.handle(r, "k") }
        var pieces = 0
        val result = FriendsSender.send(s, src, id, onProgress = { if (it % FriendsWire.DATA_CHUNK == 0L) pieces++ }, shouldCancel = { pieces >= 1 })
        assertEquals(FriendSendResult.Canceled, result)
        assertEquals(FriendReceiveResult.CanceledBySender, rx.await())
        assertEquals(0, q.listFiles()!!.size)
    }

    @Test fun emptyFileArrives() = runBlocking {
        val src = source(0); val q = File(dir, "q-empty")
        val (s, r) = pair()
        val rx = async { FriendsReceiver(q, 10_000_000) { true }.handle(r, "k") }
        assertEquals(FriendSendResult.Done, FriendsSender.send(s, src, id))
        assertEquals(0L, (rx.await() as FriendReceiveResult.Received).file.length())
    }

    @Test fun hostileFramesAndNames() = runBlocking {
        val (s, r) = pair()
        val rx = async { FriendsReceiver(File(dir, "q6"), 100) { true }.handle(r, "k") }
        s.write(byteArrayOf(0x7f, 0, 0, 0)) // 2 GB frame length
        assertEquals(FriendReceiveResult.Malformed, rx.await())
        assertEquals("passwd", FriendsWire.safeName("../../etc/passwd"))
        assertEquals("a.txt", FriendsWire.safeName("C:\\x\\a.txt"))
        assertEquals("file_CON.txt", FriendsWire.safeName("CON.txt"))
        assertEquals("file", FriendsWire.safeName("..."))
        assertTrue(FriendsWire.safeName("a".repeat(500) + ".exe").length <= 120)
    }

    @Test fun receiverReportsStartProgressAndVerifying() = runBlocking {
        val src = source(300_000); val q = File(dir, "q-events")
        val (s, r) = pair()
        val events = java.util.Collections.synchronizedList(mutableListOf<FriendReceiveEvent>())
        val rx = async { FriendsReceiver(q, 10_000_000, onEvent = { events += it }) { true }.handle(r, "k") }
        val phases = mutableListOf<FriendSendPhase>()
        assertEquals(FriendSendResult.Done, FriendsSender.send(s, src, id, onPhase = { phases += it }))
        rx.await()
        assertTrue(events.first() is FriendReceiveEvent.Started)
        assertEquals(300_000L, (events.filterIsInstance<FriendReceiveEvent.Progress>().last()).received)
        assertTrue(events.last() is FriendReceiveEvent.Verifying)
        assertEquals(listOf(FriendSendPhase.Waiting, FriendSendPhase.Sending(0), FriendSendPhase.Verifying), phases)
    }

    @Test fun tooBigIsReportedAsDeclinedForSize() = runBlocking {
        val (s, r) = pair()
        val events = mutableListOf<FriendReceiveEvent>()
        val rx = async { FriendsReceiver(File(dir, "q-big"), 100, onEvent = { events += it }) { true }.handle(r, "k") }
        FriendsSender.send(s, source(1000), id); rx.await()
        assertEquals("size", (events.single() as FriendReceiveEvent.Declined).reason)
    }
}
