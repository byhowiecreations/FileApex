package com.fileapex.data.db

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Reserved for deferred LAN control-plane delivery (schema v7).
 * Table may contain rows from prior 0.6.10a builds; this release does not enqueue new work.
 */
@Entity(tableName = "pending_control_deliveries")
@Serializable
data class PendingControlDeliveryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val targetDeviceId: String,
    val payloadJson: String,
    val preferPlaintextFirst: Boolean,
    val createdAtEpochMs: Long,
    val lastAttemptEpochMs: Long = 0L,
    val attemptCount: Int = 0
)
