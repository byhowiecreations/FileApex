package com.fileapex.security.tls

import com.fileapex.data.db.PairedDeviceEntity
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class PeerTlsConnectorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val serverIdentity = TlsIdentityFactory.generate()
    private lateinit var clientStore: TlsIdentityStore
    private lateinit var clientIdentity: TlsIdentity
    private lateinit var http: EmbeddedServer<*, *>
    private lateinit var front: TlsFrontServer
    private var httpPort = 0
    private var bridgePort = 0
    private var tlsPort = 0
    private var allowClient = true

    private val directory = object : TlsPeerDirectory {
        override fun deviceIdForPin(pin: String) = if (allowClient && pin == clientIdentity.pin) "dev-client" else null
    }

    private fun freePort() = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    @Before
    fun setUp() {
        clientStore = TlsIdentityStore(File(temp.root, "client"), emptyList())
        clientIdentity = clientStore.getOrCreate()
        PeerTlsConnector.configure { clientStore }
        httpPort = freePort()
        bridgePort = freePort()
        http = embeddedServer(CIO, configure = {
            connector { port = httpPort; host = "127.0.0.1" }
            connector { port = bridgePort; host = "127.0.0.1" }
        }) {
            routing {
                get("/who") {
                    val peer = TlsBridgeRegistry.lookup(call.request.local.remotePort)
                    call.respondText("${peer?.deviceId}")
                }
            }
        }.start(wait = false)
        front = TlsFrontServer({ serverIdentity }, directory, "127.0.0.1", 0, bridgePort) { _, _ -> }
        tlsPort = front.start()
        Thread.sleep(300)
    }

    @After
    fun tearDown() {
        front.stop()
        http.stop(0, 0)
        PeerTlsRoutes.update(emptyList())
        PeerTlsStatus.reportHealthy("dev-server")
    }

    private fun route(pin: String) = PeerTlsRoutes.update(
        listOf(
            PairedDeviceEntity(
                deviceId = "dev-server", deviceName = "Server", lastKnownIp = "127.0.0.1", port = httpPort,
                publicKeyHash = "", rootPath = "/", tlsPin = pin, tlsPort = tlsPort
            )
        )
    )

    private fun get(socket: Socket): String {
        socket.soTimeout = 5000
        socket.getOutputStream().apply {
            write("GET /who HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray()); flush()
        }
        return socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1).substringAfter("\r\n\r\n")
    }

    @Test
    fun pinnedPeerIsReachedOverTlsAndIdentifiesTheClient() {
        route(serverIdentity.pin)
        val io = PeerTlsConnector.connect(Socket(), "127.0.0.1", httpPort, 5000)
        io.use { assertEquals("dev-client", get(it).trim().lines().last()) }
    }

    @Test
    fun wrongServerKeyFailsClosedAndFlagsThePeer() {
        route(TlsIdentityFactory.generate().pin)
        val failure = runCatching { PeerTlsConnector.connect(Socket(), "127.0.0.1", httpPort, 5000) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue("dev-server" in PeerTlsStatus.pinMismatch.value)
    }

    @Test
    fun alternatePinIsAcceptedDuringRotation() {
        PeerTlsRoutes.update(
            listOf(
                PairedDeviceEntity(
                    deviceId = "dev-server", deviceName = "Server", lastKnownIp = "127.0.0.1", port = httpPort,
                    publicKeyHash = "", rootPath = "/", tlsPin = TlsIdentityFactory.generate().pin,
                    tlsPinAlt = serverIdentity.pin, tlsPort = tlsPort
                )
            )
        )
        PeerTlsConnector.connect(Socket(), "127.0.0.1", httpPort, 5000).use { assertTrue(get(it).contains("dev-client")) }
        assertFalse("dev-server" in PeerTlsStatus.pinMismatch.value)
    }

    @Test
    fun clientNotKnownToThePeerCannotUseTheConnection() {
        allowClient = false
        route(serverIdentity.pin)
        val result = runCatching {
            PeerTlsConnector.connect(Socket(), "127.0.0.1", httpPort, 5000).use { get(it) }
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun peerWithoutAPinUsesThePlainSocket() {
        PeerTlsRoutes.update(emptyList())
        val plain = Socket()
        val io = PeerTlsConnector.connect(plain, "127.0.0.1", httpPort, 5000)
        assertTrue(io === plain)
        io.close()
    }
}
