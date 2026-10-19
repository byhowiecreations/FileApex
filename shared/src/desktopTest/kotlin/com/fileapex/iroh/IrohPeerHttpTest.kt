package com.fileapex.iroh

import com.fileapex.network.peerHttpGet
import com.fileapex.network.peerHttpPost
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The app's own HTTP client functions, pointed at a bridge port, must reach the peer's server. */
class IrohPeerHttpTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = mutableListOf<ServerSocket>()
    private val bridges = mutableListOf<IrohBridge>()

    @After
    fun tearDown() {
        runBlocking { bridges.forEach { it.stop() } }
        servers.forEach { runCatching { it.close() } }
        scope.cancel()
        IrohBridgePorts.clear()
    }

    /** Answers every request with 200 and echoes the first line of the request as the body. */
    private fun startHttpServer(): Int {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        servers += server
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val first = reader.readLine().orEmpty()
                        var length = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                        }
                        val body = CharArray(length).also { buf -> reader.read(buf, 0, length) }.concatToString()
                        val reply = "$first|$body"
                        it.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: ${reply.length}\r\nConnection: close\r\n\r\n$reply".encodeToByteArray()
                        )
                        it.getOutputStream().flush()
                    }
                }
            }
        }
        return server.localPort
    }

    private fun bridgePair(): Int = runBlocking {
        val (a, b) = memoryLinkPair()
        val serverPort = startHttpServer()
        val clientSide = IrohBridge(a, 1, { it == "peer-b" }, scope).also { it.start(); bridges += it }
        IrohBridge(b, serverPort, { it == "peer-a" }, scope).also { it.start(); bridges += it }
        clientSide.portFor("peer-b")
    }

    @Test
    fun getGoesThroughTheBridgePort() = runBlocking {
        val port = bridgePair()

        val response = peerHttpGet("127.0.0.1", port, "/api/v1/ping?from=x", 5_000)

        assertNotNull("GET returned null", response)
        assertEquals(200, response!!.statusCode)
        assertEquals("GET /api/v1/ping?from=x HTTP/1.1|", response.body)
    }

    @Test
    fun postGoesThroughTheBridgePort() = runBlocking {
        val port = bridgePair()

        val response = peerHttpPost("127.0.0.1", port, "/api/v1/iroh/address", """{"a":1}""", "application/json", 5_000)

        assertNotNull("POST returned null", response)
        assertEquals(200, response!!.statusCode)
        assertEquals("""POST /api/v1/iroh/address HTTP/1.1|{"a":1}""", response.body)
    }
}
