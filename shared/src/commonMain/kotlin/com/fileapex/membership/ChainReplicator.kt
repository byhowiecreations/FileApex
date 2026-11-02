package com.fileapex.membership

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@kotlinx.serialization.Serializable
data class ChainPush(val records: List<ChainRecord>, val profiles: List<com.fileapex.domain.peer.PeerNodeState> = emptyList())

/**
 * [head] is the hash of the answering device's chain head; [records] is everything it holds, losing fork branches included.
 * [profiles] are names, addresses and ports (see [ChainProfiles]): they are not signed, so a reader takes them only for
 * devices the verified chain holds as active and only over a pinned connection.
 */
@kotlinx.serialization.Serializable
data class ChainAnswer(val head: String, val records: List<ChainRecord>, val profiles: List<com.fileapex.domain.peer.PeerNodeState> = emptyList())

/**
 * What a device tells the cluster about itself and what it takes in, kept apart from membership: a profile fills in
 * names, addresses, ports and keys for a device the chain already holds, and never decides who is a member or which
 * TLS pin is trusted.
 */
interface ChainProfiles {
    /** This device's own profile. */
    suspend fun own(): com.fileapex.domain.peer.PeerNodeState?

    /** This device's own profile followed by those of the active members it holds from direct contact, with no presence. */
    suspend fun outgoing(): List<com.fileapex.domain.peer.PeerNodeState>

    /**
     * Takes in profiles from the verified peer [from], only for devices in [activeIds] and never for this device. A
     * device's own profile (the one with [from] as its id) is what it says about itself; any other is a relayed copy.
     */
    suspend fun incoming(states: List<com.fileapex.domain.peer.PeerNodeState>, activeIds: Set<String>, from: String)

    object None : ChainProfiles {
        override suspend fun own(): com.fileapex.domain.peer.PeerNodeState? = null
        override suspend fun outgoing(): List<com.fileapex.domain.peer.PeerNodeState> = emptyList()
        override suspend fun incoming(states: List<com.fileapex.domain.peer.PeerNodeState>, activeIds: Set<String>, from: String) = Unit
    }
}

/** The wire between two devices. A null answer means the peer could not be reached. */
interface ChainTransport {
    suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<com.fileapex.domain.peer.PeerNodeState>): ChainAnswer?
    suspend fun pull(peerId: String): ChainAnswer?

    /** Asks an active member to admit this device with its signing key. The answer is the member's chain. */
    suspend fun join(peerId: String, key: String, tlsPin: String, profile: com.fileapex.domain.peer.PeerNodeState?): ChainAnswer?
}

@kotlinx.serialization.Serializable
data class ChainJoinRequest(val key: String, val tlsPin: String, val profile: com.fileapex.domain.peer.PeerNodeState? = null)

/**
 * Keeps the devices' chains equal. A new record is pushed at once to the devices it concerns; a device that was away
 * catches up by pulling on its next contact with anyone. Records prove themselves by signature, so what a peer sends is
 * checked and never believed on the strength of the connection.
 */
class ChainReplicator(
    private val log: MembershipChainLog,
    private val transport: ChainTransport,
    /** Called after records from a peer changed the chain, so the roster and the pin store can follow it. */
    private val onChanged: suspend () -> Unit = {},
    val profiles: ChainProfiles = ChainProfiles.None
) {
    suspend fun announce(peerIds: List<String>) {
        val records = log.recordsAfter(0L)
        val own = profiles.outgoing()
        coroutineScope { peerIds.map { id -> async { transport.push(id, records, own) } }.awaitAll() }
    }

    /** True when the peer answered. Takes in what it has and sends it what it lacks. */
    suspend fun reconcile(peerId: String): Boolean {
        val theirs = transport.pull(peerId) ?: return false
        val before = log.view().state.head
        log.ingest(theirs.records)
        val after = log.view().state.head
        if (after != before) onChanged()
        takeProfiles(theirs.profiles, peerId)
        if (after != theirs.head) transport.push(peerId, log.recordsAfter(0L), profiles.outgoing())
        return true
    }

    /**
     * The user paired this device into the cluster of [peerId] in person, so that cluster wins. When the peer's chain has
     * another root, [onReplacing] runs once and this device's own chain is dropped before the peer's is taken. [beforeApply] runs after the peer's chain is stored and before anything applies it, so history can be marked first.
     */
    suspend fun joinClusterOf(peerId: String, beforeApply: suspend () -> Unit = {}, onReplacing: suspend () -> Unit): Boolean {
        val theirs = transport.pull(peerId) ?: return false
        val theirRoot = theirs.records.firstOrNull { it.kind == RecordKind.GENESIS }?.hash()
        val mine = log.rootHash()
        if (mine != null && theirRoot != null && mine != theirRoot) {
            onReplacing()
            log.clear()
        }
        log.ingest(theirs.records)
        beforeApply()
        onChanged()
        takeProfiles(theirs.profiles, peerId)
        return true
    }

    /** Hands profiles that came with a verified answer or push to the profile book, for the devices the chain holds as active. */
    suspend fun takeProfiles(states: List<com.fileapex.domain.peer.PeerNodeState>, from: String) {
        if (states.isNotEmpty()) profiles.incoming(states, log.view().state.activeIds().toSet(), from)
    }

    /** An inbound push. */
    suspend fun receive(from: String, records: List<ChainRecord>, states: List<com.fileapex.domain.peer.PeerNodeState> = emptyList()): ChainAnswer {
        val before = log.view().state.head
        log.ingest(records)
        if (log.view().state.head != before) onChanged()
        takeProfiles(states, from)
        return serve()
    }

    /** [withProfiles] is false for a caller that was removed: it may read the chain and learns nothing about the cluster. */
    suspend fun serve(withProfiles: Boolean = true): ChainAnswer =
        ChainAnswer(log.view().state.head, log.recordsAfter(0L), if (withProfiles) profiles.outgoing() else emptyList())
}
