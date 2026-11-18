package com.fileapex.network

import com.fileapex.data.device.DeviceRepository
import com.fileapex.membership.ChainAnswer
import com.fileapex.membership.ChainRecord
import com.fileapex.membership.ChainTransport

/** Reads a peer's signed chain on its TLS port without holding it to the stored pin. It cannot push. */
internal class UnpinnedChainTransport(
    private val repository: DeviceRepository,
    private val http: FileApexClient,
    log: (String) -> Unit = { println(it) }
) : ChainTransport {
    private val peers = PeerExchange(log)


    override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<com.fileapex.domain.peer.PeerNodeState>): ChainAnswer? = null

    override suspend fun join(peerId: String, key: String, tlsPin: String, profile: com.fileapex.domain.peer.PeerNodeState?): ChainAnswer? = null

    override suspend fun pull(peerId: String): ChainAnswer? {
        val peer = repository.getDevice(peerId)?.takeUnless { it.isRemoved } ?: return null
        if (peer.tlsPort !in 1..65535) return null
        return peers.answer(peerId, "unpinned chain read", okOnly = true, call = {
            peerChainGetUnpinned(peer.lastKnownIp, peer.tlsPort, FileApexClient.PEER_REQUEST_TIMEOUT_MS)
                ?: throw PeerUnreachableException("no answer")
        }) { body -> decode(PeerBoundHttpResponse(200, body), http.json) ?: throw kotlinx.serialization.SerializationException("not a chain") }
    }

    internal companion object {
        /** The chain in a good answer, or null for no answer, an error status or a body that is not a chain. Profiles are dropped: this route proves nothing about who answered. */
        fun decode(response: PeerBoundHttpResponse?, json: kotlinx.serialization.json.Json): ChainAnswer? {
            if (response == null || response.statusCode != 200) return null
            return try {
                json.decodeFromString(ChainAnswer.serializer(), response.body).copy(profiles = emptyList())
            } catch (_: kotlinx.serialization.SerializationException) {
                null
            }
        }
    }
}
