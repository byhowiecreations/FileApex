package com.fileapex.iroh

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two real Iroh endpoints on this machine, no relay, joined by the bridge. This is the Stage 1
 * measurement: does carrying the HTTP API over Iroh cost speed compared with a plain loopback socket.
 */
class IrohRealLoopbackTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = mutableListOf<ServerSocket>()
    private val bridges = mutableListOf<IrohBridge>()

    @After
    fun tearDown() {
        runBlocking { bridges.forEach { runCatching { it.stop() } } }
        servers.forEach { runCatching { it.close() } }
        scope.cancel()
    }

    private fun startServer(): Int {
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

    private fun upload(port: Int, chunk: ByteArray, megabytes: Int): Pair<String, Double> =
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 120_000
            val start = System.nanoTime()
            val out = socket.getOutputStream()
            repeat(megabytes) { out.write(chunk) }
            out.flush()
            socket.shutdownOutput()
            val hash = socket.getInputStream().readBytes().decodeToString()
            hash to megabytes / ((System.nanoTime() - start) / 1e9)
        }

    @Test
    fun theDialedSideCanOpenStreamsBackOnTheSameConnection() = runBlocking {
        val a = IrohEndpointLink.bind(bindAddr = "127.0.0.1:0", relay = false)
        val b = IrohEndpointLink.bind(bindAddr = "127.0.0.1:0", relay = false)
        val bId = a.addPeer(b.address())
        val aId = b.addPeer(a.address())
        val serverA = startServer()
        val serverB = startServer()
        val bridgeA = IrohBridge(a, serverA, { it == bId }, scope).also { it.start(); bridges += it }
        val bridgeB = IrohBridge(b, serverB, { it == aId }, scope).also { it.start(); bridges += it }
        val chunk = Random(5).nextBytes(1024)
        val expected = MessageDigest.getInstance("SHA-256").digest(chunk).joinToString("") { "%02x".format(it) }

        // A dials B first. B then answers over the connection A made, with no connection of its own.
        val forward = withContext(Dispatchers.IO) { upload(bridgeA.portFor(bId), chunk, 1) }
        val back = withContext(Dispatchers.IO) { upload(bridgeB.portFor(aId), chunk, 1) }

        assertEquals(expected, forward.first)
        assertEquals(expected, back.first)
    }

    @Test
    fun connectingFirstOpensNoStreamOnThePeer() = runBlocking {
        val a = IrohEndpointLink.bind(bindAddr = "127.0.0.1:0", relay = false)
        val b = IrohEndpointLink.bind(bindAddr = "127.0.0.1:0", relay = false)
        val bId = a.addPeer(b.address())

        a.connect(bId)
        val stray = kotlinx.coroutines.withTimeoutOrNull(1_000) { b.accept() }

        assertEquals(null, stray)
        a.close()
        b.close()
    }

    @Test
    fun theSameSecretKeyGivesTheSameEndpointId() = runBlocking {
        val secret = Random(3).nextBytes(32)
        val first = IrohEndpointLink.bind(secretKey = secret, bindAddr = "127.0.0.1:0", relay = false)
        val firstId = first.id
        first.close()
        val second = IrohEndpointLink.bind(secretKey = secret, bindAddr = "127.0.0.1:0", relay = false)
        val secondId = second.id
        second.close()

        assertEquals(firstId, secondId)
    }

    @Test
    fun realEndpointsCarryALargeUploadAndReportSpeed() = runBlocking {
        val a = IrohEndpointLink.bind(bindAddr = "127.0.0.1:0", relay = false)
        val b = IrohEndpointLink.bind(bindAddr = "127.0.0.1:0", relay = false)
        val aId = a.addPeer(b.address())
        b.addPeer(a.address())
        val serverPort = startServer()
        val clientSide = IrohBridge(a, 1, { it == aId }, scope).also { it.start(); bridges += it }
        IrohBridge(b, serverPort, { it == a.id }, scope).also { it.start(); bridges += it }

        val megabytes = 128
        val chunk = Random(11).nextBytes(1024 * 1024)
        val expected = MessageDigest.getInstance("SHA-256").also { d -> repeat(megabytes) { d.update(chunk) } }
            .digest().joinToString("") { "%02x".format(it) }

        val viaIroh = withContext(Dispatchers.IO) { upload(clientSide.portFor(aId), chunk, megabytes) }
        val direct = withContext(Dispatchers.IO) { upload(serverPort, chunk, megabytes) }

        println(
            "IrohRealLoopbackTest: ${"%.0f".format(viaIroh.second)} MB/s over Iroh, " +
                "${"%.0f".format(direct.second)} MB/s direct, path=${a.pathKind(aId)}"
        )
        assertEquals(expected, viaIroh.first)
        assertEquals(expected, direct.first)
    }
}
