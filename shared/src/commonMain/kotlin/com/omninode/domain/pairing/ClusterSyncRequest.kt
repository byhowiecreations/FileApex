package com.omninode.domain.pairing

import com.omninode.data.db.PairedDeviceEntity
import kotlinx.serialization.Serializable

/**
 * Introducer shares itself plus a device list so peers can grow a LAN cluster.
 */
@Serializable
data class ClusterSyncRequest(
    val introducer: PairedDeviceEntity,
    val devices: List<PairedDeviceEntity>
)
