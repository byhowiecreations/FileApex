package com.fileapex.security.tls

import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

class TlsFrontServerTest {
    private val serverId = TlsIdentityFactory.generate()
    private val pairedId = TlsIdentityFactory.generate()
    private val strangerId = TlsIdentityFactory.generate()

    private lateinit var http: EmbeddedServer<*, *>
    private lateinit var front: TlsFrontServer
    private var bridgePort = 0
    private var tlsPort = 0

    @Volatile
    private var paired = true

    private val directory = object : TlsPeerDirectory {
        override fun deviceIdForPin(pin: String) = if (paired && pin == pairedId.pin) "dev-paired" else null
    }

    @Before
    fun setUp() {
        bridgePort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        http = embeddedServer(CIO, configure = {
            connector { port = bridgePort; host = "127.0.0.1" }
        }) {
            routing {
                get("/who") {
                    val peer = TlsBridgeRegistry.lookup(call.request.local.remotePort)
                    call.respondText("${call.request.local.localPort == bridgePort}|${peer?.deviceId}|${peer?.remoteIp}")
                }
                post("/sink") {
                    val channel = call.receiveChannel()
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read < 0) break
                        total += read
                    }
                    call.respondText(total.toString())
                }
            }
        }.start(wait = false)
        front = TlsFrontServer(
            identity = { serverId },
            directory = directory,
            bindHost = "127.0.0.1",
            preferredPort = 0,
            bridgePort = bridgePort,
            log = { _, _ -> }
        )
        tlsPort = front.start()
        Thread.sleep(300)
    }

    @After
    fun tearDown() {
        front.stop()
        http.stop(0, 0)
        TlsBridgeRegistry.clear()
    }

    private fun client(identity: TlsIdentity?): SSLSocket {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            if (identity != null) setKeyEntry("k", identity.privateKey, "pw".toCharArray(), arrayOf(identity.certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, "pw".toCharArray()) }
        val trust = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("s", serverId.certificate) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, tmf.trustManagers, null) }
        return (context.socketFactory.createSocket("127.0.0.1", tlsPort) as SSLSocket).apply {
            soTimeout = 15_000
            enabledProtocols = arrayOf("TLSv1.3")
            startHandshake()
        }
    }

    private fun bodyOf(raw: String) = raw.substringAfter("\r\n\r\n")

    private fun roundTrip(socket: Socket, head: String, body: ByteArray? = null): String {
        val out = socket.getOutputStream()
        out.write(head.toByteArray())
        if (body != null) out.write(body)
        out.flush()
        val reader = socket.getInputStream()
        val collected = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            collected.write(buffer, 0, read)
            val text = collected.toString(Charsets.ISO_8859_1)
            val headerEnd = text.indexOf("\r\n\r\n")
            if (headerEnd >= 0) {
                val length = Regex("(?i)content-length: (\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
                if (text.length - headerEnd - 4 >= length) return text
            }
        }
        return collected.toString(Charsets.ISO_8859_1)
    }

    @Test
    fun pairedClientIsIdentifiedFromItsCertificateNotHeaders() {
        client(pairedId).use { socket ->
            assertEquals("TLSv1.3", socket.session.protocol)
            val response = roundTrip(
                socket,
                "GET /who?from=someone-else HTTP/1.1\r\nHost: x\r\nX-FileApex-Device-Id: spoof\r\nContent-Length: 0\r\n\r\n"
            )
            assertEquals("true|dev-paired|127.0.0.1", bodyOf(response))
        }
    }

    @Test
    fun strangerAndAnonymousClientsNeverReachTheServer() {
        for (identity in listOf(strangerId, null)) {
            val result = runCatching {
                client(identity).use { socket ->
                    roundTrip(socket, "GET /who HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n")
                }
            }
            assertTrue("expected rejection for ${identity?.pin?.take(8)}", result.isFailure)
        }
    }

    @Test
    fun directConnectionToBridgePortHasNoPeerIdentity() {
        Socket("127.0.0.1", bridgePort).use { socket ->
            socket.soTimeout = 5000
            val response = roundTrip(socket, "GET /who HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n")
            assertEquals("true|null|null", bodyOf(response))
        }
    }

    @Test
    fun largeUploadStreamsThroughTheFront() {
        val size = 256L * 1024 * 1024
        client(pairedId).use { socket ->
            val out = socket.getOutputStream()
            out.write("POST /sink HTTP/1.1\r\nHost: x\r\nContent-Length: $size\r\n\r\n".toByteArray())
            val chunk = ByteArray(256 * 1024) { it.toByte() }
            var sent = 0L
            while (sent < size) {
                out.write(chunk)
                sent += chunk.size
            }
            out.flush()
            val response = StringBuilder()
            val input = socket.getInputStream()
            val buffer = ByteArray(1024)
            while (!response.contains("\r\n\r\n") || !response.endsWith(size.toString())) {
                val read = input.read(buffer)
                if (read < 0) break
                response.append(String(buffer, 0, read, Charsets.ISO_8859_1))
            }
            assertEquals(size.toString(), bodyOf(response.toString()))
        }
    }

    @Test
    fun registryEntryIsRemovedWhenTheConnectionCloses() {
        client(pairedId).use { socket ->
            roundTrip(socket, "GET /who HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n")
        }
        Thread.sleep(500)
        assertNull(TlsBridgeRegistry.lookup(-1))
        assertFalse(TlsBridgeRegistryAccess.anyRegistered())
    }

    @Test
    fun unpairingClosesALiveConnection() {
        client(pairedId).use { socket ->
            roundTrip(socket, "GET /who HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n")
            paired = false
            front.revalidate()
            val outcome = runCatching {
                roundTrip(socket, "GET /who HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n")
            }
            assertTrue(outcome.isFailure || outcome.getOrThrow().isEmpty())
        }
    }

    @Test
    fun protocolSelectionPrefersTls13AndFallsBackToTls12() {
        assertEquals(listOf("TLSv1.3"), TlsFrontServer.preferredProtocols(arrayOf("TLSv1.2", "TLSv1.3")).toList())
        assertEquals(listOf("TLSv1.2"), TlsFrontServer.preferredProtocols(arrayOf("TLSv1.2")).toList())
    }
}

private object TlsBridgeRegistryAccess {
    fun anyRegistered(): Boolean = (1..65535).any { TlsBridgeRegistry.lookup(it) != null }
}
