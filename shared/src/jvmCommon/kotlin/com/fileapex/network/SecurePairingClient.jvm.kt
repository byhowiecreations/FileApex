package com.fileapex.network

import com.fileapex.domain.pairing.PairingSas
import com.fileapex.domain.pairing.SasDecideRequest
import com.fileapex.domain.pairing.SasOfferRequest
import com.fileapex.domain.pairing.SasOfferResponse
import com.fileapex.domain.pairing.SasRevealRequest
import com.fileapex.domain.pairing.SecurePairRequest
import com.fileapex.domain.pairing.SecurePairResponse
import com.fileapex.domain.pairing.SecurePairingProof
import com.fileapex.security.useAndWipe
import com.fileapex.network.routes.SECURE_PAIRING_DECIDE_PATH
import com.fileapex.network.routes.SECURE_PAIRING_OFFER_PATH
import com.fileapex.network.routes.SECURE_PAIRING_QR_PATH
import com.fileapex.network.routes.SECURE_PAIRING_REVEAL_PATH
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Joiner side of pairing. Every request is a TLS connection that shows this device's key to the host and the host's
 * key to us. Either the QR secret or the number both users compare ties those two keys to this pairing, so a swapped
 * key on either leg is caught. Nothing here reads a hardware value.
 */
internal object SecurePairingClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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

    private class Reply(val status: Int, val body: String)

    /** One TLS connection to the host; [expectedHostPin] empty means the first key seen is taken and then held. */
    private fun connect(request: SecurePairingExchange, expectedHostPin: String, bindIps: List<String>): Pair<SSLSocket, String> {
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
                plain.connect(InetSocketAddress(request.host, request.tlsPort), CONNECT_TIMEOUT_MS)
                plain.soTimeout = request.timeoutMs.coerceIn(1_000L, 180_000L).toInt()
                val ssl = context.socketFactory.createSocket(plain, request.host, request.tlsPort, true) as SSLSocket
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
                println("SecurePairingClient: connect via ${NetworkUtils.lastOctetLabel(localIp)} failed - ${e::class.simpleName}: ${e.message}")
                if (!NetworkUtils.shouldTryNextBindCandidate(localIp, request.host)) break
            }
        }
        throw failure ?: IOException("no usable network interface")
    }

    private fun post(ssl: SSLSocket, request: SecurePairingExchange, path: String, body: String): Reply = ssl.use {
        val bytes = body.encodeToByteArray()
        val head = "POST $path HTTP/1.1\r\nHost: ${request.host}:${request.tlsPort}\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        ssl.outputStream.apply { write(head.encodeToByteArray()); write(bytes); flush() }
        readResponse(ssl)
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): String = json.encodeToString(serializer, value)

    private fun rejected(reply: Reply) = SecurePairingOutcome.Rejected(reply.body.trim().ifEmpty { "http_${reply.status}" })

    /** Runs [block], turning connection and certificate failures into outcomes. */
    private inline fun guarded(block: () -> SecurePairingOutcome): SecurePairingOutcome = try {
        block()
    } catch (e: CertificateException) {
        SecurePairingOutcome.NotVerified(e.message ?: "certificate refused")
    } catch (e: IOException) {
        SecurePairingOutcome.Unreachable("${e::class.simpleName}: ${e.message}")
    }

    suspend fun runQr(
        request: SecurePairingExchange,
        bindIps: List<String> = NetworkUtils.orderBindCandidatesForPeer(request.host)
    ): SecurePairingOutcome = withContext(Dispatchers.IO) {
        val password = SecurePairingProof.passwordFromSecret(request.qrSecret)
            ?: return@withContext SecurePairingOutcome.NotVerified("pairing secret is malformed")
        password.useAndWipe {
            guarded {
                val localPin = PeerTlsConnector.clientIdentity().second
                val (ssl, hostPin) = connect(request, request.qrHostPin, bindIps)
                val nonceA = SecurePairingProof.newNonceHex()
                val scanner = request.scanner.copy(tlsPin = localPin)
                val macA = SecurePairingProof.macA(password, nonceA, hostPin, localPin, request.hostDeviceId, scanner.deviceId)
                val reply = post(
                    ssl, request, SECURE_PAIRING_QR_PATH,
                    encode(SecurePairRequest.serializer(), SecurePairRequest(nonceA, macA, scanner, request.devicePin))
                )
                if (reply.status != 201) return@guarded rejected(reply)
                val answer = runCatching { json.decodeFromString(SecurePairResponse.serializer(), reply.body) }.getOrNull()
                    ?: return@guarded SecurePairingOutcome.NotVerified("host reply was not understood")
                val expected = SecurePairingProof.macB(password, nonceA, answer.nonceB, hostPin, localPin, request.hostDeviceId, scanner.deviceId)
                if (!SecurePairingProof.equal(expected, answer.macB)) {
                    return@guarded SecurePairingOutcome.NotVerified("host did not prove the pairing secret")
                }
                SecurePairingOutcome.Paired(hostPin)
            }
        }
    }

    suspend fun runSas(
        request: SecurePairingExchange,
        onCode: suspend (String) -> Boolean,
        bindIps: List<String> = NetworkUtils.orderBindCandidatesForPeer(request.host)
    ): SecurePairingOutcome = withContext(Dispatchers.IO) {
        try {
            val localPin = PeerTlsConnector.clientIdentity().second
            val scanner = request.scanner.copy(tlsPin = localPin)
            val nonceB = PairingSas.newNonce()
            val commit = PairingSas.commit(nonceB, localPin, scanner.deviceId)

            val (first, hostPin) = connect(request, "", bindIps)
            val offer = post(first, request, SECURE_PAIRING_OFFER_PATH, encode(SasOfferRequest.serializer(), SasOfferRequest(commit, scanner, request.devicePin)))
            if (offer.status != 200) return@withContext rejected(offer)
            val answer = runCatching { json.decodeFromString(SasOfferResponse.serializer(), offer.body) }.getOrNull()
                ?: return@withContext SecurePairingOutcome.NotVerified("host reply was not understood")

            // The host key must be the same on every connection of this pairing.
            val (second, _) = connect(request, hostPin, bindIps)
            val reveal = post(second, request, SECURE_PAIRING_REVEAL_PATH, encode(SasRevealRequest.serializer(), SasRevealRequest(answer.sessionId, nonceB)))
            if (reveal.status != 200) return@withContext rejected(reveal)

            val code = PairingSas.code(hostPin, localPin, request.hostDeviceId, scanner.deviceId, answer.nonceA, nonceB)
            val approved = onCode(code)

            val (third, _) = connect(request, hostPin, bindIps)
            val decide = post(third, request, SECURE_PAIRING_DECIDE_PATH, encode(SasDecideRequest.serializer(), SasDecideRequest(answer.sessionId, approved)))
            when {
                !approved -> SecurePairingOutcome.Declined
                decide.status == 201 -> SecurePairingOutcome.Paired(hostPin)
                else -> rejected(decide)
            }
        } catch (e: CertificateException) {
            SecurePairingOutcome.NotVerified(e.message ?: "certificate refused")
        } catch (e: IOException) {
            SecurePairingOutcome.Unreachable("${e::class.simpleName}: ${e.message}")
        }
    }

    private fun readResponse(socket: Socket): Reply {
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
    private const val MAX_RESPONSE_BYTES = 64 * 1024
}
