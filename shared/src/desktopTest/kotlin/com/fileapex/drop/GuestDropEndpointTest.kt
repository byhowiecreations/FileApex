package com.fileapex.drop

import com.fileapex.friends.transfer.FriendsWire
import com.fileapex.iroh.IrohStream
import computer.iroh.BiStream
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointBuilder
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Two real Iroh endpoints on loopback: the sender's drop endpoint and a stand-in for a visitor. No relay. */
class GuestDropEndpointTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val visitors = mutableListOf<Endpoint>()
    private val drops = GuestDrops(maxLive = 3)

    @After fun tearDown() {
        scope.cancel()
        runBlocking { visitors.forEach { runCatching { it.shutdown() } } }
    }

    private suspend fun visitor(alpn: ByteArray = GuestDropIroh.ALPN): Endpoint {
        val b = EndpointBuilder()
        b.applyN0DisableRelay()
        b.alpns(listOf(alpn))
        b.bindAddr("127.0.0.1:0")
        return b.bind().also { visitors += it }
    }

    private fun raw(ep: GuestDropEndpoint): Endpoint = (ep as GuestDropIrohEndpoint).raw

    private fun dialAddr(ep: GuestDropEndpoint) = raw(ep).addr().let { EndpointAddr(it.id(), null, it.directAddresses()) }

    private fun serveAll(ep: GuestDropEndpoint) = scope.launch {
        while (true) {
            val stream = ep.accept() ?: return@launch
            launch { GuestDropServer.serve(stream, drops, requestTimeoutMs = 3_000L) }
        }
    }

    private suspend fun readAll(bi: BiStream, id: String, secret: String): Pair<List<String>, ByteArray> {
        val stream: IrohStream = object : IrohStream {
            override suspend fun read(max: Int) = bi.recv().read(max.toUInt()).takeIf { it.isNotEmpty() }
            override suspend fun write(bytes: ByteArray) = bi.send().writeAll(bytes)
            override suspend fun finish() = bi.send().finish()
            override fun abort() = Unit
        }
        FriendsWire.writeFrame(stream, GuestDropWire.encodeRequest(id, secret))
        val head = FriendsWire.readFrame(stream, 10_000L).decodeToString().split("\n")
        val out = ByteArrayOutputStream()
        if (head[0] == "ok") {
            var left = head[1].toLong()
            while (left > 0) {
                val chunk = stream.read(minOf(left, 65536L).toInt()) ?: break
                out.write(chunk)
                left -= chunk.size
            }
            if (left == 0L) FriendsWire.writeRaw(stream, GuestDropWire.RECEIPT)
        }
        return head to out.toByteArray()
    }

    @Test fun aVisitorPullsTheFileOnceThroughTheDropDoor() = runBlocking {
        withTimeout(60_000L) {
            val engine = checkNotNull(embeddedGuestDropEngine())
            val sender = engine.start(SecureRandom().generateSeed(32), useRelay = false)
            try {
                serveAll(sender)
                val file = temp.newFile().apply { writeBytes(ByteArray(3_000_000) { (it % 253).toByte() }) }
                val d = checkNotNull(drops.create(file.path, "pic.bin", file.length(), 600_000L))
                val connection = visitor().connect(dialAddr(sender), GuestDropIroh.ALPN)
                val (head, body) = readAll(connection.openBi(), d.id, d.secret)
                assertEquals(listOf("ok", "3000000", "pic.bin"), head)
                assertArrayEquals(file.readBytes(), body)
                val second = visitor().connect(dialAddr(sender), GuestDropIroh.ALPN)
                assertEquals(listOf("no", "used"), readAll(second.openBi(), d.id, d.secret).first)
            } finally {
                sender.close()
                assertFalse(sender.isAlive)
            }
        }
    }

    @Test fun closingTheEndpointRightAfterServingDoesNotCutTheVisitorsLastBytes() = runBlocking {
        withTimeout(90_000L) {
            val engine = checkNotNull(embeddedGuestDropEngine())
            val sender = engine.start(SecureRandom().generateSeed(32), useRelay = false)
            val file = temp.newFile().apply { writeBytes(ByteArray(30_000_000) { (it % 249).toByte() }) }
            val d = checkNotNull(drops.create(file.path, "big.bin", file.length(), 600_000L))
            val served = scope.async {
                val stream = sender.accept()!!
                GuestDropServer.serve(stream, drops, requestTimeoutMs = 3_000L).also { sender.close() }
            }
            val connection = visitor().connect(dialAddr(sender), GuestDropIroh.ALPN)
            val (head, body) = readAll(connection.openBi(), d.id, d.secret)
            assertEquals("ok", head[0])
            assertEquals(file.length().toInt(), body.size)
            assertArrayEquals(file.readBytes(), body)
            assertEquals(DropServe.Served, served.await())
        }
    }

    @Test fun theConnectionStaysOpenForAVisitorWhileItIsOnlyReadingAfterGarbageCollection() = runBlocking {
        withTimeout(90_000L) {
            val engine = checkNotNull(embeddedGuestDropEngine())
            val sender = engine.start(SecureRandom().generateSeed(32), useRelay = false)
            try {
                serveAll(sender)
                val file = temp.newFile().apply { writeBytes(ByteArray(8_000_000) { (it % 247).toByte() }) }
                val d = checkNotNull(drops.create(file.path, "slow.bin", file.length(), 600_000L))
                val connection = visitor().connect(dialAddr(sender), GuestDropIroh.ALPN)
                val bi = connection.openBi()
                val stream: IrohStream = object : IrohStream {
                    override suspend fun read(max: Int) = bi.recv().read(max.toUInt()).takeIf { it.isNotEmpty() }
                    override suspend fun write(bytes: ByteArray) = bi.send().writeAll(bytes)
                    override suspend fun finish() = bi.send().finish()
                    override fun abort() = Unit
                }
                FriendsWire.writeFrame(stream, GuestDropWire.encodeRequest(d.id, d.secret))
                FriendsWire.readFrame(stream, 10_000L)
                repeat(5) { System.gc(); kotlinx.coroutines.delay(300) }
                assertEquals("the sender must not close a connection it is still serving", null, connection.closeReason())
                var left = 8_000_000
                while (left > 0) left -= (stream.read(minOf(left, 65536)) ?: break).size
                assertEquals(0, left)
                FriendsWire.writeRaw(stream, GuestDropWire.RECEIPT)
            } finally {
                sender.close()
            }
        }
    }

    @Test fun overRealIrohTheSenderStillWaitsForTheReceiptAfterTheLastByte() = runBlocking {
        withTimeout(90_000L) {
            val engine = checkNotNull(embeddedGuestDropEngine())
            val sender = engine.start(SecureRandom().generateSeed(32), useRelay = false)
            try {
                val file = temp.newFile().apply { writeBytes(ByteArray(2_000_000) { (it % 241).toByte() }) }
                val d = checkNotNull(drops.create(file.path, "r.bin", file.length(), 600_000L))
                val served = scope.async { GuestDropServer.serve(sender.accept()!!, drops, requestTimeoutMs = 3_000L) }
                val connection = visitor().connect(dialAddr(sender), GuestDropIroh.ALPN)
                val bi = connection.openBi()
                val stream: IrohStream = object : IrohStream {
                    override suspend fun read(max: Int) = bi.recv().read(max.toUInt()).takeIf { it.isNotEmpty() }
                    override suspend fun write(bytes: ByteArray) = bi.send().writeAll(bytes)
                    override suspend fun finish() = bi.send().finish()
                    override fun abort() = Unit
                }
                FriendsWire.writeFrame(stream, GuestDropWire.encodeRequest(d.id, d.secret))
                FriendsWire.readFrame(stream, 10_000L)
                var left = 2_000_000
                while (left > 0) left -= (stream.read(minOf(left, 65536)) ?: break).size
                assertEquals(0, left)
                kotlinx.coroutines.delay(2_000)
                assertFalse("serve returned before the receipt", served.isCompleted)
                assertEquals("connection must still be open", null, connection.closeReason())
                FriendsWire.writeRaw(stream, GuestDropWire.RECEIPT)
                assertEquals(DropServe.Served, served.await())
            } finally {
                sender.close()
            }
        }
    }

    @Test fun theDropEndpointHasItsOwnIdentityAndSpeaksNothingElse() = runBlocking {
        withTimeout(60_000L) {
            val engine = checkNotNull(embeddedGuestDropEngine())
            val a = engine.start(SecureRandom().generateSeed(32), useRelay = false)
            val b = engine.start(SecureRandom().generateSeed(32), useRelay = false)
            try {
                assertNotEquals(a.id, b.id)
                assertTrue(Regex("[0-9a-f]{64}").matches(a.id))
                listOf("fileapex/http/1", "fileapex/friends/1").forEach { alpn ->
                    val refused = runCatching { visitor(alpn.encodeToByteArray()).connect(dialAddr(a), alpn.encodeToByteArray()) }
                    assertTrue("$alpn must be refused", refused.isFailure)
                }
            } finally {
                a.close()
                b.close()
            }
        }
    }

    @Test fun theEndpointPublishesNoDirectAddressInItsRelayOnlyForm() = runBlocking {
        withTimeout(60_000L) {
            val engine = checkNotNull(embeddedGuestDropEngine())
            val ep = engine.start(SecureRandom().generateSeed(32), useRelay = false)
            try {
                assertTrue(raw(ep).addr().directAddresses().all { it.startsWith("127.0.0.1") })
            } finally {
                ep.close()
            }
        }
    }

    @Test fun closingTheEndpointEndsTheAcceptLoop() = runBlocking {
        withTimeout(60_000L) {
            val engine = checkNotNull(embeddedGuestDropEngine())
            val ep = engine.start(SecureRandom().generateSeed(32), useRelay = false)
            val waiting = scope.async { ep.accept() }
            ep.close()
            assertEquals(null, waiting.await())
        }
    }

    @Test fun anEndpointThatCannotBeBuiltIsAnIOExceptionNotANativeOne() = runBlocking {
        withTimeout(30_000L) {
            val engine = checkNotNull(embeddedGuestDropEngine())
            val failure = runCatching { engine.start(ByteArray(5), useRelay = false) }.exceptionOrNull()
            assertTrue("expected IOException but got $failure", failure is java.io.IOException)
        }
    }

    @Test fun visitorsThatOpenAConnectionAndNeverAStreamDoNotBlockTheEndpoint() = runBlocking {
        withTimeout(90_000L) {
            val sender = GuestDropIrohEndpoint.bind(SecureRandom().generateSeed(32), useRelay = false, streamWaitMs = 500L)
            try {
                serveAll(sender)
                val idle = (1..9).map { visitor().connect(dialAddr(sender), GuestDropIroh.ALPN) }
                kotlinx.coroutines.delay(1_500)
                val file = temp.newFile().apply { writeBytes(ByteArray(10_000) { 3 }) }
                val d = checkNotNull(drops.create(file.path, "n.bin", file.length(), 600_000L))
                val connection = visitor().connect(dialAddr(sender), GuestDropIroh.ALPN)
                val (head, body) = readAll(connection.openBi(), d.id, d.secret)
                assertEquals("ok", head[0])
                assertEquals(10_000, body.size)
                assertTrue(idle.isNotEmpty())
            } finally {
                sender.close()
            }
        }
    }
}
