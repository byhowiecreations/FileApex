package com.fileapex.network

import com.fileapex.security.tls.PeerTlsConnector
import com.fileapex.security.tls.PeerTlsRoutes
import com.fileapex.security.tls.PeerTlsStatus
import com.fileapex.security.tls.TlsBridgeRegistry
import com.fileapex.security.tls.TlsFrontServer
import com.fileapex.security.tls.TlsIdentity
import com.fileapex.security.tls.TlsIdentityFactory
import com.fileapex.security.tls.TlsIdentityStore
import com.fileapex.security.tls.TlsPeerDirectory
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The fetch that reads signed data from a peer whose TLS key is not (or no longer) the pinned one. */
class UnpinnedFetchTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val serverIdentity = TlsIdentityFactory.generate()
    private lateinit var clientIdentity: TlsIdentity
    private lateinit var http: EmbeddedServer<*, *>
    private lateinit var front: TlsFrontServer
    private var tlsPort = 0
    private var allowClient = true

    private val directory = object : TlsPeerDirectory {
        override fun deviceIdForPin(pin: String) = if (allowClient && pin == clientIdentity.pin) "dev-client" else null
    }

    private fun freePort() = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    @Before
    fun setUp() {
        val clientStore = TlsIdentityStore(File(temp.root, "client"), emptyList())
        clientIdentity = clientStore.getOrCreate()
        PeerTlsConnector.configure { clientStore }
        val httpPort = freePort()
        val bridgePort = freePort()
        http = embeddedServer(CIO, configure = {
            connector { port = httpPort; host = "127.0.0.1" }
            connector { port = bridgePort; host = "127.0.0.1" }
        }) {
            routing {
                get("/api/v1/membership/chain") { call.respondText("chain for ${TlsBridgeRegistry.lookup(call.request.local.remotePort)?.deviceId}") }
                get("/api/v1/files") { call.respondText("secret") }
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
    }

    @Test
    fun readsTheChainFromAServerWhoseKeyIsNotPinnedAndIdentifiesItself() {
        val reply = kotlinx.coroutines.runBlocking { UnpinnedFetch.get("127.0.0.1", tlsPort, UnpinnedFetch.CHAIN_PATH, 5_000) }!!
        assertEquals(200, reply.statusCode)
        assertEquals("chain for dev-client", reply.body.trim())
    }

    @Test
    fun aMismatchedKeyRaisesNoPromptAndStoresNoPin() {
        kotlinx.coroutines.runBlocking { UnpinnedFetch.get("127.0.0.1", tlsPort, UnpinnedFetch.CHAIN_PATH, 5_000) }
        assertTrue(PeerTlsStatus.prompts.value.isEmpty())
        assertNull(PeerTlsRoutes.lookup("127.0.0.1", 1))
    }

    @Test
    fun onlyTheChainPathCanBeFetchedThisWay() {
        val failure = runCatching { kotlinx.coroutines.runBlocking { UnpinnedFetch.get("127.0.0.1", tlsPort, "/api/v1/files", 5_000) } }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun aServerThatDoesNotKnowThisDeviceGivesNothing() {
        allowClient = false
        assertNull(kotlinx.coroutines.runBlocking { UnpinnedFetch.get("127.0.0.1", tlsPort, UnpinnedFetch.CHAIN_PATH, 5_000) })
    }
}
