package com.fileapex.network

import com.fileapex.data.device.DeviceRepository
import com.fileapex.membership.IdentityChangeAnswer
import com.fileapex.membership.IdentityChangeLink
import com.fileapex.membership.IdentityChangeRequest
import com.fileapex.network.routes.BridgeIdentity

/**
 * Sends the id change request over the connection pinned to the member, under the TLS key this device already had.
 * A null answer means the member could not be reached or did not answer in the expected form; the reason is logged.
 * Any other failure is a bug and propagates to the caller.
 */
internal class HttpIdentityChangeLink(
    private val repository: DeviceRepository,
    private val http: FileApexClient,
    log: (String) -> Unit = { println(it) }
) : IdentityChangeLink {
    private val peers = PeerExchange(log)

    override suspend fun send(peerId: String, request: IdentityChangeRequest): IdentityChangeAnswer? {
        val peer = repository.getDevice(peerId)?.takeUnless { it.isRemoved } ?: return null
        return peers.answer(peerId, "identity change request", okOnly = false, call = {
            http.boundPost(
                host = peer.lastKnownIp, port = peer.port, pathWithQuery = BridgeIdentity.CHANGE_PATH,
                body = http.json.encodeToString(IdentityChangeRequest.serializer(), request),
                contentType = "application/json", timeoutMs = FileApexClient.PEER_REQUEST_TIMEOUT_MS
            )
        }) { http.json.decodeFromString(IdentityChangeAnswer.serializer(), it) }
    }

    override suspend fun status(peerId: String, oldId: String, nonce: String): IdentityChangeAnswer? {
        val peer = repository.getDevice(peerId)?.takeUnless { it.isRemoved } ?: return null
        return peers.answer(peerId, "identity change status question", okOnly = false, call = {
            http.boundGet(
                host = peer.lastKnownIp, port = peer.port,
                pathWithQuery = "${BridgeIdentity.STATUS_PATH}?oldId=$oldId&nonce=$nonce",
                timeoutMs = FileApexClient.PEER_REQUEST_TIMEOUT_MS
            )
        }) { http.json.decodeFromString(IdentityChangeAnswer.serializer(), it) }
    }
}
