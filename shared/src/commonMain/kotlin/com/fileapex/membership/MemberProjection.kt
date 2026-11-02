package com.fileapex.membership

/** What this device records about a member outside the chain: an admit it still owes, and the in-person pairing hold. */
data class MemberFacts(
    /** When a key-less peer was paired here and its legacy ADMIT was not on the chain yet; 0 when none. */
    val pendingAdmitAt: Long = 0L,
    /** When the user paired this device in person, and the chain's seq then; 0 when never. */
    val pairedAtEpochMs: Long = 0L,
    val pairedChainSeq: Long = 0L
)

/** Who removed an id before the chain existed, and why; both empty when the removal did not say. */
data class LegacyRemoval(val actor: String = "", val reason: String = "")

/** One row of `members`: membership and identity only. How this device reaches the member lives in `member_local`. */
data class ProjectedMember(
    val deviceId: String,
    val state: MemberState,
    val publicKey: String,
    val eventActor: String,
    val eventReason: String
)

/** `members` as a function of the walked chain and the local facts, so it can be dropped and rebuilt at any time. */
object MemberProjection {
    const val PAIRING_HOLD_MS = 10L * 60_000L
    const val PENDING_ADMIT_MS = 7L * 24 * 60 * 60_000L
    const val LEGACY_REASON = "legacy"

    fun project(view: ChainView, facts: Map<String, MemberFacts>, legacyRemoved: Map<String, LegacyRemoval>, now: Long): List<ProjectedMember> {
        val ids = (view.state.members.keys + facts.keys + legacyRemoved.keys).toSortedSet()
        return ids.mapNotNull { id -> rowFor(id, view, facts[id], legacyRemoved[id], now) }
    }

    private fun rowFor(id: String, view: ChainView, facts: MemberFacts?, legacyRemoved: LegacyRemoval?, now: Long): ProjectedMember? {
        val member = view.state.member(id)
        if (member != null) {
            if (member.active) return active(id, if (member.isLegacyAdmit) "" else member.key)
            val record = view.canonical.lastOrNull { it.subject == id && (it.kind == RecordKind.REMOVE || it.kind == RecordKind.LEAVE) }
            if (record != null && held(facts, record.seq, now)) return active(id, "")
            return ProjectedMember(id, MemberState.REMOVED, "", record?.actor.orEmpty(), record?.reason.orEmpty())
        }
        if (facts != null && facts.pendingAdmitAt > 0L && now - facts.pendingAdmitAt < PENDING_ADMIT_MS) return active(id, "")
        if (legacyRemoved != null) return ProjectedMember(id, MemberState.REMOVED, "", legacyRemoved.actor, legacyRemoved.reason.ifEmpty { LEGACY_REASON })
        return null
    }

    private fun active(id: String, key: String) = ProjectedMember(id, MemberState.ACTIVE, key, "", "")

    /** Held back only while the pairing is recent and the removal was already in the chain when it happened: a later removal or leave is news. */
    fun held(facts: MemberFacts?, removalSeq: Long, now: Long): Boolean =
        facts != null && facts.pairedAtEpochMs > 0L && now - facts.pairedAtEpochMs <= PAIRING_HOLD_MS && removalSeq <= facts.pairedChainSeq
}
