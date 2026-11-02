package com.fileapex.domain.peer

/**
 * Capability tokens advertised in [PeerNodeState.supportedProtocols].
 */
object PeerNodeProtocols {
    const val FILES_V1 = "fileapex.v1.files"
    const val CLUSTER_V1 = "fileapex.v1.cluster"
    const val PAIRING_V1 = "fileapex.v1.pairing"
    const val IDENTITY_V1 = "fileapex.v1.identity"
    const val PAIRING_SECURE_V1 = com.fileapex.domain.pairing.SecurePairingProof.PROTOCOL

    /** This build writes and answers the signed membership chain, so it joins with its own signing key. */
    const val MEMBERSHIP_CHAIN_V1 = "fileapex.v2.membership.chain"

    /** True only when the protocol list is known and lacks the chain: an unknown list never counts as an old build. */
    fun knownToLackChain(protocols: List<String>): Boolean = protocols.isNotEmpty() && MEMBERSHIP_CHAIN_V1 !in protocols

    val DEFAULT: List<String> = listOf(
        IDENTITY_V1,
        FILES_V1,
        CLUSTER_V1,
        PAIRING_V1,
        PAIRING_SECURE_V1,
        MEMBERSHIP_CHAIN_V1
    )
}
