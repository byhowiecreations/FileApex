package com.fileapex.domain.transfer

import com.fileapex.data.db.PendingTransferDao
import com.fileapex.data.db.PendingTransferStatus

/**
 * Bulk pause/resume for all active or paused queue rows, used by quick widgets and controls.
 * Returns `true` if queued/sending rows were paused, `false` if paused rows were resumed, or `null` when idle.
 */
internal suspend fun toggleQueuePauseAll(
    dao: PendingTransferDao,
    cancelSending: (String) -> Boolean,
    scheduleDrain: () -> Unit,
    pauseErrorTag: String
): Boolean? {
    val queued = dao.listByStatus(PendingTransferStatus.Queued.name)
    val sending = dao.listByStatus(PendingTransferStatus.Sending.name)
    if (queued.isNotEmpty() || sending.isNotEmpty()) {
        for (entity in queued) {
            dao.upsert(entity.copy(status = PendingTransferStatus.Paused.name, lastError = pauseErrorTag))
        }
        for (entity in sending) {
            if (!cancelSending(entity.id)) {
                dao.upsert(entity.copy(status = PendingTransferStatus.Paused.name, lastError = pauseErrorTag))
            }
        }
        return true
    }
    val paused = dao.listByStatus(PendingTransferStatus.Paused.name)
    if (paused.isEmpty()) return null
    for (entity in paused) {
        dao.upsert(
            entity.copy(
                status = PendingTransferStatus.Queued.name,
                lastError = null,
                lastAttemptEpochMs = 0L
            )
        )
    }
    scheduleDrain()
    return false
}
