package com.fileapex.security.tls

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/** Drives the real Swift client (libFileApexTray.dylib) against the Kotlin TLS front. Mac only. */
class MacNativeTlsClientTest {
    private interface Native_ : Library {
        fun fileapex_lan_tls_set_routes(json: String): Int
        fun fileapex_lan_tls_set_identity(p12: Pointer, p12Len: Int, password: String): Int
        fun fileapex_lan_http_execute(
            method: String, url: String, contentType: String?, body: Pointer?, bodyLen: Int, timeoutMs: Int,
            outStatus: IntByReference, outBody: PointerByReference, outBodyLen: IntByReference
        ): Int
        fun fileapex_lan_http_free(ptr: Pointer?)
        fun fileapex_lan_tls_take_mismatch(host: String, port: Int, out: Pointer, outLen: Int): Int
    }

    private lateinit var lib: Native_
    private lateinit var http: EmbeddedServer<*, *>
    private lateinit var front: TlsFrontServer
    private val serverIdentity = TlsIdentityFactory.generate()
    private val clientIdentity = TlsIdentityFactory.generate()
    private var httpPort = 0
    private var bridgePort = 0
    private var tlsPort = 0

    private fun freePort() = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    @Before
    fun setUp() {
        val dylib = File("../macos/build/Tray/libFileApexTray.dylib").absoluteFile
        assumeTrue(System.getProperty("os.name").contains("Mac") && dylib.isFile)
        lib = Native.load(dylib.absolutePath, Native_::class.java)
        httpPort = freePort()
        bridgePort = freePort()
        http = embeddedServer(CIO, configure = {
            connector { port = httpPort; host = "127.0.0.1" }
            connector { port = bridgePort; host = "127.0.0.1" }
        }) {
            routing {
                get("/who") {
                    val peer = TlsBridgeRegistry.lookup(call.request.local.remotePort)
                    call.respondText("peer=${peer?.deviceId}")
                }
            }
        }.start(wait = false)
        val directory = object : TlsPeerDirectory {
            override fun deviceIdForPin(pin: String) = if (pin == clientIdentity.pin) "mac-client" else null
        }
        front = TlsFrontServer({ serverIdentity }, directory, "127.0.0.1", 0, bridgePort) { _, _ -> }
        tlsPort = front.start()
        Thread.sleep(300)
    }

    @After
    fun tearDown() {
        if (::front.isInitialized) front.stop()
        if (::http.isInitialized) http.stop(0, 0)
    }

    private fun routes(pin: String) =
        """[{"host":"127.0.0.1","port":$httpPort,"tlsPort":$tlsPort,"pins":["$pin"]}]"""

    private fun loadIdentity(identity: TlsIdentity): Int {
        val p12 = TlsPkcs12.export(identity, "pw".toCharArray())
        val memory = Memory(p12.size.toLong()).also { it.write(0, p12, 0, p12.size) }
        return lib.fileapex_lan_tls_set_identity(memory, p12.size, "pw")
    }

    private fun get(): Pair<Int, String>? {
        val status = IntByReference()
        val body = PointerByReference()
        val len = IntByReference()
        val rc = lib.fileapex_lan_http_execute("GET", "http://127.0.0.1:$httpPort/who", null, null, 0, 8000, status, body, len)
        if (rc != 0) return null
        val text = body.value?.getByteArray(0, len.value)?.toString(Charsets.UTF_8).orEmpty()
        lib.fileapex_lan_http_free(body.value)
        return status.value to text
    }

    @Test
    fun nativeClientReachesPinnedPeerOverTlsWithItsClientCertificate() {
        assertEquals(0, loadIdentity(clientIdentity))
        assertEquals(0, lib.fileapex_lan_tls_set_routes(routes(serverIdentity.pin)))
        assertEquals(200 to "peer=mac-client", get())
    }

    @Test
    fun nativeClientRejectsAServerWithTheWrongKey() {
        assertEquals(0, loadIdentity(clientIdentity))
        assertEquals(0, lib.fileapex_lan_tls_set_routes(routes(TlsIdentityFactory.generate().pin)))
        assertEquals(null, get())
        // The key the peer actually presented is kept so the user can be asked about it.
        val buffer = Memory(128)
        assertEquals(0, lib.fileapex_lan_tls_take_mismatch("127.0.0.1", httpPort, buffer, 128))
        assertEquals(serverIdentity.pin, buffer.getString(0))
        assertEquals(-1, lib.fileapex_lan_tls_take_mismatch("127.0.0.1", httpPort, buffer, 128))
    }

    @Test
    fun nativeClientNeverFallsBackToHttpWhenThePinnedTlsPortIsDown() {
        assertEquals(0, loadIdentity(clientIdentity))
        val deadPort = freePort()
        val dead = """[{"host":"127.0.0.1","port":$httpPort,"tlsPort":$deadPort,"pins":["${serverIdentity.pin}"]}]"""
        assertEquals(0, lib.fileapex_lan_tls_set_routes(dead))
        // The plain HTTP port is answering, yet the pinned peer must not be reached over it.
        assertEquals(null, get())
    }

    @Test
    fun nativeClientUsesPlainHttpForPeersWithoutAPin() {
        assertEquals(0, lib.fileapex_lan_tls_set_routes("[]"))
        val plain = get()
        assertEquals(200, plain?.first)
        assertTrue(plain?.second?.contains("peer=null") == true)
    }
}
