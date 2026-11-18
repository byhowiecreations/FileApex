package com.fileapex.network

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.security.tls.PeerTlsConnector
import com.fileapex.security.tls.PeerTlsRoutes
import com.fileapex.security.tls.RosterTlsPeerDirectory
import com.fileapex.security.tls.TlsFrontServer
import com.fileapex.security.tls.TlsIdentityFactory
import com.fileapex.security.tls.TlsIdentityStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What the hand-written peer HTTP client does today, pinned down through its public functions so a replacement (OkHttp,
 * plan docs/planning/3-5-ktor-bound-client-plan.md) must do exactly the same. Plain-HTTP cases use the open pairing
 * routes; the TLS case uses a real pinned front. The peer is a scripted raw socket server, so every byte is visible.
 */
abstract class PeerBoundHttpCharacterisationBase(private val okHttp: Boolean) {
    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun chooseClient() {
        PeerHttpClientSwitch.override = okHttp
    }

    private class Req(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray)

    /** A raw HTTP/1.1 peer: [reply] writes the answer to what was received; a body without a length is read until the client half-closes. */
    private class ScriptedPeer(private val reply: (Req, OutputStream) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int = server.localPort
        val requests = CopyOnWriteArrayList<Req>()

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { socket.use { serve(it) } }
                }
            }
        }

        private fun serve(socket: Socket) {
            val input = socket.getInputStream().buffered()
            val first = input.readAsciiLine()
            if (first.isEmpty()) return
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = input.readAsciiLine()
                if (line.isEmpty()) break
                headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
            }
            val body = when {
                headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
                headers["content-length"] != null -> input.readNBytes(headers.getValue("content-length").toInt())
                first.startsWith("POST") -> input.readBytes()
                else -> ByteArray(0)
            }
            val request = Req(first.substringBefore(' '), first.substringAfter(' ').substringBefore(' '), headers, body)
            requests += request
            reply(request, socket.getOutputStream())
            socket.getOutputStream().flush()
        }

        private fun readChunked(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = input.readAsciiLine().trim().toInt(16)
                if (size == 0) {
                    input.readAsciiLine()
                    return out.toByteArray()
                }
                out.write(input.readNBytes(size))
                input.readAsciiLine()
            }
        }

        override fun close() = server.close()
    }

    private val peers = mutableListOf<ScriptedPeer>()
    private var front: TlsFrontServer? = null

    private fun peer(reply: (Req, OutputStream) -> Unit) = ScriptedPeer(reply).also { peers += it }

    @After
    fun stop() {
        PeerHttpClientSwitch.override = null
        com.fileapex.security.tls.PeerTlsStatus.prompts.value.forEach { com.fileapex.security.tls.PeerTlsStatus.dismiss(it.deviceId) }
        sinks.forEach { it.close() }
        peers.forEach { it.close() }
        front?.stop()
        PeerTlsRoutes.update(emptyList())
    }

    private fun ok(out: OutputStream, body: String, extra: String = "") =
        out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n$extra\r\n$body".toByteArray())

    private val health = "/api/v1/health"
    private val open = "/api/v1/pairing/x"

    @Test fun aSmallGetReturnsStatusBodyAndLowerCaseHeaders() = runBlocking {
        val p = peer { _, out -> ok(out, "hello", "X-Thing: One\r\n") }
        val r = peerHttpGet("127.0.0.1", p.port, health, 5_000)!!
        assertEquals(200, r.statusCode)
        assertEquals("hello", r.body)
        assertEquals("One", r.headers["x-thing"])
    }

    @Test fun theRequestLineHostAndConnectionCloseAreSentAsToday() = runBlocking {
        val p = peer { _, out -> ok(out, "x") }
        peerHttpGet("127.0.0.1", p.port, "$health?from=a", 5_000)
        val seen = p.requests.single()
        assertEquals("GET", seen.method)
        assertEquals("$health?from=a", seen.target)
        assertEquals("127.0.0.1:${p.port}", seen.headers["host"])
        assertEquals("close", seen.headers["connection"])
        assertEquals("application/json", seen.headers["accept"])
    }

    @Test fun aPinInTheQueryTravelsAsAHeaderAndIsNotInTheUrl() = runBlocking {
        val p = peer { _, out -> ok(out, "x") }
        peerHttpGet("127.0.0.1", p.port, "$health?pin=secret&from=a", 5_000)
        val seen = p.requests.single()
        assertEquals("$health?from=a", seen.target)
        assertEquals("secret", seen.headers["x-fileapex-pin"])
    }

    @Test fun theBodyIsTrimmed() = runBlocking {
        val p = peer { _, out -> ok(out, "  padded \n") }
        assertEquals("padded", peerHttpGet("127.0.0.1", p.port, health, 5_000)!!.body)
    }

    @Test fun aNon2xxAnswerIsReturnedWithItsStatusAndBody() = runBlocking {
        val p = peer { _, out -> out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 7\r\nConnection: close\r\n\r\ndenied!".toByteArray()) }
        val r = peerHttpGet("127.0.0.1", p.port, health, 5_000)!!
        assertEquals(403, r.statusCode)
        assertEquals("denied!", r.body)
    }

    @Test fun aChunkedAnswerIsDecoded() = runBlocking {
        val p = peer { _, out -> out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n".toByteArray()) }
        assertEquals("hello world", peerHttpGet("127.0.0.1", p.port, health, 5_000)!!.body)
    }

    @Test fun anAnswerWithoutALengthIsReadUntilTheServerCloses() = runBlocking {
        val p = peer { _, out -> out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nuntil close".toByteArray()) }
        assertEquals("until close", peerHttpGet("127.0.0.1", p.port, health, 5_000)!!.body)
    }

    @Test fun anAnswerDeclaringMoreThanTheCapIsRefused() = runBlocking {
        val p = peer { _, out -> out.write("HTTP/1.1 200 OK\r\nContent-Length: ${MAX_PEER_RESPONSE_BYTES + 1L}\r\nConnection: close\r\n\r\nx".toByteArray()) }
        assertNull(peerHttpGet("127.0.0.1", p.port, health, 5_000))
    }

    @Test fun aChunkedAnswerOverTheCapIsRefused() = runBlocking {
        val big = "x".repeat(1 shl 20)
        val p = peer { _, out ->
            out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray())
            repeat(34) { out.write("${big.length.toString(16)}\r\n$big\r\n".toByteArray()) }
            out.write("0\r\n\r\n".toByteArray())
        }
        assertNull(peerHttpGet("127.0.0.1", p.port, health, 10_000))
    }

    @Test fun tooManyHeaderLinesAreRefused() = runBlocking {
        val p = peer { _, out ->
            out.write(("HTTP/1.1 200 OK\r\nContent-Length: 1\r\n" + (1..300).joinToString("") { "X-$it: v\r\n" } + "\r\nx").toByteArray())
        }
        assertNull(peerHttpGet("127.0.0.1", p.port, health, 5_000))
    }

    @Test fun aHeaderLineOverTheCapIsRefused() = runBlocking {
        val p = peer { _, out -> out.write(("HTTP/1.1 200 OK\r\nX-Long: ${"a".repeat(MAX_HEADER_LINE_CHARS + 10)}\r\nContent-Length: 1\r\n\r\nx").toByteArray()) }
        assertNull(peerHttpGet("127.0.0.1", p.port, health, 5_000))
    }

    @Test fun aRefusedConnectionReturnsNull() = runBlocking {
        val dead = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertNull(peerHttpGet("127.0.0.1", dead, health, 2_000))
    }

    @Test fun aPostSendsTheJsonBodyWithItsLengthAndType() = runBlocking {
        val p = peer { _, out -> ok(out, """{"ok":true}""") }
        val r = peerHttpPost("127.0.0.1", p.port, health, """{"a":"é"}""", "application/json", 5_000)!!
        assertEquals(200, r.statusCode)
        val seen = p.requests.single()
        assertEquals("POST", seen.method)
        assertEquals("application/json", seen.headers["content-type"])
        assertEquals("""{"a":"é"}""".toByteArray().size.toString(), seen.headers["content-length"])
        assertEquals("""{"a":"é"}""", seen.body.decodeToString())
    }

    @Test fun aStreamingGetDeliversTheBodyAndTheStatus() = runBlocking {
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        val p = peer { _, out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(payload)
        }
        val got = ByteArrayOutputStream()
        var status = 0
        val result = peerHttpGetStreaming("127.0.0.1", p.port, open, 5_000, 5_000, { b, n -> got.write(b, 0, n) }, { status = it })
        assertEquals(200, result!!.statusCode)
        assertEquals(200, status)
        assertArrayEquals(payload, got.toByteArray())
        assertEquals("application/octet-stream", p.requests.single().headers["accept"])
    }

    @Test fun aStreamingGetWithAnOffsetAsksForTheRest() = runBlocking {
        val p = peer { _, out -> out.write("HTTP/1.1 206 Partial Content\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc".toByteArray()) }
        peerHttpGetStreaming("127.0.0.1", p.port, "$open?offset=1048576&x=1", 5_000, 5_000, { _, _ -> }, null)
        assertEquals("bytes=1048576-", p.requests.single().headers["range"])
        assertEquals("$open?offset=1048576&x=1", p.requests.single().target)
    }

    @Test fun aStreamingGetWithoutAnOffsetSendsNoRange() = runBlocking {
        val p = peer { _, out -> ok(out, "abc") }
        peerHttpGetStreaming("127.0.0.1", p.port, "$open?offset=0", 5_000, 5_000, { _, _ -> }, null)
        assertFalse(p.requests.single().headers.containsKey("range"))
    }

    @Test fun aChunkedStreamingAnswerIsDeliveredWholeInOrder() = runBlocking {
        val p = peer { _, out -> out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n3\r\nabc\r\n3\r\ndef\r\n0\r\n\r\n".toByteArray()) }
        val got = ByteArrayOutputStream()
        peerHttpGetStreaming("127.0.0.1", p.port, open, 5_000, 5_000, { b, n -> got.write(b, 0, n) }, null)
        assertEquals("abcdef", got.toString())
    }

    @Test fun aStreamingAnswerWithoutALengthIsReadUntilClose() = runBlocking {
        val p = peer { _, out -> out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\ntail".toByteArray()) }
        val got = ByteArrayOutputStream()
        peerHttpGetStreaming("127.0.0.1", p.port, open, 5_000, 5_000, { b, n -> got.write(b, 0, n) }, null)
        assertEquals("tail", got.toString())
    }

    @Test fun aStreamingErrorStatusIsReportedAndNoBodyIsDelivered() = runBlocking {
        val p = peer { _, out -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 4\r\nConnection: close\r\n\r\nnope".toByteArray()) }
        var chunks = 0
        var status = 0
        val result = peerHttpGetStreaming("127.0.0.1", p.port, open, 5_000, 5_000, { _, _ -> chunks++ }, { status = it })
        assertEquals(404, result!!.statusCode)
        assertEquals(404, status)
        assertEquals(0, chunks)
    }

    @Test fun aStreamingBodyThatEndsShortFailsTheTransfer() = runBlocking {
        val p = peer { _, out -> out.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\nonly forty bytes of the hundred promised".toByteArray()) }
        val failed = runCatching { peerHttpGetStreaming("127.0.0.1", p.port, open, 5_000, 5_000, { _, _ -> }, null) }
        assertTrue("a short body must not look complete: ${failed.getOrNull()}", failed.isFailure || failed.getOrNull() == null)
    }

    @Test fun aStreamingRefusedConnectionReturnsNull() = runBlocking {
        val dead = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertNull(peerHttpGetStreaming("127.0.0.1", dead, open, 2_000, 2_000, { _, _ -> }, null))
    }

    @Test fun anUploadFromAFileSendsTheRangeWithItsLengthAndReportsProgress() = runBlocking {
        val source = temp.newFile("src.bin").also { f -> f.writeBytes(ByteArray(500_000) { (it % 199).toByte() }) }
        val p = peer { _, out -> ok(out, "stored") }
        val progress = mutableListOf<Long>()
        val r = peerHttpUploadFromFile("127.0.0.1", p.port, "$open?offset=100000", "application/octet-stream", source.path, 100_000, 250_000, 5_000, 5_000) { sent, _ -> progress += sent }!!
        assertEquals(200, r.statusCode)
        assertEquals("stored", r.body)
        val seen = p.requests.single()
        assertEquals("POST", seen.method)
        assertEquals("250000", seen.headers["content-length"])
        assertEquals("application/octet-stream", seen.headers["content-type"])
        assertArrayEquals(source.readBytes().copyOfRange(100_000, 350_000), seen.body)
        assertTrue(progress.isNotEmpty() && progress.last() == 350_000L)
    }

    @Test fun anUploadFromAChannelWithALengthSendsThatLength() = runBlocking {
        val p = peer { _, out -> ok(out, "stored") }
        val chunks = Channel<ByteArray>(Channel.UNLIMITED).also { c -> c.trySend("abc".toByteArray()); c.trySend("defg".toByteArray()); c.close() }
        val r = peerHttpUploadFromChannel("127.0.0.1", p.port, open, "application/octet-stream", chunks, 5_000, 5_000, 7L)!!
        assertEquals("stored", r.body)
        val seen = p.requests.single()
        assertEquals("7", seen.headers["content-length"])
        assertEquals("abcdefg", seen.body.decodeToString())
    }

    @Test fun anUploadFromAChannelWithoutALengthEndsTheBodyByHalfClosing() = runBlocking {
        val p = peer { _, out -> ok(out, "stored") }
        val chunks = Channel<ByteArray>(Channel.UNLIMITED).also { c -> c.trySend("abc".toByteArray()); c.trySend("defg".toByteArray()); c.close() }
        val r = peerHttpUploadFromChannel("127.0.0.1", p.port, open, "application/octet-stream", chunks, 5_000, 5_000, null)!!
        assertEquals("stored", r.body)
        val seen = p.requests.single()
        assertNull("no length is sent", seen.headers["content-length"])
        assertNull("no chunked framing either", seen.headers["transfer-encoding"])
        assertEquals("abcdefg", seen.body.decodeToString())
    }

    @Test fun anUploadRefusedConnectionReturnsNull() = runBlocking {
        val dead = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val chunks = Channel<ByteArray>(Channel.UNLIMITED).also { it.close() }
        assertNull(peerHttpUploadFromChannel("127.0.0.1", dead, open, "application/octet-stream", chunks, 2_000, 2_000, 0L))
    }

    @Test fun aRouteThatNeedsTlsIsNeverSentAsPlainHttp() = runBlocking {
        val p = peer { _, out -> ok(out, "x") }
        assertNull(peerHttpGet("127.0.0.1", p.port, "/api/v1/files/list", 2_000))
        assertTrue(p.requests.isEmpty())
    }

    @Test fun cancellingTheCoroutineEndsADownloadBlockedOnASlowPeer() = runBlocking {
        Assume.assumeTrue("OkHttp only: the hand-written client cannot interrupt a blocked read", okHttp)
        val p = peer { _, out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\nConnection: close\r\n\r\nfirst".toByteArray())
            out.flush()
            Thread.sleep(30_000)
        }
        val gotFirst = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = kotlinx.coroutines.GlobalScope.async(kotlinx.coroutines.Dispatchers.IO) {
            peerHttpGetStreaming("127.0.0.1", p.port, open, 5_000, 20_000, { _, _ -> gotFirst.complete(Unit) }, null)
        }
        kotlinx.coroutines.withTimeout(5_000) { gotFirst.await() }
        val started = System.nanoTime()
        job.cancel()
        kotlinx.coroutines.withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
        assertTrue("cancel took too long: ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 3_000_000_000L)
    }

    /** Accepts and then reads nothing at all, so the sender's writes stop once the socket buffers are full. */
    private val sinks = mutableListOf<ServerSocket>()

    private fun slowSink(): ScriptedPeerPort {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress()).also { sinks += it }
        thread(isDaemon = true) {
            val held = mutableListOf<Socket>()
            while (!server.isClosed) held += runCatching { server.accept() }.getOrNull() ?: break
        }
        return ScriptedPeerPort(server.localPort)
    }

    private class ScriptedPeerPort(val port: Int)

    private fun CoroutineScope.bigChunks(count: Int): Channel<ByteArray> {
        val chunks = Channel<ByteArray>(2)
        launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                repeat(count) { chunks.send(ByteArray(1 shl 20)) }
                chunks.close()
            } catch (_: kotlinx.coroutines.CancellationException) {
                chunks.close()
            }
        }
        return chunks
    }

    @Test fun cancellingTheCoroutineEndsAnUploadBlockedOnASlowPeer() = runBlocking {
        Assume.assumeTrue("OkHttp only", okHttp)
        val p = slowSink()
        val job = kotlinx.coroutines.GlobalScope.async(kotlinx.coroutines.Dispatchers.IO) {
            coroutineScope {
                peerHttpUploadFromChannel("127.0.0.1", p.port, open, "application/octet-stream", bigChunks(400), 5_000, 20_000, 400L * (1 shl 20))
            }
        }
        delay(1_000)
        val started = System.nanoTime()
        job.cancel()
        kotlinx.coroutines.withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
        assertTrue("cancel took too long", System.nanoTime() - started < 3_000_000_000L)
    }

    @Test fun theUsersCancelClosesAnInFlightUploadAsACancellation() = runBlocking {
        Assume.assumeTrue("OkHttp only", okHttp)
        com.fileapex.domain.transfer.TransferActivityGuard.reset()
        val p = slowSink()
        val job = kotlinx.coroutines.GlobalScope.async(kotlinx.coroutines.Dispatchers.IO) {
            coroutineScope {
                peerHttpUploadFromChannel("127.0.0.1", p.port, open, "application/octet-stream", bigChunks(400), 5_000, 20_000, 400L * (1 shl 20))
            }
        }
        delay(1_000)
        com.fileapex.domain.transfer.TransferActivityGuard.cancelActiveTransfers()
        val outcome = runCatching { kotlinx.coroutines.withTimeout(5_000) { job.await() } }
        com.fileapex.domain.transfer.TransferActivityGuard.reset()
        assertTrue("expected a cancellation, got $outcome", outcome.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }

    private fun pinnedRoute(peerPort: Int, tlsPin: String, frontPort: Int, plainPort: Int = 59_999) {
        PeerTlsRoutes.update(
            listOf(
                PairedDeviceEntity(
                    deviceId = "server-1", deviceName = "Server", lastKnownIp = "127.0.0.1", port = plainPort,
                    publicKeyHash = "", rootPath = "/", tlsPin = tlsPin, tlsPort = frontPort
                )
            )
        )
    }

    private fun startFront(bridgeTo: Int, clientPin: String): Pair<Int, String> {
        val serverIdentity = TlsIdentityFactory.generate()
        val directory = RosterTlsPeerDirectory().also {
            it.refresh(listOf(com.fileapex.data.device.TlsPinEntry("client-1", clientPin, alt = "", port = 8081, revoked = false, signed = true)))
        }
        val f = TlsFrontServer({ serverIdentity }, directory, "127.0.0.1", 0, bridgeTo) { _, _ -> }
        front = f
        return f.start() to serverIdentity.pin
    }

    @Test fun aPinnedPeerIsReachedOverTlsAndTheRequestArrivesIntact() = runBlocking {
        val p = peer { _, out -> ok(out, "over tls") }
        val clientStore = TlsIdentityStore(File(temp.root, "client"), emptyList())
        PeerTlsConnector.configure { clientStore }
        val (frontPort, serverPin) = startFront(p.port, clientStore.getOrCreate().pin)
        pinnedRoute(p.port, serverPin, frontPort)
        val r = peerHttpGet("127.0.0.1", 59_999, "/api/v1/files/list?from=a", 5_000)!!
        assertEquals(200, r.statusCode)
        assertEquals("over tls", r.body)
        assertEquals("/api/v1/files/list?from=a", p.requests.single().target)
    }

    @Test fun aPeerWithAnotherKeyThanItsPinNeverReceivesTheRequest() = runBlocking {
        val p = peer { _, out -> ok(out, "x") }
        val clientStore = TlsIdentityStore(File(temp.root, "client2"), emptyList())
        PeerTlsConnector.configure { clientStore }
        val (frontPort, _) = startFront(p.port, clientStore.getOrCreate().pin)
        pinnedRoute(p.port, TlsIdentityFactory.generate().pin, frontPort)
        assertNull(peerHttpGet("127.0.0.1", 59_999, "/api/v1/files/list", 5_000))
        assertTrue(p.requests.isEmpty())
    }

    @Test fun aPinnedUploadStreamsOverTlsWithItsLength() = runBlocking {
        val p = peer { _, out -> ok(out, "stored") }
        val clientStore = TlsIdentityStore(File(temp.root, "client3"), emptyList())
        PeerTlsConnector.configure { clientStore }
        val (frontPort, serverPin) = startFront(p.port, clientStore.getOrCreate().pin)
        pinnedRoute(p.port, serverPin, frontPort)
        val data = ByteArray(200_000) { (it % 97).toByte() }
        val chunks = Channel<ByteArray>(Channel.UNLIMITED).also { c -> c.trySend(data); c.close() }
        val r = peerHttpUploadFromChannel("127.0.0.1", 59_999, "/api/v1/files/upload", "application/octet-stream", chunks, 5_000, 5_000, data.size.toLong())!!
        assertEquals("stored", r.body)
        assertArrayEquals(data, p.requests.single().body)
        assertNotNull(p.requests.single().headers["content-length"])
    }
}

/** The hand-written client, which stays one release behind the switch. */
class PeerBoundHttpLegacyClientTest : PeerBoundHttpCharacterisationBase(okHttp = false)

/** OkHttp, which must behave exactly the same. */
class PeerBoundHttpOkHttpClientTest : PeerBoundHttpCharacterisationBase(okHttp = true)
