package com.fileapex.network

import com.fileapex.data.device.DeviceRepository
import com.fileapex.membership.IdentityChangeAnswer
import com.fileapex.membership.IdentityChangeLink
import com.fileapex.membership.IdentityChangeRequest
import com.fileapex.network.routes.BridgeIdentity
import com.fileapex.util.cancellableCatching

/** Sends the id change request over the connection pinned to the member, under the TLS key this device already had. */
internal class HttpIdentityChangeLink(
    private val repository: DeviceRepository,
    private val http: FileApexClient
) : IdentityChangeLink {
    override suspend fun send(peerId: String, request: IdentityChangeRequest): IdentityChangeAnswer? {
        val peer = repository.getDevice(peerId)?.takeUnless { it.isRemoved } ?: return null
        return cancellableCatching {
            val response = http.boundPost(
                host = peer.lastKnownIp, port = peer.port, pathWithQuery = BridgeIdentity.CHANGE_PATH,
                body = http.json.encodeToString(IdentityChangeRequest.serializer(), request),
                contentType = "application/json", timeoutMs = FileApexClient.PEER_REQUEST_TIMEOUT_MS
            )
            http.json.decodeFromString(IdentityChangeAnswer.serializer(), response.body)
        }.getOrNull()
    }

    override suspend fun status(peerId: String, oldId: String, nonce: String): IdentityChangeAnswer? {
        val peer = repository.getDevice(peerId)?.takeUnless { it.isRemoved } ?: return null
        return cancellableCatching {
            val response = http.boundGet(
                host = peer.lastKnownIp, port = peer.port,
                pathWithQuery = "${BridgeIdentity.STATUS_PATH}?oldId=$oldId&nonce=$nonce",
                timeoutMs = FileApexClient.PEER_REQUEST_TIMEOUT_MS
            )
            http.json.decodeFromString(IdentityChangeAnswer.serializer(), response.body)
        }.getOrNull()
    }
}
