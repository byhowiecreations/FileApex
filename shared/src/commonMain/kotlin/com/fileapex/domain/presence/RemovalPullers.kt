package com.fileapex.domain.presence

import com.fileapex.data.device.DeviceRepository
import com.fileapex.di.FileApexServices
import com.fileapex.domain.pairing.KickedByPeer
import com.fileapex.network.FileApexClient

/**
 * The two ways a peer's removal records are read: when its removal fingerprint differs ([removals], builds that send
 * no digest) and when its cluster digest differs ([cluster], which also asks that peer for introductions).
 */
internal class RemovalPullers(private val repository: DeviceRepository, private val client: FileApexClient) {
    private fun localId() = com.fileapex.data.identity.loadLocalIdentity().deviceId

    val removals = RemovalPuller(
        ourFingerprint = { repository.removalFingerprint() },
        localDeviceId = ::localId,
        fetchRemoved = { host, port -> client.cluster.fetchRemovedDevices(host, port) },
        merge = { request, senderId -> FileApexServices.pairingCoordinator.mergeIncoming(request, authenticatedSenderId = senderId) },
        onSelfRecord = { peer, record -> KickedByPeer.onTombstone(peer, record, repository.selfMembershipVersion()) }
    )

    val cluster = RemovalPuller(
        ourFingerprint = { repository.clusterDigest() },
        localDeviceId = ::localId,
        fetchRemoved = { host, port -> client.cluster.fetchRemovedDevices(host, port) },
        merge = { request, senderId -> FileApexServices.pairingCoordinator.mergeIncoming(request, authenticatedSenderId = senderId) },
        onSelfRecord = { peer, record -> KickedByPeer.onTombstone(peer, record, repository.selfMembershipVersion()) },
        afterRead = { peer -> FileApexServices.pairingCoordinator.catchUpFromPeer(peer) }
    )
}
