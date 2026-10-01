package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.peer.PeerNodeState
import kotlinx.serialization.Serializable

@Serializable
enum class PeerSyncEventKind {
    /** Local node publishes its own identity/metadata (heartbeat or rename). */
    SELF_METADATA,
    /** One-time delta: introduce a newly paired peer, or seed a newcomer with a direct roster snapshot. */
    PAIRING_INTRO,
    /** Explicit peer removal blocklist propagation. */
    REMOVAL
}

@Serializable
data class RemovedDeviceRecord(
    val deviceId: String,
    val publicKeyHash: String = "",
    val lastKnownIp: String = "",
    val port: Int = 0,
    val clusterVersion: Long = 0L,
    val removedAt: Long? = null,
    /** [com.fileapex.domain.peer.ClusterClock.MEMBERSHIP_PROTOCOL] of the build that stamped the removal. */
    val membershipProtocol: Int = 0
) {
    fun membershipVersion(): Long = maxOf(clusterVersion, removedAt ?: 0L)
}

/**
 * Direct peer metadata delta — receivers ingest [nodeStates] and [removedDevices] only.
 *
 * [introducer] and [devices] are legacy gossip fields ignored on ingest (direct heartbeat discovery).
 */
@Serializable
data class ClusterSyncRequest(
    val eventKind: PeerSyncEventKind = PeerSyncEventKind.SELF_METADATA,
    val nodeStates: List<PeerNodeState> = emptyList(),
    val removedDevices: List<RemovedDeviceRecord> = emptyList(),
    val clusterVersion: Long = 0L,
    @Deprecated("Legacy gossip — ignored on ingest")
    val introducer: PairedDeviceEntity? = null,
    @Deprecated("Legacy gossip — ignored on ingest")
    val devices: List<PairedDeviceEntity> = emptyList()
)
