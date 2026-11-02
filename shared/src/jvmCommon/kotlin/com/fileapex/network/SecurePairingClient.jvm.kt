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
import com.fileapex.util.NetworkUtils
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLSocket
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

    private fun post(ssl: SSLSocket, request: SecurePairingExchange, path: String, body: String): TlsExchange.Reply = ssl.use {
        val bytes = body.encodeToByteArray()
        val head = "POST $path HTTP/1.1\r\nHost: ${request.host}:${request.tlsPort}\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        ssl.outputStream.apply { write(head.encodeToByteArray()); write(bytes); flush() }
        TlsExchange.readResponse(ssl)
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): String = json.encodeToString(serializer, value)

    private fun rejected(reply: TlsExchange.Reply) = SecurePairingOutcome.Rejected(
        if (com.fileapex.domain.pairing.PairingRefusal.hostIsOlder(reply.status, reply.body)) "pairing_update_host"
        else reply.body.trim().ifEmpty { "http_${reply.status}" }
    )

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
                val (ssl, hostPin) = TlsExchange.connect(request.host, request.tlsPort, request.timeoutMs, request.qrHostPin, bindIps)
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

            val (first, hostPin) = TlsExchange.connect(request.host, request.tlsPort, request.timeoutMs, "", bindIps)
            val offer = post(first, request, SECURE_PAIRING_OFFER_PATH, encode(SasOfferRequest.serializer(), SasOfferRequest(commit, scanner, request.devicePin)))
            if (offer.status != 200) return@withContext rejected(offer)
            val answer = runCatching { json.decodeFromString(SasOfferResponse.serializer(), offer.body) }.getOrNull()
                ?: return@withContext SecurePairingOutcome.NotVerified("host reply was not understood")

            // The host key must be the same on every connection of this pairing.
            val (second, _) = TlsExchange.connect(request.host, request.tlsPort, request.timeoutMs, hostPin, bindIps)
            val reveal = post(second, request, SECURE_PAIRING_REVEAL_PATH, encode(SasRevealRequest.serializer(), SasRevealRequest(answer.sessionId, nonceB)))
            if (reveal.status != 200) return@withContext rejected(reveal)

            val code = PairingSas.code(hostPin, localPin, request.hostDeviceId, scanner.deviceId, answer.nonceA, nonceB)
            val approved = onCode(code)

            val (third, _) = TlsExchange.connect(request.host, request.tlsPort, request.timeoutMs, hostPin, bindIps)
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

}
