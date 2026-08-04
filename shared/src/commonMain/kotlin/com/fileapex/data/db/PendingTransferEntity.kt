package com.fileapex.data.db

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Deferred outbound transfer — stores source paths only (no file copy).
 * Drained when peer presence shows the destination back on local Wi‑Fi.
 */
@Entity(tableName = "pending_transfers")
@Serializable
data class PendingTransferEntity(
    @PrimaryKey val id: String,
    /** Epoch millis when queued (UTC). */
    val createdAtEpochMs: Long,
    /** [PendingTransferStatus.name] */
    val status: String,
    /** [QueuedTransferSourceKind.name] */
    val sourceKind: String,
    /** JSON payload — root paths or serialized [QueuedSourceSnapshot] list. */
    val sourceJson: String,
    /** JSON list of device IDs still waiting for LAN reachability. */
    val pendingDeviceIdsJson: String,
    /** Short label for UI, e.g. "vacation.jpg → iPad". */
    val displayLabel: String,
    val lastAttemptEpochMs: Long = 0L,
    val attemptCount: Int = 0,
    val lastError: String? = null
)

enum class PendingTransferStatus {
    Queued,
    Sending
}

enum class QueuedTransferSourceKind {
    /** [sourceJson] is List<String> absolute root paths — expanded at send time. */
    LocalRoots,
    /** [sourceJson] is List<QueuedSourceSnapshot> — full Multi Copy sources. */
    Sources
}

@Serializable
data class QueuedSourceSnapshot(
    val fileName: String,
    val sizeBytes: Long,
    val absolutePath: String,
    val relativeDestPath: String,
    val remoteHost: String? = null,
    val remotePort: Int? = null
)
