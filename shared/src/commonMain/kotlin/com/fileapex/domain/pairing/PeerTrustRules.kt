package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.peer.ClusterClock

/** The conditions a device must meet before it is trusted, or vouched for to the rest of the cluster. */
object PeerTrustRules {
    /** This device itself reached the peer: presence is only ever stamped by direct contact. */
    fun reachedDirectly(row: PairedDeviceEntity): Boolean = !row.isRemoved && row.lastSeenEpochMs > 0L

    /** A peer that never stamped itself (0) is covered by its introducer's stamp; a stamp that is set must be sane. */
    fun answersOnClusterClock(protocol: Int, version: Long): Boolean =
        protocol >= ClusterClock.MEMBERSHIP_PROTOCOL && (version == 0L || ClusterClock.isAcceptable(version))

    /** The same key under another id: one row is a copy of the other. Address and model are not used. */
    fun samePublicKey(a: PairedDeviceEntity, b: PairedDeviceEntity): Boolean {
        if (a.deviceId == b.deviceId) return false
        val hashA = a.publicKeyHash.trim()
        if (hashA.isNotEmpty() && hashA == b.publicKeyHash.trim()) return true
        val keyA = a.publicKey.trim()
        return keyA.isNotEmpty() && keyA == b.publicKey.trim()
    }

    /** Only a device this one verified, and that is not a second row of the same key, may be introduced. */
    fun mayIntroduce(row: PairedDeviceEntity, roster: List<PairedDeviceEntity>): Boolean =
        reachedDirectly(row) &&
            row.clusterVersion > 0L && ClusterClock.isAcceptable(row.clusterVersion) &&
            roster.none { other -> !other.isRemoved && samePublicKey(row, other) }
}
