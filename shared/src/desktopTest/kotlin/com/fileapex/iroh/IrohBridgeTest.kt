package com.fileapex.iroh

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohBridgeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = mutableListOf<ServerSocket>()
    private val bridges = mutableListOf<IrohBridge>()

    @After
    fun tearDown() {
        runBlocking { bridges.forEach { it.stop() } }
        servers.forEach { runCatching { it.close() } }
        scope.cancel()
    }

    /** Replies "ok:" plus whatever the client sent, once the client has finished sending. */
    private fun startEchoServer(connections: AtomicInteger = AtomicInteger()): Int {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        servers += server
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                connections.incrementAndGet()
                thread(isDaemon = true) {
                    socket.use {
                        val body = it.getInputStream().readBytes()
                        it.getOutputStream().write("ok:".encodeToByteArray() + body)
                        it.getOutputStream().flush()
                    }
                }
            }
        }
        return server.localPort
    }

    /** Replies with the SHA-256 of everything the client sent. */
    private fun startHashServer(): Int {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        servers += server
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use {
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        val input = it.getInputStream()
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                        }
                        it.getOutputStream().write(digest.digest().joinToString("") { b -> "%02x".format(b) }.encodeToByteArray())
                        it.getOutputStream().flush()
                    }
                }
            }
        }
        return server.localPort
    }

    private fun bridge(
        link: IrohLink,
        serverPort: Int,
        allowed: Set<String>,
        maxStreams: Int = 64
    ): IrohBridge {
        val bridge = IrohBridge(link, serverPort, { it in allowed }, scope, maxConcurrentStreams = maxStreams)
        bridge.start()
        bridges += bridge
        return bridge
    }

    private fun request(port: Int, payload: ByteArray): ByteArray =
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 15_000
            socket.getOutputStream().write(payload)
            socket.getOutputStream().flush()
            socket.shutdownOutput()
            socket.getInputStream().readBytes()
        }

    @Test
    fun requestAndResponseCrossTheBridgeWithHalfClose() = runBlocking {
        val (a, b) = memoryLinkPair()
        val serverPort = startEchoServer()
        val clientSide = bridge(a, serverPort = 1, allowed = setOf("peer-b"))
        bridge(b, serverPort, allowed = setOf("peer-a"))

        val port = clientSide.portFor("peer-b")
        val reply = withContext(Dispatchers.IO) { request(port, "ping".encodeToByteArray()) }

        assertEquals("ok:ping", reply.decodeToString())
    }

    @Test
    fun largeUploadKeepsEveryByteAndReportsSpeed() = runBlocking {
        val (a, b) = memoryLinkPair()
        val hashPort = startHashServer()
        val clientSide = bridge(a, serverPort = 1, allowed = setOf("peer-b"))
        bridge(b, hashPort, allowed = setOf("peer-a"))
        val sizeMb = 64
        val chunk = Random(7).nextBytes(1024 * 1024)
        val expected = MessageDigest.getInstance("SHA-256").also { d -> repeat(sizeMb) { d.update(chunk) } }
            .digest().joinToString("") { "%02x".format(it) }

        fun upload(port: Int): Pair<String, Long> =
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.soTimeout = 60_000
                val start = System.nanoTime()
                val out = socket.getOutputStream()
                repeat(sizeMb) { out.write(chunk) }
                out.flush()
                socket.shutdownOutput()
                val hash = socket.getInputStream().readBytes().decodeToString()
                hash to (System.nanoTime() - start)
            }

        val viaBridge = withContext(Dispatchers.IO) { upload(clientSide.portFor("peer-b")) }
        val direct = withContext(Dispatchers.IO) { upload(hashPort) }

        assertEquals(expected, viaBridge.first)
        assertEquals(expected, direct.first)
        val bridgeMbPerSec = sizeMb / (viaBridge.second / 1e9)
        val directMbPerSec = sizeMb / (direct.second / 1e9)
        println("IrohBridgeTest: ${"%.0f".format(bridgeMbPerSec)} MB/s through the bridge, ${"%.0f".format(directMbPerSec)} MB/s direct")
        assertTrue("bridge below 20 MB/s on loopback", bridgeMbPerSec > 20)
    }

    @Test
    fun peerThatIsNotPairedNeverReachesTheServer() = runBlocking {
        val (a, b) = memoryLinkPair("stranger", "peer-b")
        val connections = AtomicInteger()
        val serverPort = startEchoServer(connections)
        val strangerSide = bridge(a, serverPort = 1, allowed = setOf("peer-b"))
        bridge(b, serverPort, allowed = setOf("someone-else"))

        val port = strangerSide.portFor("peer-b")
        val reply = withContext(Dispatchers.IO) {
            runCatching { request(port, "ping".encodeToByteArray()) }.getOrDefault(ByteArray(0))
        }

        assertEquals(0, reply.size)
        assertEquals(0, connections.get())
    }

    @Test
    fun manyStreamsAtOnceStayApart() = runBlocking {
        val (a, b) = memoryLinkPair()
        val serverPort = startEchoServer()
        val clientSide = bridge(a, serverPort = 1, allowed = setOf("peer-b"))
        bridge(b, serverPort, allowed = setOf("peer-a"))
        val port = clientSide.portFor("peer-b")

        val replies = (0 until 32).map { i ->
            async(Dispatchers.IO) { request(port, "msg-$i".encodeToByteArray()).decodeToString() }
        }.awaitAll()

        replies.forEachIndexed { i, reply -> assertEquals("ok:msg-$i", reply) }
    }

    @Test
    fun deadServerClosesTheClientInsteadOfHanging() = runBlocking {
        val (a, b) = memoryLinkPair()
        val deadPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val clientSide = bridge(a, serverPort = 1, allowed = setOf("peer-b"))
        bridge(b, deadPort, allowed = setOf("peer-a"))
        val port = clientSide.portFor("peer-b")

        val reply = withTimeout(10_000) {
            withContext(Dispatchers.IO) {
                runCatching { request(port, "ping".encodeToByteArray()) }.getOrDefault(ByteArray(0))
            }
        }

        assertEquals(0, reply.size)
    }

    @Test
    fun aLinkThatThrowsOnOpenClosesThatClientAndTheListenerKeepsServing() = runBlocking {
        val (a, b) = memoryLinkPair()
        val serverPort = startEchoServer()
        val failures = AtomicInteger(1)
        val flaky = object : IrohLink by a {
            override suspend fun open(peerId: String): IrohStream {
                if (failures.getAndDecrement() > 0) throw RuntimeException("connect failed")
                return a.open(peerId)
            }
        }
        val clientSide = bridge(flaky, serverPort = 1, allowed = setOf("peer-b"))
        bridge(b, serverPort, allowed = setOf("peer-a"))
        val port = clientSide.portFor("peer-b")

        val first = withTimeout(10_000) {
            withContext(Dispatchers.IO) { runCatching { request(port, "one".encodeToByteArray()) }.getOrDefault(ByteArray(0)) }
        }
        val second = withContext(Dispatchers.IO) { request(port, "two".encodeToByteArray()) }

        assertEquals(0, first.size)
        assertEquals("ok:two", second.decodeToString())
    }

    @Test
    fun listenerAcceptsOnlyLoopback() = runBlocking {
        val (a, _) = memoryLinkPair()
        val clientSide = bridge(a, serverPort = 1, allowed = setOf("peer-b"))
        clientSide.portFor("peer-b")

        val address = clientSide.listenerAddress("peer-b")!!
        assertTrue(address.isLoopbackAddress)
        // The app's HTTP client dials the literal 127.0.0.1, so a listener on ::1 (what some Android
        // versions return for the loopback address) would refuse every request.
        assertTrue(address.address.contentEquals(byteArrayOf(127, 0, 0, 1)))
    }

    @Test
    fun portForIsStablePerPeer() = runBlocking {
        val (a, _) = memoryLinkPair()
        val clientSide = bridge(a, serverPort = 1, allowed = setOf("peer-b"))

        assertEquals(clientSide.portFor("peer-b"), clientSide.portFor("peer-b"))
    }

    @Test
    fun streamsOverTheLimitAreDropped() = runBlocking {
        val (a, b) = memoryLinkPair()
        val connections = AtomicInteger()
        val serverPort = startEchoServer(connections)
        bridge(b, serverPort, allowed = setOf("peer-a"), maxStreams = 2)
        val held = mutableListOf<IrohStream>()
        repeat(2) { held += a.open("peer-b").also { it.write("hold".encodeToByteArray()) } }
        withTimeout(5_000) { while (connections.get() < 2) kotlinx.coroutines.delay(20) }

        val third = a.open("peer-b")
        third.write("late".encodeToByteArray())
        third.finish()
        val answer = withTimeout(5_000) { runCatching { third.read(1024) }.getOrNull() }

        assertEquals(null, answer)
        assertEquals(2, connections.get())
        held.forEach { it.abort() }
    }

    @Test
    fun stopFreesTheLoopbackPort() = runBlocking {
        val (a, _) = memoryLinkPair()
        val clientSide = IrohBridge(a, 1, { true }, scope)
        clientSide.start()
        val port = clientSide.portFor("peer-b")
        clientSide.stop()

        val refused = runCatching { Socket(InetAddress.getLoopbackAddress(), port).close() }.isFailure
        assertTrue(refused)
    }
}
