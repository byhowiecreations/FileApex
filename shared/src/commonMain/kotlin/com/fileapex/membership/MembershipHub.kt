package com.fileapex.membership

/** The signing identity for the membership chain, published by the platform. Its key is not the TLS key. */
object ChainSigning {
    @Volatile
    var provider: () -> EventSigner? = { null }

    /** Replaces the device signing key; set by the platform that holds it. */
    @Volatile
    var renew: () -> Unit = {}
}

/** The running membership chain of this device: what the routes, the screens and the tick talk to. */
class MembershipHub(
    val chainLog: MembershipChainLog,
    val chainReplicator: ChainReplicator,
    val chainTick: ChainTick,
    /** The active devices of the old roster, this one included: who the chain may admit. */
    val chainRoster: suspend () -> Set<String>,
    /** Applies the walked chain to this device: pins into the pin store, a removal of this device into its self-wipe. */
    /** The joiner's old cluster ends because the user paired it into another one: everything of it except [keepId], the host, is let go. */
    private val replaceCluster: suspend (keepId: String) -> Unit = {},
    /** Marks a removal of this device that is already in the chain it just took as history. */
    private val acknowledgeHistory: suspend () -> Unit = {},
    /** The user just paired [peerId] in person with this device. */
    private val markPairedInPerson: suspend (peerId: String, chainSeq: Long) -> Unit = { _, _ -> },
    /** Runs once when the schedule starts: records this device's id, drops another cluster's records and rebuilds the roster from the chain. */
    val prepare: suspend () -> Unit = {},
    /** The id changes other devices asked this one to take; null where no such request can be answered. */
    val identityChanges: IdentityChangeInbox? = null,
    /** This device's id changed and the cluster does not hold the new one: asks a member to continue under it. Returns when answered or given up. */
    val continueUnderNewId: suspend () -> Unit = {},
    val applyChain: suspend () -> Unit
) {
    /** This device was the joiner of an in-person pairing with [hostId]: the host's cluster wins, then the normal join follows. */
    suspend fun onJoinedHost(hostId: String, tlsPin: String) {
        chainReplicator.joinClusterOf(hostId, beforeApply = acknowledgeHistory) { replaceCluster(hostId) }
        onPaired(hostId, tlsPin)
    }

    /** Pairing just finished with [peerId]: tick now so the join is admitted without waiting for the minute schedule. */
    suspend fun onPaired(peerId: String, tlsPin: String) {
        markPairedInPerson(peerId, chainLog.view().state.seq)
        chainTick.notePairedInPerson()
        applyChain()
        chainTick.tick(chainRoster() + peerId, setOf(peerId), tlsPin)
    }
}
