package com.fileapex.membership

/** A device this node already trusts from its roster: [key] may be empty when that peer never had a chain signing key. */
data class TrustedPeer(
    val deviceId: String,
    val key: String,
    val tlsPin: String,
    /** False for a peer that speaks the chain or whose build is not known yet: it joins with its own key, never key-less. */
    val mayBeLegacy: Boolean = true
)

/**
 * Starts the chain for a cluster that already exists. The device with the lowest id of the old roster writes the
 * genesis; every other roster device is admitted by an active member that was just reached over that device's pinned
 * connection, so the signing key it gives is tied to the device the old cluster already trusted. A device not on the old
 * roster is never admitted this way.
 */
class ChainBootstrap(private val log: MembershipChainLog, private val signer: () -> EventSigner?) {
    /** True when this device wrote the genesis. [capableIds] are the devices that answer for the chain, this one included: a peer on an older build cannot write it. */
    suspend fun createGenesisIfLowest(capableIds: Set<String>, tlsPin: String): Boolean {
        val self = signer() ?: return false
        if (log.view().state.seq != 0L || capableIds.minOrNull() != self.deviceId) return false
        return log.append(self, RecordKind.GENESIS, self.deviceId, self.publicKey, tlsPin, MembershipReason.PAIRED) is ChainVerdict.Accepted
    }

    /** Admits [requesterId] with the signing key it presented; the chain's own rules decide whether this device may. */
    suspend fun admit(requesterId: String, requesterKey: String, requesterTlsPin: String, rosterIds: Set<String>): ChainVerdict {
        val self = signer() ?: return ChainVerdict.Rejected("no signing key")
        if (requesterId !in rosterIds) return ChainVerdict.Rejected("not on the old roster")
        return log.append(self, RecordKind.ADMIT, requesterId, requesterKey, requesterTlsPin, MembershipReason.PAIRED)
    }

    /**
     * Puts this device's already-trusted ACTIVE peers on the chain so a joiner can take their profiles.
     * Peers that never ran the chain have no signing key; [TrustedPeer.key] may be empty and is stored as `legacy:<id>`.
     */
    suspend fun admitTrusted(peers: List<TrustedPeer>): Boolean {
        if (log.view().state.member(signer()?.deviceId ?: return false)?.active != true) return false
        var changed = false
        for (peer in peers) {
            if (peer.deviceId == signer()?.deviceId) continue
            if (log.view().state.member(peer.deviceId)?.active == true) continue
            if (peer.tlsPin.length != 64) continue
            if (peer.key.isBlank() && !peer.mayBeLegacy) continue
            val key = peer.key.ifBlank { "${ChainMember.LEGACY_KEY_PREFIX}${peer.deviceId}" }
            if (admit(peer.deviceId, key, peer.tlsPin, setOf(peer.deviceId)) is ChainVerdict.Accepted) changed = true
        }
        return changed
    }
}
