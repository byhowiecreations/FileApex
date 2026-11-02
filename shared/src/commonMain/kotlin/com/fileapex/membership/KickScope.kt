package com.fileapex.membership

/**
 * Whether a Remove of [subjectId] is a kick the rest of that cluster should hear.
 * A pairwise unpair of a peer that never joined this chain (`legacy:` key) is not, even
 * when leftover admitTrusted members sit on the log: this device never exchanged keys
 * with that cluster, so the cluster must not hear the kick.
 */
fun ChainState.removalIsClusterKick(selfId: String, subjectId: String): Boolean {
    if (seq == 0L) return false
    val held = member(subjectId) ?: return false
    return !held.isLegacyAdmit
}

/** True when [deviceId] joined this chain with a real key and is still active. A leftover `legacy:` admit cannot order a kick or a key change. */
fun ChainState.memberCanCommandMembership(deviceId: String): Boolean {
    if (seq == 0L) return false
    val held = member(deviceId) ?: return false
    return held.active && !held.isLegacyAdmit
}
