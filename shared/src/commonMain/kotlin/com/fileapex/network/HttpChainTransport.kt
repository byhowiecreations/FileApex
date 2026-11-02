package com.fileapex.network

import com.fileapex.data.device.DeviceRepository
import com.fileapex.membership.ChainAnswer
import com.fileapex.membership.ChainJoinRequest
import com.fileapex.membership.ChainPush
import com.fileapex.membership.ChainRecord
import com.fileapex.membership.ChainTransport
import com.fileapex.util.cancellableCatching

/** Chain push and pull over the same pinned connections as every other peer request. A null answer means unreachable. */
internal class HttpChainTransport(
    private val repository: DeviceRepository,
    private val http: FileApexClient
) : ChainTransport {
    override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<com.fileapex.domain.peer.PeerNodeState>): ChainAnswer? {
        val peer = repository.getDevice(peerId)?.takeUnless { it.isRemoved } ?: return null
        return cancellableCatching {
            val response = http.boundPost(
                host = peer.lastKnownIp,
                port = peer.port,
                pathWithQuery = PATH,
                body = http.json.encodeToString(ChainPush.serializer(), ChainPush(records, profiles)),
                contentType = "application/json",
                timeoutMs = FileApexClient.PEER_REQUEST_TIMEOUT_MS
            )
            http.requireSuccess(response, "Chain push failed (${response.statusCode})")
            http.json.decodeFromString(ChainAnswer.serializer(), response.body)
        }.getOrNull()
    }

    override suspend fun pull(peerId: String): ChainAnswer? {
        val peer = repository.getDevice(peerId)?.takeUnless { it.isRemoved } ?: return null
        return cancellableCatching {
            val response = http.boundGet(
                host = peer.lastKnownIp,
                port = peer.port,
                pathWithQuery = PATH,
                timeoutMs = FileApexClient.PEER_REQUEST_TIMEOUT_MS
            )
            http.requireSuccess(response, "Chain pull failed (${response.statusCode})")
            http.json.decodeFromString(ChainAnswer.serializer(), response.body)
        }.getOrNull()
    }

    override suspend fun join(peerId: String, key: String, tlsPin: String, profile: com.fileapex.domain.peer.PeerNodeState?): ChainAnswer? {
        val peer = repository.getDevice(peerId)?.takeUnless { it.isRemoved } ?: return null
        return cancellableCatching {
            val response = http.boundPost(
                host = peer.lastKnownIp,
                port = peer.port,
                pathWithQuery = JOIN_PATH,
                body = http.json.encodeToString(ChainJoinRequest.serializer(), ChainJoinRequest(key, tlsPin, profile)),
                contentType = "application/json",
                timeoutMs = FileApexClient.PEER_REQUEST_TIMEOUT_MS
            )
            http.requireSuccess(response, "Chain join failed (${response.statusCode})")
            http.json.decodeFromString(ChainAnswer.serializer(), response.body)
        }.getOrNull()
    }

    private companion object {
        const val PATH = "/api/v1/membership/chain"
        const val JOIN_PATH = "/api/v1/membership/chain/join"
    }
}
