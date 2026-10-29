package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.util.AddressRedaction
import com.fileapex.domain.pairing.ClusterSyncRequest
import com.fileapex.domain.pairing.PeerSyncEventKind
import com.fileapex.domain.pairing.RemovalPullLedger
import com.fileapex.domain.pairing.RemovedDeviceRecord
import com.fileapex.util.cancellableCatching

internal class RemovalPuller(
    private val ourFingerprint: suspend () -> String,
    private val localDeviceId: () -> String,
    private val fetchRemoved: suspend (host: String, port: Int) -> List<RemovedDeviceRecord>,
    private val merge: suspend (ClusterSyncRequest, authenticatedSenderId: String) -> Unit,
    private val onSelfRecord: (PairedDeviceEntity, RemovedDeviceRecord) -> Unit,
    /** Runs after a successful read, for work that belongs to the same difference (the cluster digest asks for introductions). */
    private val afterRead: suspend (PairedDeviceEntity) -> Unit = {}
) {
    private val ledger = RemovalPullLedger()

    /**
     * A peer whose removal fingerprint differs from ours holds a removal we missed, or the reverse. Read its records
     * once per distinct fingerprint and apply them under the usual rules (a record counts only when its stamp is
     * newer than what we hold for that device). A record naming this device is never merged: it is only passed on as
     * evidence of a removal.
     */
    suspend fun pull(peer: PairedDeviceEntity, endpoint: ResolvedPeerEndpoint, theirs: String) {
        val ours = cancellableCatching { ourFingerprint() }.getOrNull() ?: return
        if (!ledger.shouldPull(peer.deviceId, theirs.trim(), ours)) return
        val localId = localDeviceId()
        cancellableCatching {
            val all = fetchRemoved(endpoint.host, endpoint.port).filter { it.deviceId.isNotBlank() }
            all.filter { it.deviceId == localId }.forEach { onSelfRecord(peer, it) }
            val records = all.filter { it.deviceId != localId }
            if (records.isNotEmpty()) {
                merge(
                    ClusterSyncRequest(
                        eventKind = PeerSyncEventKind.REMOVAL,
                        removedDevices = records,
                        clusterVersion = records.maxOf { it.membershipVersion() }
                    ),
                    peer.deviceId
                )
            }
            afterRead(peer)
        }.onFailure { error ->
            ledger.forget(peer.deviceId)
            println("PeerPresenceMonitor: removal read from ${peer.deviceId} failed: ${AddressRedaction.redact(error.message.orEmpty())}")
        }
    }
}
