package com.fileapex.membership

/**
 * Pulls over the pinned route and, when that fails (a peer whose TLS key changed is refused by its old pin), over the
 * fallback. Pushes never use the fallback: only signed records are read that way, nothing is sent.
 */
class FallbackChainTransport(private val primary: ChainTransport, private val fallback: ChainTransport) : ChainTransport {
    override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<com.fileapex.domain.peer.PeerNodeState>): ChainAnswer? =
        primary.push(peerId, records, profiles)

    override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<com.fileapex.domain.peer.PeerNodeState>, head: String): ChainAnswer? =
        primary.push(peerId, records, profiles, head)

    override suspend fun pull(peerId: String): ChainAnswer? = primary.pull(peerId) ?: fallback.pull(peerId)

    override suspend fun pull(peerId: String, since: String): ChainAnswer? = primary.pull(peerId, since) ?: fallback.pull(peerId)

    override suspend fun join(peerId: String, key: String, tlsPin: String, profile: com.fileapex.domain.peer.PeerNodeState?): ChainAnswer? =
        primary.join(peerId, key, tlsPin, profile)
}
