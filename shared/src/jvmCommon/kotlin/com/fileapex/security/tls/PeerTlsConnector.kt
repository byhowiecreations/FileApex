package com.fileapex.security.tls

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager

class PeerPinMismatchException(val observedPin: String = "") : CertificateException("peer key does not match its pin")

/** Accepts a server certificate only when its public key pin is one of [pins]. Dates and names are ignored. */
class PinnedServerTrustManager(private val pins: Set<String>) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = verify(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = verify(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = verify(chain)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = unsupported()
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = unsupported()
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = unsupported()

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun unsupported(): Nothing = throw CertificateException("client verification is not handled here")

    private fun verify(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
        val pin = TlsPin.of(leaf.publicKey)
        if (pins.none { TlsPin.matches(it, pin) }) throw PeerPinMismatchException(pin)
    }
}

/**
 * Connects to a peer, over TLS when [PeerTlsRoutes] has a pin for it and over the given plain socket
 * otherwise. A pinned peer is never reached over HTTP: failures surface as exceptions.
 */
object PeerTlsConnector {
    @Volatile
    private var identityStore: (() -> TlsIdentityStore?)? = null

    fun configure(storeProvider: () -> TlsIdentityStore?) {
        identityStore = storeProvider
    }

    /** [socket] must be unconnected (it may already be bound). Returns the socket to read and write on. */
    fun connect(socket: Socket, host: String, httpPort: Int, connectTimeoutMs: Int): Socket {
        val route = PeerTlsRoutes.lookup(host, httpPort)
        if (route == null) {
            socket.connect(InetSocketAddress(host, httpPort), connectTimeoutMs)
            return socket
        }
        return try {
            socket.connect(InetSocketAddress(host, route.tlsPort), connectTimeoutMs)
            val ssl = contextFor(route).socketFactory.createSocket(socket, host, route.tlsPort, true) as SSLSocket
            ssl.enabledProtocols = TlsFrontServer.preferredProtocols(ssl.supportedProtocols)
            socket.soTimeout = connectTimeoutMs
            ssl.startHandshake()
            PeerTlsStatus.reportHealthy(route.deviceId)
            ssl
        } catch (e: Exception) {
            runCatching { socket.close() }
            val mismatch = e.findCause<PeerPinMismatchException>()
            if (mismatch != null) {
                PeerTlsStatus.reportPinMismatch(route.deviceId)
                if (mismatch.observedPin.isNotEmpty()) {
                    PeerTlsStatus.requestConfirmation(
                        TlsPinPrompt(route.deviceId, TlsPromptKind.KEY_CHANGED, mismatch.observedPin, route.tlsPort)
                    )
                }
                println("PeerTls: pin mismatch for ${route.deviceId}, expected ${route.pins.joinToString { TlsPin.forLog(it) }}")
            }
            throw if (e is IOException) e else IOException("TLS connect failed", e)
        }
    }

    /** SSLSocket cannot half-close on every platform; plain sockets can. */
    fun shutdownOutput(socket: Socket) {
        try {
            socket.shutdownOutput()
        } catch (_: UnsupportedOperationException) {
            // A body with a known Content-Length does not need the half-close.
        }
    }

    private fun contextFor(route: PeerTlsRoute): SSLContext {
        val identity = identityStore?.invoke()?.getOrCreate()
            ?: throw IOException("local TLS identity unavailable")
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("tls", identity.privateKey, KEY_PASSWORD, arrayOf(identity.certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, KEY_PASSWORD) }.keyManagers
        // One context per pin set keeps TLS session resumption working across requests.
        return contexts.getOrPut(route.pins to identity.pin) {
            SSLContext.getInstance("TLS").apply {
                init(keyManagers, arrayOf<TrustManager>(PinnedServerTrustManager(route.pins)), null)
            }
        }
    }

    private val contexts = java.util.concurrent.ConcurrentHashMap<Pair<Set<String>, String>, SSLContext>()
    private val KEY_PASSWORD = "fileapex".toCharArray()

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth++ < 8) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }
}
