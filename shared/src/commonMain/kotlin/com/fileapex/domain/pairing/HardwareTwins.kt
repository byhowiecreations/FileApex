package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.util.NetworkUtils
import com.fileapex.util.cancellableCatching

/**
 * A paired peer over TLS reported the same hardware as an older row that no longer answers: the old
 * id is removed through the chain so the rest of the cluster follows.
 */
object HardwareTwins {
    suspend fun supersede(state: PeerNodeState): Int {
        val hash = state.hardwareHash.trim()
        if (hash.isEmpty()) return 0
        val repo = FileApexServices.deviceRepository
        var replaced = 0
        for (twin in repo.activeDevicesWithHardware(hash, state.deviceId.trim())) {
            if (answersAs(twin)) continue
            FileApexServices.membershipHub?.chainTick?.removeMember(twin.deviceId)
            if (repo.removePermanently(twin.deviceId, RemovalReason.SUPERSEDED.code, FileApexServices.localIdentity.deviceId)) {
                replaced++
            }
        }
        return replaced
    }

    /** True when the device at the row's last address still answers under the row's own id. */
    suspend fun answersAs(row: PairedDeviceEntity): Boolean {
        val host = row.lastKnownIp.trim()
        if (!NetworkUtils.isUsableLanIpv4(host) || row.port <= 0) return false
        val seen = cancellableCatching { FileApexServices.client.fetchPeerNodeState(host, row.port) }.getOrNull() ?: return false
        return seen.deviceId.trim() == row.deviceId
    }
}
