package com.fileapex.network

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.security.tls.PeerTlsConnector
import com.fileapex.security.tls.PeerTlsRoutes
import com.fileapex.security.tls.PinnedServerTrustManager
import com.fileapex.security.tls.TlsFrontServer
import com.fileapex.security.tls.TlsIdentityFactory
import com.fileapex.security.tls.TlsIdentityStore
import com.fileapex.security.tls.TlsPeerDirectory
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * A server reached the way a paired peer reaches it, through the pinned TLS front, on the LAN or the tailnet alike.
 * The caller is identified as [clientDeviceId] by its client certificate. [connection] opens a request as that caller,
 * and the production client reaches the server at ("127.0.0.1", [httpPort]) through the registered route.
 */
internal class PinnedPeerFixture(
    private val clientDeviceId: String = "self-test",
    makeServer: (httpPort: Int, bridgePort: Int) -> FileApexServer
) {
    private val serverIdentity = TlsIdentityFactory.generate()
    private val clientIdentity = clientStore.getOrCreate()
    val httpPort: Int = freePort()
    private val bridgePort: Int = freePort()
    val server: FileApexServer = makeServer(httpPort, bridgePort)
    private val directory = object : TlsPeerDirectory {
        override fun deviceIdForPin(pin: String) = if (pin == clientIdentity.pin) clientDeviceId else null
    }
    private val front = TlsFrontServer({ serverIdentity }, directory, "127.0.0.1", 0, bridgePort) { _, _ -> }
    private var tlsPort = 0

    fun start() {
        server.start()
        Thread.sleep(500)
        tlsPort = front.start()
        PeerTlsConnector.configure { clientStore }
        active += this
        publishRoutes()
    }

    fun stop() {
        active -= this
        publishRoutes()
        front.stop()
        server.stop()
    }

    private fun route() = PairedDeviceEntity(
        deviceId = "fixture-server-$httpPort", deviceName = "Server", lastKnownIp = "127.0.0.1", port = httpPort,
        publicKeyHash = "", rootPath = "/", tlsPin = serverIdentity.pin, tlsPort = tlsPort
    )

    /** A request to [pathAndQuery] over TLS, with the client certificate presented and the server pinned. */
    fun connection(pathAndQuery: String): HttpURLConnection {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("k", clientIdentity.privateKey, "pw".toCharArray(), arrayOf(clientIdentity.certificate))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, "pw".toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply {
            init(keys.keyManagers, arrayOf<TrustManager>(PinnedServerTrustManager(setOf(serverIdentity.pin))), null)
        }
        return (URL("https://127.0.0.1:$tlsPort$pathAndQuery").openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = context.socketFactory
            setHostnameVerifier { _, _ -> true }
        }
    }

    /** A plain-HTTP request straight to the share port: nobody has proved who is calling. */
    fun anonymous(pathAndQuery: String): HttpURLConnection =
        URL("http://127.0.0.1:$httpPort$pathAndQuery").openConnection() as HttpURLConnection

    private fun freePort() = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    private companion object {
        /** One client identity for every fixture in the process, as a real device has one. */
        val clientStore = TlsIdentityStore(Files.createTempDirectory("fileapex-fixture").toFile(), emptyList())
        val active = java.util.concurrent.CopyOnWriteArrayList<PinnedPeerFixture>()

        fun publishRoutes() = PeerTlsRoutes.update(active.map { it.route() })
    }
}
