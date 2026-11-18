package com.fileapex.domain.transfer

data class PendingTransferItem(
    val id: String,
    val createdAtEpochMs: Long,
    val displayLabel: String,
    val pendingDeviceIds: List<String>,
    val pendingDeviceNames: List<String>,
    val sourceSummary: String,
    val lastError: String?,
    val isSending: Boolean = false,
    val isPaused: Boolean = false
)

/**
 * Outcome of [TransferQueueCoordinator.sendOrQueue], immediate send plus optional queue.
 */
data class QueueAwareSendResult(
    val batch: TransferBatchResult?,
    val queuedDeviceNames: List<String>,
    val message: String,
    val relayedDeviceNames: List<String> = emptyList(),
    val needsCellularConfirm: Boolean = false
) {
    val hadImmediateSend: Boolean get() = batch != null && !batch.allFailed
    val hadQueue: Boolean get() = queuedDeviceNames.isNotEmpty()
    val hadRelay: Boolean get() = relayedDeviceNames.isNotEmpty()
}
