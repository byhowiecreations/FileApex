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

    /** Same hardware model on the same address under another id: one of the two is an outdated copy. */
    fun sharesAddressAndModel(a: PairedDeviceEntity, b: PairedDeviceEntity): Boolean {
        if (a.deviceId == b.deviceId) return false
        val ipA = a.lastKnownIp.trim()
        if (ipA.isEmpty() || ipA != b.lastKnownIp.trim()) return false
        val modelA = modelKey(a)
        return modelA.isNotEmpty() && modelA == modelKey(b)
    }

    /** Only a device this one verified, and that has no twin on its address, may be introduced. */
    fun mayIntroduce(row: PairedDeviceEntity, roster: List<PairedDeviceEntity>): Boolean =
        reachedDirectly(row) &&
            row.clusterVersion > 0L && ClusterClock.isAcceptable(row.clusterVersion) &&
            roster.none { other -> !other.isRemoved && sharesAddressAndModel(row, other) }

    private fun modelKey(row: PairedDeviceEntity): String {
        val make = row.deviceMake.trim().lowercase()
        val model = row.deviceModel.trim().lowercase()
        return if (make.isEmpty() || model.isEmpty()) "" else "$make|$model"
    }
}
