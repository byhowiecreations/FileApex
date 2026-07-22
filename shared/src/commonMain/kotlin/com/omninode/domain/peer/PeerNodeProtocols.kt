package com.omninode.domain.peer

/**
 * Capability tokens advertised in [PeerNodeState.supportedProtocols].
 */
object PeerNodeProtocols {
    const val FILES_V1 = "omninode.v1.files"
    const val CLUSTER_V1 = "omninode.v1.cluster"
    const val PAIRING_V1 = "omninode.v1.pairing"
    const val IDENTITY_V1 = "omninode.v1.identity"

    val DEFAULT: List<String> = listOf(
        IDENTITY_V1,
        FILES_V1,
        CLUSTER_V1,
        PAIRING_V1
    )
}
