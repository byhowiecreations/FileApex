package com.fileapex.drop

import com.fileapex.friends.transfer.FriendsWire
import com.fileapex.iroh.IrohStream
import com.fileapex.iroh.memoryLinkPair
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuestDropServerTest {
    @get:Rule val temp = TemporaryFolder()

    @Before fun clean() = com.fileapex.domain.transfer.TransferActivityGuard.reset()

    @After fun cleanAfter() = com.fileapex.domain.transfer.TransferActivityGuard.reset()

    private var clock = 5_000_000L
    private val drops = GuestDrops(now = { clock }, maxLive = 3)

    private fun fileOf(size: Int) = temp.newFile().apply { writeBytes(ByteArray(size) { (it * 7 % 251).toByte() }) }

    private fun newDrop(file: java.io.File, ttl: Long = 10 * 60_000L) =
        checkNotNull(drops.create(file.path, "movie.mp4", file.length(), ttl, file.lastModified()))

    private fun bounded(s: IrohStream): IrohStream = BoundedStream(s)

    private class Reply(val head: List<String>, val body: ByteArray)

    /** A browser stand-in: sends the request, reads the answer, then [take] body bytes (all of them by default). */
    private suspend fun fetch(stream: IrohStream, id: String, secret: String, take: Long? = null): Reply {
        FriendsWire.writeFrame(stream, GuestDropWire.encodeRequest(id, secret))
        val head = FriendsWire.readFrame(stream, 5_000L).decodeToString().split("\n")
        val out = ByteArrayOutputStream()
        if (head[0] == "ok") {
            val want = take ?: head[1].toLong()
            var got = 0L
            while (got < want) {
                val chunk = stream.read((want - got).coerceAtMost(64 * 1024).toInt()) ?: break
                out.write(chunk)
                got += chunk.size
            }
            if (take == null && got == want) FriendsWire.writeRaw(stream, GuestDropWire.RECEIPT)
        }
        return Reply(head, out.toByteArray())
    }

    /** Reads until the stream ends or the sender drops it (the in-memory stream reports a drop as a cancellation). */
    private suspend fun drain(s: IrohStream): Long {
        var total = 0L
        try {
            while (true) total += (s.read(64 * 1024) ?: break).size
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
        return total
    }

    private suspend fun session(id: String, secret: String, take: Long? = null): Pair<Reply, DropServe> = coroutineScope {
        val (client, host) = memoryLinkPair()
        val served = async(Dispatchers.IO) { GuestDropServer.serve(bounded(host.accept()!!.stream), drops, requestTimeoutMs = 2_000L) }
        val reply = fetch(bounded(client.open("peer-b")), id, secret, take)
        reply to served.await()
    }

    @Test fun theFileArrivesWholeAndTheLinkIsSpent() = runBlocking {
        withTimeout(30_000L) {
            val file = fileOf(700_000)
            val d = newDrop(file)
            val (reply, served) = session(d.id, d.secret)
            assertEquals(listOf("ok", "700000", "movie.mp4"), reply.head)
            assertArrayEquals(file.readBytes(), reply.body)
            assertEquals(DropServe.Served, served)
            val (again, _) = session(d.id, d.secret)
            assertEquals(listOf("no", "used"), again.head)
        }
    }

    @Test fun theSenderWaitsForTheVisitorsReceiptButTheLinkIsAlreadySpent() = runBlocking {
        withTimeout(30_000L) {
            val file = fileOf(200_000)
            val d = newDrop(file)
            val (client, host) = memoryLinkPair()
            val served = async(Dispatchers.IO) { GuestDropServer.serve(bounded(host.accept()!!.stream), drops, requestTimeoutMs = 2_000L) }
            val s = bounded(client.open("peer-b"))
            FriendsWire.writeFrame(s, GuestDropWire.encodeRequest(d.id, d.secret))
            FriendsWire.readFrame(s, 5_000L)
            var left = 200_000
            while (left > 0) left -= s.read(left)!!.size
            kotlinx.coroutines.delay(300)
            assertTrue("serve must still be waiting for the receipt", !served.isCompleted)
            assertEquals(listOf("no", "used"), session(d.id, d.secret).first.head)
            FriendsWire.writeRaw(s, GuestDropWire.RECEIPT)
            assertEquals(DropServe.Served, served.await())
        }
    }

    @Test fun aVisitorThatNeverSendsAReceiptDoesNotHoldTheSenderForever() = runBlocking {
        withTimeout(30_000L) {
            val d = newDrop(fileOf(1000))
            val (client, host) = memoryLinkPair()
            val served = async(Dispatchers.IO) { GuestDropServer.serve(bounded(host.accept()!!.stream), drops, requestTimeoutMs = 2_000L, receiptTimeoutMs = 300L) }
            val s = bounded(client.open("peer-b"))
            FriendsWire.writeFrame(s, GuestDropWire.encodeRequest(d.id, d.secret))
            FriendsWire.readFrame(s, 5_000L)
            var left = 1000
            while (left > 0) left -= s.read(left)!!.size
            assertEquals(DropServe.Served, served.await())
        }
    }

    @Test fun anEmptyFileIsServedAndSpendsTheLink() = runBlocking {
        withTimeout(30_000L) {
            val d = newDrop(fileOf(0))
            val (reply, served) = session(d.id, d.secret)
            assertEquals(listOf("ok", "0", "movie.mp4"), reply.head)
            assertEquals(DropServe.Served, served)
            assertEquals(listOf("no", "used"), session(d.id, d.secret).first.head)
        }
    }

    @Test fun aWrongSecretIsToldDeniedAndSendsNothing() = runBlocking {
        withTimeout(30_000L) {
            val d = newDrop(fileOf(1000))
            val (reply, served) = session(d.id, "0".repeat(32))
            assertEquals(listOf("no", "denied"), reply.head)
            assertEquals(DropServe.Refused(DropRefusal.Denied), served)
            assertTrue(session(d.id, d.secret).first.head[0] == "ok")
        }
    }

    @Test fun aSecondDownloaderIsToldBusyWhileTheFirstIsStillReceiving() = runBlocking {
        withTimeout(30_000L) {
            val file = fileOf(4_000_000)
            val d = newDrop(file)
            val (c1, h1) = memoryLinkPair("c1", "h1")
            val first = async(Dispatchers.IO) { GuestDropServer.serve(bounded(h1.accept()!!.stream), drops, requestTimeoutMs = 2_000L) }
            val s1 = bounded(c1.open("h1"))
            FriendsWire.writeFrame(s1, GuestDropWire.encodeRequest(d.id, d.secret))
            assertEquals("ok", FriendsWire.readFrame(s1, 5_000L).decodeToString().substringBefore("\n"))
            val (busy, refused) = session(d.id, d.secret)
            assertEquals(listOf("no", "busy"), busy.head)
            assertEquals(DropServe.Refused(DropRefusal.Busy), refused)
            s1.abort()
            first.join()
        }
    }

    @Test fun aBrowserThatLeavesBeforeTheEndDoesNotSpendTheLink() = runBlocking {
        withTimeout(30_000L) {
            val file = fileOf(4_000_000)
            val d = newDrop(file)
            val (client, host) = memoryLinkPair()
            val served = async(Dispatchers.IO) { GuestDropServer.serve(bounded(host.accept()!!.stream), drops, requestTimeoutMs = 2_000L) }
            val s = bounded(client.open("peer-b"))
            val reply = fetch(s, d.id, d.secret, take = 100_000L)
            assertEquals("ok", reply.head[0])
            s.abort()
            served.join()
            val retry = session(d.id, d.secret).first
            assertEquals("ok", retry.head[0])
            assertArrayEquals(file.readBytes(), retry.body)
        }
    }

    @Test fun theHardLimitCutsATransferThatIsStillRunning() = runBlocking {
        withTimeout(30_000L) {
            val file = fileOf(4_000_000)
            val d = newDrop(file, ttl = 60_000L)
            val (client, host) = memoryLinkPair()
            val served = async(Dispatchers.IO) { GuestDropServer.serve(bounded(host.accept()!!.stream), drops, requestTimeoutMs = 2_000L) }
            val s = bounded(client.open("peer-b"))
            FriendsWire.writeFrame(s, GuestDropWire.encodeRequest(d.id, d.secret))
            FriendsWire.readFrame(s, 5_000L)
            clock += 61_000L
            val total = drain(s)
            assertEquals(DropServe.Failed("time limit"), served.await())
            assertTrue(total < file.length())
            assertEquals(listOf("no", "expired"), session(d.id, d.secret).first.head)
        }
    }

    @Test fun aRevokedLinkStopsARunningTransfer() = runBlocking {
        withTimeout(30_000L) {
            val file = fileOf(4_000_000)
            val d = newDrop(file)
            val (client, host) = memoryLinkPair()
            val served = async(Dispatchers.IO) { GuestDropServer.serve(bounded(host.accept()!!.stream), drops, requestTimeoutMs = 2_000L) }
            val s = bounded(client.open("peer-b"))
            FriendsWire.writeFrame(s, GuestDropWire.encodeRequest(d.id, d.secret))
            FriendsWire.readFrame(s, 5_000L)
            drops.revoke(d.id)
            drain(s)
            assertEquals(DropServe.Failed("time limit"), served.await())
            assertEquals(listOf("no", "denied"), session(d.id, d.secret).first.head)
        }
    }

    @Test fun aFileThatChangedSizeIsNotServed() = runBlocking {
        withTimeout(30_000L) {
            val file = fileOf(1000)
            val d = newDrop(file)
            file.appendBytes(ByteArray(10))
            val (reply, served) = session(d.id, d.secret)
            assertEquals(listOf("no", "gone"), reply.head)
            assertEquals(DropServe.Failed("file changed"), served)
        }
    }

    @Test fun aMissingFileIsNotServed() = runBlocking {
        withTimeout(30_000L) {
            val file = fileOf(1000)
            val d = newDrop(file)
            file.delete()
            val (reply, _) = session(d.id, d.secret)
            assertEquals(listOf("no", "gone"), reply.head)
        }
    }

    @Test fun garbageInsteadOfARequestIsDroppedWithoutTouchingAnyLink() = runBlocking {
        withTimeout(30_000L) {
            val d = newDrop(fileOf(1000))
            val (client, host) = memoryLinkPair()
            val served = async(Dispatchers.IO) { GuestDropServer.serve(bounded(host.accept()!!.stream), drops, requestTimeoutMs = 2_000L) }
            FriendsWire.writeFrame(bounded(client.open("peer-b")), "hello\nthere".encodeToByteArray())
            assertEquals(DropServe.BadRequest, served.await())
            assertEquals("ok", session(d.id, d.secret).first.head[0])
        }
    }

    @Test fun silenceInsteadOfARequestIsDroppedAfterTheTimeout() = runBlocking {
        withTimeout(30_000L) {
            val (client, host) = memoryLinkPair()
            val served = async(Dispatchers.IO) { GuestDropServer.serve(bounded(host.accept()!!.stream), drops, requestTimeoutMs = 300L) }
            bounded(client.open("peer-b"))
            assertEquals(DropServe.BadRequest, served.await())
        }
    }

    @Test fun theRequestCarriesNothingButTheIdAndTheSecret() {
        val d = "a".repeat(32) to "b".repeat(32)
        val bytes = GuestDropWire.encodeRequest(d.first, d.second)
        assertEquals(d, GuestDropWire.decodeRequest(bytes))
        assertEquals(null, GuestDropWire.decodeRequest("v1\nzz\nyy".encodeToByteArray()))
        assertEquals(null, GuestDropWire.decodeRequest("v2\n${d.first}\n${d.second}".encodeToByteArray()))
        assertEquals(null, GuestDropWire.decodeRequest("v1\n${d.first}\n${d.second}\nextra".encodeToByteArray()))
    }

    @Test fun theAppCountsAServingDownloadAsATransferSoTheWakeAndWifiLocksAreHeld() = runBlocking {
        withTimeout(30_000L) {
            val guard = com.fileapex.domain.transfer.TransferActivityGuard
            val d = newDrop(fileOf(4_000_000))
            val (client, host) = memoryLinkPair()
            val served = async(Dispatchers.IO) { GuestDropServer.serve(bounded(host.accept()!!.stream), drops, requestTimeoutMs = 2_000L) }
            val s = bounded(client.open("peer-b"))
            assertTrue(!guard.isTransferActive())
            FriendsWire.writeFrame(s, GuestDropWire.encodeRequest(d.id, d.secret))
            FriendsWire.readFrame(s, 5_000L)
            kotlinx.coroutines.delay(300)
            assertTrue("a running download must count as a transfer", guard.isTransferActive())
            var left = 4_000_000
            while (left > 0) left -= s.read(left)!!.size
            FriendsWire.writeRaw(s, GuestDropWire.RECEIPT)
            assertEquals(DropServe.Served, served.await())
            assertTrue("a finished download must not", !guard.isTransferActive())
        }
    }

    @Test fun aRefusedRequestIsNotATransfer() = runBlocking {
        withTimeout(30_000L) {
            val d = newDrop(fileOf(10))
            session(d.id, "0".repeat(32))
            assertTrue(!com.fileapex.domain.transfer.TransferActivityGuard.isTransferActive())
        }
    }

    @Test fun theGrantedCallbackRunsOnceForAGrantedRequestAndNeverForARefusedOne() = runBlocking {
        withTimeout(30_000L) {
            val d = newDrop(fileOf(1000))
            var granted = 0
            suspend fun go(secret: String) = coroutineScope {
                val (client, host) = memoryLinkPair()
                val served = async(Dispatchers.IO) {
                    GuestDropServer.serve(bounded(host.accept()!!.stream), drops, requestTimeoutMs = 2_000L, receiptTimeoutMs = 300L) { granted++ }
                }
                fetch(bounded(client.open("peer-b")), d.id, secret)
                served.await()
            }
            go("0".repeat(32))
            assertEquals(0, granted)
            go(d.secret)
            assertEquals(1, granted)
        }
    }

    @Test fun aFileChangedSinceTheLinkWasMadeIsNotServed() = runBlocking {
        withTimeout(30_000L) {
            val file = fileOf(1000)
            val d = newDrop(file)
            file.setLastModified(file.lastModified() + 5_000L)
            val (reply, served) = session(d.id, d.secret)
            assertEquals(listOf("no", "gone"), reply.head)
            assertEquals(DropServe.Failed("file changed"), served)
        }
    }

    @Test fun aFileRewrittenToTheSameSizeWhileSendingIsNotFinished() = runBlocking {
        withTimeout(30_000L) {
            val file = fileOf(4_000_000)
            val d = newDrop(file)
            val (client, host) = memoryLinkPair()
            val served = async(Dispatchers.IO) { GuestDropServer.serve(bounded(host.accept()!!.stream), drops, requestTimeoutMs = 2_000L) }
            val s = bounded(client.open("peer-b"))
            FriendsWire.writeFrame(s, GuestDropWire.encodeRequest(d.id, d.secret))
            FriendsWire.readFrame(s, 5_000L)
            file.writeBytes(ByteArray(4_000_000) { 9 })
            file.setLastModified(file.lastModified() + 5_000L)
            drain(s)
            assertEquals(DropServe.Failed("file changed"), served.await())
            assertEquals(listOf("no", "gone"), session(d.id, d.secret).first.head)
        }
    }

    @Test fun theSpentCallbackRunsAtTheLastByteBeforeTheReceiptAndNeverForAFailedDownload() = runBlocking {
        withTimeout(30_000L) {
            val d = newDrop(fileOf(2000))
            val (client, host) = memoryLinkPair()
            var spent = 0
            val served = async(Dispatchers.IO) {
                GuestDropServer.serve(bounded(host.accept()!!.stream), drops, requestTimeoutMs = 2_000L, onSpent = { spent++ })
            }
            val s = bounded(client.open("peer-b"))
            FriendsWire.writeFrame(s, GuestDropWire.encodeRequest(d.id, d.secret))
            FriendsWire.readFrame(s, 5_000L)
            var left = 2000
            while (left > 0) left -= s.read(left)!!.size
            kotlinx.coroutines.delay(300)
            assertEquals("must run before the receipt", 1, spent)
            assertTrue(!served.isCompleted)
            FriendsWire.writeRaw(s, GuestDropWire.RECEIPT)
            served.await()
            assertEquals(1, spent)

            val failing = newDrop(fileOf(4_000_000))
            val (c2, h2) = memoryLinkPair("c2", "h2")
            var spentOnFailure = 0
            val served2 = async(Dispatchers.IO) {
                GuestDropServer.serve(bounded(h2.accept()!!.stream), drops, requestTimeoutMs = 2_000L, onSpent = { spentOnFailure++ })
            }
            val s2 = bounded(c2.open("h2"))
            FriendsWire.writeFrame(s2, GuestDropWire.encodeRequest(failing.id, failing.secret))
            FriendsWire.readFrame(s2, 5_000L)
            drops.revoke(failing.id)
            drain(s2)
            served2.await()
            assertEquals(0, spentOnFailure)
        }
    }
}
