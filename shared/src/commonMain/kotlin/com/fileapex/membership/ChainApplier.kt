package com.fileapex.membership

/** What the chain changes on this device, kept behind an interface so the rules are testable without a database. */
interface ChainEffects {
    suspend fun knownPin(deviceId: String): String?
    suspend fun setPin(deviceId: String, pin: String)

    /** An active member of the chain has a row in the device list, a stub when nothing else has told this device about it yet. */
    suspend fun ensureProfile(deviceId: String)

    /** This device was removed by [by] in the record [recordHash]: it lets go of its cluster. */
    suspend fun selfRemoved(by: String, recordHash: String)

    /** The hash of the last removal record this device acted on, empty if none. */
    suspend fun handledRemoval(): String
    suspend fun markHandled(recordHash: String)

    /** True when this device's roster already holds [deviceId] as removed or departed. */
    suspend fun knownRemoved(deviceId: String): Boolean

    /** Rebuilds the roster from the chain as it now stands, so a change that moves no pin and removes no one still shows in it. */
    suspend fun refreshRoster()

    /** The user paired [deviceId] in person when the chain was at [chainSeq]: a removal of it already in the chain is held back for a while so it can join first. */
    suspend fun holdRemoval(deviceId: String, chainSeq: Long)

    /** True while the removal record of [deviceId] at [removalSeq] is held back by an in-person pairing. */
    suspend fun removalHeld(deviceId: String, removalSeq: Long): Boolean

    /** The chain removed or released [deviceId] ([by] made the record, [reason] is `user_removed` or `left_cluster`, [tlsPin] is the pin the record names, kept as a revoked pin so the device can still connect and read its removal): the roster follows. */
    suspend fun markRemoved(deviceId: String, by: String, reason: String, tlsPin: String)

    /** The chain seq the cloud record key was made at, or null when this device holds no key. */
    suspend fun clusterKeySeq(): Long? = null

    /** Makes a new cloud record key for the removal recorded at [chainSeq]. */
    suspend fun rotateClusterKey(chainSeq: Long) {}
}

/**
 * Applies the walked chain to this device: an active member's TLS pin in the chain replaces the stored one silently (a
 * leftover `legacy:` admit never signed its pin, so its chain copy only fills a pin this device does not hold yet and
 * never overrides what pairing or the user confirmed), a
 * member the chain removed or released is removed from the roster, and a removal of this device by another member (a record on the chain that won, never one on a losing branch) is reported
 * once. A device that left on its own was already wiped by the Leave action.
 */
class ChainApplier(private val selfId: String, private val effects: ChainEffects) {
    /** The user just paired [deviceId] in person with this device when the chain was at [chainSeq]. For a short while a removal of it that was already in the chain is not applied, so it can join first. */
    suspend fun pairedInPerson(deviceId: String, chainSeq: Long) = effects.holdRemoval(deviceId, chainSeq)

    /** A removal of this device that is already in the chain it just took in person is history, not a kick. */
    suspend fun acknowledgeHistory(view: ChainView) {
        val me = view.state.member(selfId) ?: return
        if (me.active) return
        val removal = view.canonical.lastOrNull { it.subject == selfId && it.kind == RecordKind.REMOVE } ?: return
        effects.markHandled(removal.hash())
    }

    /**
     * The device responsible for a removal makes a new cloud record key once: the author of a `REMOVE`, and for a
     * `LEAVE` the lowest active id. The key records the seq it was made at, so nothing else needs to be stored.
     */
    private suspend fun rotateClusterKeyIfDue(view: ChainView) {
        if (view.state.member(selfId)?.active != true) return
        val lowest = view.state.activeIds().minOrNull()
        val due = view.canonical.filter {
            (it.kind == RecordKind.REMOVE && it.actor == selfId) || (it.kind == RecordKind.LEAVE && lowest == selfId)
        }.maxOfOrNull { it.seq } ?: return
        val held = effects.clusterKeySeq() ?: return
        if (held < due) effects.rotateClusterKey(due)
    }

    suspend fun apply(view: ChainView) {
        // Decided before anything is applied: setting a pin rebuilds the roster, and a removal must still be news here.
        val removals = view.state.members.filter { (id, member) -> id != selfId && !member.active && !effects.knownRemoved(id) }.keys.mapNotNull { id ->
            view.canonical.lastOrNull { it.subject == id && (it.kind == RecordKind.REMOVE || it.kind == RecordKind.LEAVE) }
                ?.takeUnless { effects.removalHeld(id, it.seq) }
        }
        effects.refreshRoster()
        for ((id, member) in view.state.members) {
            if (id == selfId || !member.active || member.tlsPin.isEmpty()) continue
            val known = effects.knownPin(id)
            if (known != member.tlsPin && (known == null || !member.isLegacyAdmit)) effects.setPin(id, member.tlsPin)
            effects.ensureProfile(id)
        }
        for (record in removals) effects.markRemoved(record.subject, record.actor, record.reason, record.tlsPin)
        rotateClusterKeyIfDue(view)
        val me = view.state.member(selfId) ?: return
        if (me.active) return
        val removal = view.canonical.lastOrNull { it.subject == selfId && it.kind == RecordKind.REMOVE } ?: return
        val hash = removal.hash()
        if (effects.handledRemoval() == hash) return
        effects.markHandled(hash)
        effects.selfRemoved(removal.actor, hash)
    }
}
