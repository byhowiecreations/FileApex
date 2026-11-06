package com.fileapex.membership

/**
 * What one device does for the chain on each tick: the lowest-id device among those that answer for the chain (a peer on
 * an older build does not) writes the genesis, but only after it has pulled from the others and found no chain, every
 * device trades records with the peers that are online, and a roster device that was never in the chain asks an active
 * member to admit it, and a removal that lost a fork is issued again by its author. A device that was removed is in the chain as inactive and asks again only with a new signing key, which it gets when it pairs again.
 */
class ChainTick(
    private val selfId: String,
    private val log: MembershipChainLog,
    val replicator: ChainReplicator,
    private val transport: ChainTransport,
    private val bootstrap: ChainBootstrap,
    private val signer: () -> EventSigner?,
    /** ACTIVE roster peers this device already holds a pin for, including those that cannot answer the chain. */
    private val trustedPeers: suspend () -> List<TrustedPeer> = { emptyList() },
    /** Gives this device a new signing key. Used only when the user pairs it in person after the chain removed it under its current key. */
    private val renewSigningKey: suspend () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val marks: PairingMarks = InMemoryPairingMarks(),
    /** Reads peers' state fields and decides which peers still need a request; without one every peer is asked each tick. */
    private val watch: ChainStateWatch? = null,
    /** Called after this device changed the chain itself, so the roster and the pin store follow it at once. */
    private val onChanged: suspend () -> Unit = {}
) {
    /** The user just paired this device with another one in person: for a short while a removal of this device under its current key may be answered with a new key. */
    suspend fun notePairedInPerson() = marks.markPaired(now())

    private suspend fun pairedInPersonRecently(): Boolean = marks.pairedAt().let { it > 0L && now() - it <= PAIRING_WINDOW_MS }

    /**
     * Every roster peer is tried, the ones seen online first: a removed device is answered `401` on health and a peer
     * whose TLS key changed while this device was away fails the pinned handshake, and neither is in [onlineIds].
     */
    suspend fun tick(rosterIds: Set<String>, onlineIds: Set<String>, tlsPin: String) {
        val peers = (onlineIds + rosterIds).filter { it != selfId }
        val answering = watch?.tickPeers(peers) ?: peers.filter { replicator.reconcile(it) }.toSet()
        if (watch != null && watch.safetyNetDue()) watch.runSafetyNet(peers.filter { it in onlineIds })
        if (peers.isNotEmpty()) bootstrap.createGenesisIfLowest(answering + selfId, tlsPin)
        val state = log.view().state
        announceOwnPin(state, tlsPin)
        if (bootstrap.admitTrusted(trustedPeers())) {
            onChanged()
            replicator.announce(log.view().state.activeIds().filter { it != selfId })
        }
        reissueLostRemovals(state)
        val me = state.member(selfId)
        if (state.seq == 0L) return
        if (me?.active == true) {
            // A key-less member that now holds a signing key asks an active member to bind it, so it keeps its place.
            val key = signer()?.publicKey ?: return
            if (me.isLegacyAdmit && me.key != key) askToJoin(peers, state, key, tlsPin)
            return
        }
        var key = signer()?.publicKey ?: return
        if (me != null && me.key == key) {
            if (!pairedInPersonRecently()) return
            marks.clear()
            renewSigningKey()
            key = signer()?.publicKey ?: return
            if (me.key == key) return
        }
        askToJoin(peers, state, key, tlsPin)
    }

    /** Asks the active members that hold a key of their own, one after another, until the chain has this device with [key]. */
    private suspend fun askToJoin(peers: List<String>, state: ChainState, key: String, tlsPin: String) {
        for (peerId in peers.filter { state.member(it)?.let { member -> member.active && !member.isLegacyAdmit } == true }) {
            val answer = transport.join(peerId, key, tlsPin, replicator.profiles.own())
            if (answer == null) continue
            log.ingest(answer.records)
            onChanged()
            replicator.takeProfiles(answer.profiles, peerId)
            if (log.view().state.member(selfId)?.let { it.active && it.key == key } == true) return
        }
    }

    /** The user pressed Remove. The record goes to every device that was active, the removed one included, so it can let go at once. */
    suspend fun removeMember(subjectId: String): ChainVerdict {
        val self = signer() ?: return ChainVerdict.Rejected("no signing key")
        val before = log.view().state
        val subject = before.member(subjectId) ?: return ChainVerdict.Rejected("not in the chain")
        val verdict = log.append(self, RecordKind.REMOVE, subjectId, subject.key, subject.tlsPin, MembershipReason.USER_REMOVED)
        if (verdict is ChainVerdict.Accepted) replicator.announce(before.activeIds().filter { it != selfId }, removed = setOf(subjectId))
        return verdict
    }

    /**
     * The user confirmed an id change on this device: the old id is removed and the new one admitted with the same keys in
     * one step, then every other member is told. The request must already have passed [IdentityChangeProof.verify].
     */
    suspend fun confirmIdentityChange(request: IdentityChangeRequest): ChainVerdict {
        val self = signer() ?: return ChainVerdict.Rejected("no signing key")
        val before = log.view().state
        val old = before.member(request.oldId) ?: return ChainVerdict.Rejected("not in the chain")
        val verdict = log.appendAll(
            self,
            listOf(
                MembershipChainLog.Draft(RecordKind.REMOVE, request.oldId, old.key, old.tlsPin, MembershipReason.USER_REMOVED),
                MembershipChainLog.Draft(RecordKind.ADMIT, request.newId, request.signingKey, old.tlsPin, MembershipReason.PAIRED)
            )
        )
        if (verdict is ChainVerdict.Accepted) {
            onChanged()
            replicator.announce(before.activeIds().filter { it != selfId && it != request.oldId } + request.newId)
        }
        return verdict
    }

    /** The user left the cluster from Settings. */
    suspend fun leave(): ChainVerdict {
        val self = signer() ?: return ChainVerdict.Rejected("no signing key")
        val before = log.view().state
        val me = before.member(selfId) ?: return ChainVerdict.Rejected("not in the chain")
        val verdict = log.append(self, RecordKind.LEAVE, selfId, me.key, me.tlsPin, MembershipReason.LEFT_CLUSTER)
        if (verdict is ChainVerdict.Accepted) replicator.announce(before.activeIds().filter { it != selfId })
        return verdict
    }

    /**
     * A removal this device made can lose a fork against another member's record made at the same time and drop out of
     * the chain. While its subject is still a member holding the key the removal named, the author issues it again on top of the chain as it stands; a device that came back with a new key is not the one that was removed.
     */
    private suspend fun reissueLostRemovals(state: ChainState) {
        if (state.member(selfId)?.active != true) return
        val inChain = log.view().canonical.map { it.hash() }.toSet()
        log.recordsAfter(0L)
            .filter { it.actor == selfId && it.kind == RecordKind.REMOVE && it.hash() !in inChain }
            .filter { record -> state.member(record.subject)?.let { it.active && it.key == record.subjectKey } == true }
            .map { it.subject }.distinct()
            .forEach { removeMember(it) }
    }

    /** This device's TLS key changed since the chain last heard of it: the new pin goes into a record signed with the signing key. */
    private suspend fun announceOwnPin(state: ChainState, tlsPin: String) {
        val me = state.member(selfId)?.takeIf { it.active } ?: return
        if (tlsPin.isEmpty() || me.tlsPin == tlsPin) return
        val self = signer() ?: return
        log.append(self, RecordKind.TLS_PIN, selfId, me.key, tlsPin, MembershipReason.TLS_PIN)
        replicator.announce(state.activeIds().filter { it != selfId })
    }

    /** A roster device asked this device to admit it. The answer carries the chain either way. */
    suspend fun receiveJoin(from: String, key: String, tlsPin: String, rosterIds: Set<String>, profile: com.fileapex.domain.peer.PeerNodeState? = null): ChainAnswer {
        val verdict = bootstrap.admit(from, key, tlsPin, rosterIds)
        val admitted = verdict is ChainVerdict.Accepted
        val trusted = bootstrap.admitTrusted(trustedPeers())
        if (admitted || trusted) {
            onChanged()
            if (admitted) replicator.takeProfiles(listOfNotNull(profile), from)
            replicator.announce(log.view().state.activeIds().filter { it != selfId && it != from })
        }
        return replicator.serve()
    }

    private companion object {
        const val PAIRING_WINDOW_MS = 10L * 60_000L
    }
}
