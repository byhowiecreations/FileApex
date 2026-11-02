package com.fileapex.network

import com.fileapex.security.tls.PeerTlsConnector
import com.fileapex.security.tls.TlsFrontServer
import com.fileapex.security.tls.TlsPin
import com.fileapex.util.NetworkUtils
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * A TLS connection to a peer whose key is not looked up in the pin store: it shows this device's key and takes the
 * peer's key as it is, or holds it to one expected pin. Pairing (the key is tied to a secret or a code) and the signed
 * chain fetch (the data is tied to signatures) use it; neither treats the connection itself as proof.
 */
internal object TlsExchange {
    class Reply(val status: Int, val body: String)

    private class HostKeyTrust(private val expected: String, val observed: AtomicReference<String>) : X509ExtendedTrustManager() {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        private fun check(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
            if (leaf.publicKey.algorithm != "EC") throw CertificateException("unsupported key type")
            val pin = TlsPin.of(leaf.publicKey)
            if (expected.isNotEmpty() && !TlsPin.matches(expected, pin)) throw CertificateException("host key changed during pairing")
            observed.set(pin)
        }
    }

    /** One TLS connection to the host; [expectedHostPin] empty means the first key seen is taken and then held. */
    fun connect(host: String, tlsPort: Int, timeoutMs: Long, expectedHostPin: String, bindIps: List<String>): Pair<SSLSocket, String> {
        val (keyManagers, _) = PeerTlsConnector.clientIdentity()
        val observed = AtomicReference<String>()
        val context = SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf<TrustManager>(HostKeyTrust(expectedHostPin, observed)), null)
        }
        var failure: IOException? = null
        for (localIp in bindIps.ifEmpty { listOf("") }) {
            val plain = Socket()
            try {
                if (localIp.isNotBlank()) plain.bind(InetSocketAddress(localIp, 0))
                plain.connect(InetSocketAddress(host, tlsPort), CONNECT_TIMEOUT_MS)
                plain.soTimeout = timeoutMs.coerceIn(1_000L, 180_000L).toInt()
                val ssl = context.socketFactory.createSocket(plain, host, tlsPort, true) as SSLSocket
                ssl.enabledProtocols = TlsFrontServer.preferredProtocols(ssl.supportedProtocols)
                ssl.startHandshake()
                return ssl to (observed.get() ?: throw CertificateException("no host key seen"))
            } catch (e: CertificateException) {
                runCatching { plain.close() }
                throw e
            } catch (e: IOException) {
                runCatching { plain.close() }
                if (generateSequence<Throwable>(e) { it.cause }.any { it is CertificateException }) {
                    throw CertificateException(e.message, e)
                }
                failure = e
                println("TlsExchange: connect via ${NetworkUtils.lastOctetLabel(localIp)} failed - ${e::class.simpleName}: ${e.message}")
                if (!NetworkUtils.shouldTryNextBindCandidate(localIp, host)) break
            }
        }
        throw failure ?: IOException("no usable network interface")
    }

    fun readResponse(socket: Socket): Reply {
        val raw = ByteArrayOutputStream().also { out ->
            val buffer = ByteArray(4096)
            val input = socket.getInputStream()
            while (true) {
                val read = try { input.read(buffer) } catch (_: IOException) { -1 }
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_RESPONSE_BYTES) break
            }
        }.toByteArray()
        val text = raw.decodeToString()
        val status = text.substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        val head = text.substringBefore("\r\n\r\n").lowercase()
        val rawBody = text.substringAfter("\r\n\r\n", "")
        return Reply(status, if ("transfer-encoding: chunked" in head) dechunk(rawBody) else rawBody)
    }

    private fun dechunk(body: String): String {
        val out = StringBuilder()
        var rest = body
        while (true) {
            val size = rest.substringBefore("\r\n").trim().substringBefore(';').toIntOrNull(16) ?: break
            if (size == 0) break
            val start = rest.indexOf("\r\n") + 2
            out.append(rest, start, minOf(start + size, rest.length))
            rest = rest.substring(minOf(start + size + 2, rest.length))
        }
        return out.toString()
    }

    private const val CONNECT_TIMEOUT_MS = 5_000
    const val MAX_RESPONSE_BYTES = 512 * 1024
}
