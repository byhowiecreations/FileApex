package com.fileapex.network.routes

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.pairing.LanPairingDiscovery
import com.fileapex.domain.pairing.PairingConfirmations
import com.fileapex.domain.pairing.PairingSas
import com.fileapex.domain.pairing.PendingJoin
import com.fileapex.domain.pairing.SasDecideRequest
import com.fileapex.domain.pairing.SasOfferRequest
import com.fileapex.domain.pairing.SasOfferResponse
import com.fileapex.domain.pairing.SasRevealRequest
import com.fileapex.domain.pairing.SecurePairingSessions
import com.fileapex.domain.pairing.SecurePairRequest
import com.fileapex.domain.pairing.SecurePairResponse
import com.fileapex.domain.pairing.SecurePairingProof
import com.fileapex.network.FileApexServer
import com.fileapex.security.tls.BridgedPeer
import com.fileapex.util.cancellableCatching
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The only routes a TLS client that nobody has paired yet may reach. */
const val SECURE_PAIRING_PREFIX = "/api/v1/pairing/secure"
const val SECURE_PAIRING_QR_PATH = "$SECURE_PAIRING_PREFIX/qr"
const val SECURE_PAIRING_OFFER_PATH = "$SECURE_PAIRING_PREFIX/offer"
const val SECURE_PAIRING_REVEAL_PATH = "$SECURE_PAIRING_PREFIX/reveal"
const val SECURE_PAIRING_DECIDE_PATH = "$SECURE_PAIRING_PREFIX/decide"

/**
 * Gate for a request that arrived on the TLS bridge port. A request that did not come through the TLS front, or that
 * names a different device than its certificate, is never served; a key nobody has paired yet reaches the secure
 * pairing route and nothing else. True when the request was answered and must stop here.
 */
internal suspend fun FileApexServer.rejectBridgedRequest(call: ApplicationCall, bridged: BridgedPeer?, path: String): Boolean {
    val claimed = call.request.queryParameters["from"]?.trim().orEmpty().ifEmpty {
        call.request.headers["X-FileApex-Device-Id"]?.trim().orEmpty()
    }
    if (bridged != null && bridged.pairingOnly) {
        if (path.substringBefore('?').startsWith("$SECURE_PAIRING_PREFIX/")) return false
        onLog("Rejected unpaired TLS client outside the pairing route", null)
        call.respond(HttpStatusCode.Forbidden, "pairing_only")
        return true
    }
    if (bridged != null && (claimed.isEmpty() || claimed == bridged.deviceId)) return false
    onLog("Rejected bridged request: identity mismatch", null)
    call.respond(HttpStatusCode.Forbidden, "peer_identity_mismatch")
    return true
}

internal fun Route.registerSecurePairingRoute(server: FileApexServer) {
    post(SECURE_PAIRING_QR_PATH) {
        cancellableCatching {
            val peer = server.securePairingPeer(call) ?: return@cancellableCatching
            val request = server.decodeSecure(call, SecurePairRequest.serializer()) ?: return@cancellableCatching
            val joiner = request.scannerDevice
            val localId = server.identityProvider().deviceId
            if (!server.acceptableJoiner(call, joiner.deviceId, joiner.deviceName, localId, request.pin)) return@cancellableCatching
            val password = LanPairingDiscovery.passwordFor()
            if (password == null) {
                call.respond(HttpStatusCode.Forbidden, "pairing_code_invalid")
                return@cancellableCatching
            }
            val hostPin = com.fileapex.security.tls.LocalTlsInfo.pin
            val expected = SecurePairingProof.macA(password, request.nonceA, hostPin, peer.pin, localId, joiner.deviceId)
            if (!SecurePairingProof.equal(expected, request.macA)) {
                // Wrong secret, or someone swapped a TLS key on the way. Three of these burn the pairing.
                server.onLog("Secure pairing proof failed", null)
                LanPairingDiscovery.recordFailedAttempt()
                call.respond(HttpStatusCode.Forbidden, "pairing_code_invalid")
                return@cancellableCatching
            }
            val nonceB = SecurePairingProof.newNonceHex()
            val macB = SecurePairingProof.macB(password, request.nonceA, nonceB, hostPin, peer.pin, localId, joiner.deviceId)
            val inbound = server.storeJoiner(joiner, peer.pin, call)
            call.respondText(
                server.json.encodeToString(SecurePairResponse.serializer(), SecurePairResponse(nonceB, macB)),
                ContentType.Application.Json,
                HttpStatusCode.Created
            )
            server.finishHostPairing(inbound)
        }.onFailure { error -> server.failSecure(call, SECURE_PAIRING_QR_PATH, error) }
    }

    post(SECURE_PAIRING_OFFER_PATH) {
        cancellableCatching {
            val peer = server.securePairingPeer(call) ?: return@cancellableCatching
            val request = server.decodeSecure(call, SasOfferRequest.serializer()) ?: return@cancellableCatching
            val joiner = request.scannerDevice
            val localId = server.identityProvider().deviceId
            if (!server.acceptableJoiner(call, joiner.deviceId, joiner.deviceName, localId, request.pin)) return@cancellableCatching
            val session = SecurePairingSessions.open(joiner, peer.pin, request.commit)
            if (session == null) {
                call.respond(HttpStatusCode.Conflict, "pairing_busy")
                return@cancellableCatching
            }
            call.respondText(
                server.json.encodeToString(SasOfferResponse.serializer(), SasOfferResponse(session.id, session.nonceA)),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }.onFailure { error -> server.failSecure(call, SECURE_PAIRING_OFFER_PATH, error) }
    }

    post(SECURE_PAIRING_REVEAL_PATH) {
        cancellableCatching {
            val peer = server.securePairingPeer(call) ?: return@cancellableCatching
            val request = server.decodeSecure(call, SasRevealRequest.serializer()) ?: return@cancellableCatching
            val session = SecurePairingSessions.get(request.sessionId)
            // Same TLS key as the offer, and the revealed value must be the one that was committed to.
            if (session == null || session.hostDecision != null || !SecurePairingProof.equal(session.joinerPin, peer.pin) ||
                !PairingSas.commitMatches(session.commit, request.nonceB, session.joinerPin, session.joiner.deviceId)
            ) {
                session?.let { SecurePairingSessions.close(it.id) }
                LanPairingDiscovery.recordFailedAttempt()
                call.respond(HttpStatusCode.Forbidden, "pairing_code_invalid")
                return@cancellableCatching
            }
            val code = PairingSas.code(
                hostPin = com.fileapex.security.tls.LocalTlsInfo.pin, joinerPin = session.joinerPin,
                hostId = server.identityProvider().deviceId, joinerId = session.joiner.deviceId,
                nonceA = session.nonceA, nonceB = request.nonceB
            )
            session.hostDecision = server.serverScope.async(start = CoroutineStart.DEFAULT) {
                PairingConfirmations.board.askHost(
                    PendingJoin(session.joiner.deviceId, session.joiner.deviceName, code),
                    PairingConfirmations.HOST_CONFIRM_TIMEOUT_MS
                )
            }
            call.respond(HttpStatusCode.OK)
        }.onFailure { error -> server.failSecure(call, SECURE_PAIRING_REVEAL_PATH, error) }
    }

    post(SECURE_PAIRING_DECIDE_PATH) {
        cancellableCatching {
            val peer = server.securePairingPeer(call) ?: return@cancellableCatching
            val request = server.decodeSecure(call, SasDecideRequest.serializer()) ?: return@cancellableCatching
            val session = SecurePairingSessions.get(request.sessionId)
            val decision = session?.hostDecision
            if (session == null || decision == null || !SecurePairingProof.equal(session.joinerPin, peer.pin)) {
                call.respond(HttpStatusCode.Forbidden, "pairing_code_invalid")
                return@cancellableCatching
            }
            if (!request.approved) {
                PairingConfirmations.board.decideHost(false)
                SecurePairingSessions.close(session.id)
                LanPairingDiscovery.onHostPairingAccepted()
                call.respond(HttpStatusCode.Forbidden, "pairing_declined")
                return@cancellableCatching
            }
            val hostAccepted = decision.await()
            SecurePairingSessions.close(session.id)
            when (hostAccepted) {
                null -> call.respond(HttpStatusCode.Conflict, "pairing_busy")
                false -> {
                    // A declined or unanswered attempt ends this pairing window.
                    LanPairingDiscovery.onHostPairingAccepted()
                    call.respond(HttpStatusCode.Forbidden, "pairing_declined")
                }
                true -> {
                    val inbound = server.storeJoiner(session.joiner, peer.pin, call)
                    call.respond(HttpStatusCode.Created)
                    server.finishHostPairing(inbound)
                }
            }
        }.onFailure { error -> server.failSecure(call, SECURE_PAIRING_DECIDE_PATH, error) }
    }
}

/** The TLS peer of a secure pairing call, or null after the refusal has been sent. Needs the pairing window open. */
private suspend fun FileApexServer.securePairingPeer(call: ApplicationCall): BridgedPeer? {
    if (pairingThrottle.hitAndCheck(call.request.local.remoteAddress.trim(), com.fileapex.util.TimeUtils.now())) {
        call.respond(HttpStatusCode.TooManyRequests, "slow_down")
        return null
    }
    // Only through the TLS front: the joiner's key comes from the handshake, never from the request.
    val peer = bridgedPeer(call)
    if (peer == null || peer.pin.isEmpty()) {
        call.respond(HttpStatusCode.Forbidden, "tls_required")
        return null
    }
    if (!LanPairingDiscovery.isHostingPairing()) {
        call.respond(HttpStatusCode.Forbidden, "pairing_code_invalid")
        return null
    }
    return peer
}

private suspend fun <T> FileApexServer.decodeSecure(
    call: ApplicationCall,
    serializer: kotlinx.serialization.KSerializer<T>
): T? = runCatching { json.decodeFromString(serializer, call.receiveBoundedText()) }.getOrElse { error ->
    onLog("Invalid secure pairing payload", error)
    call.respond(HttpStatusCode.BadRequest, "Invalid pairing payload")
    null
}

private suspend fun FileApexServer.acceptableJoiner(
    call: ApplicationCall, joinerId: String, joinerName: String, localId: String, devicePin: String
): Boolean {
    if (joinerId.isBlank() || joinerName.isBlank() || joinerId == localId) {
        call.respond(HttpStatusCode.BadRequest, "Missing required device fields")
        return false
    }
    if (!isPeerPinAccepted(devicePin.trim())) {
        call.respond(HttpStatusCode.Forbidden, "pin_required")
        return false
    }
    return true
}

/** The stored key is the one this handshake proved; the key the joiner claims in its record is ignored. */
private suspend fun FileApexServer.storeJoiner(joiner: PairedDeviceEntity, handshakePin: String, call: ApplicationCall): PairedDeviceEntity {
    val inbound = joiner.copy(
        lastKnownIp = inboundPeerLanIpv4(call) ?: joiner.lastKnownIp,
        tlsPin = handshakePin,
        tlsPinAlt = "",
        hardwareHash = ""
    )
    withContext(Dispatchers.IO) { onPairingRespond(inbound) }
    onLog("Paired inbound device ${inbound.deviceName} (${inbound.deviceId})", null)
    return inbound
}

private fun FileApexServer.finishHostPairing(inbound: PairedDeviceEntity) {
    LanPairingDiscovery.onHostPairingAccepted()
    serverScope.launch {
        cancellableCatching {
            withContext(Dispatchers.IO) { onPairingRespondComplete(inbound) }
        }.onFailure { error -> onLog("Post-pairing handshake notification failed", error) }
    }
}

private suspend fun FileApexServer.failSecure(call: ApplicationCall, path: String, error: Throwable) {
    onLog("POST $path failed", error)
    call.respond(HttpStatusCode.InternalServerError, "pairing_failed")
}
