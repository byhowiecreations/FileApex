package com.omninode.domain.pairing

import com.omninode.data.db.PairedDeviceEntity
import com.omninode.domain.peer.PeerNodeState
import kotlinx.serialization.Serializable

@Serializable
enum class PeerSyncEventKind {
    /** Local node publishes its own identity/metadata (heartbeat or rename). */
    SELF_METADATA,
    /** One-time authoritative delta introducing a newly paired peer. */
    PAIRING_INTRO,
    /** Explicit peer removal blocklist propagation. */
    REMOVAL
}

@Serializable
data class RemovedDeviceRecord(
    val deviceId: String,
    val publicKeyHash: String = "",
    val lastKnownIp: String = "",
    val port: Int = 0
)

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
    @Deprecated("Legacy gossip — ignored on ingest")
    val introducer: PairedDeviceEntity? = null,
    @Deprecated("Legacy gossip — ignored on ingest")
    val devices: List<PairedDeviceEntity> = emptyList()
)
