package com.fileapex.ui.transfer

import com.fileapex.domain.transfer.LiveTransferStats
import com.fileapex.domain.transfer.PendingTransferItem
import com.fileapex.domain.transfer.TransferHistoryEntry
import com.fileapex.friends.transfer.FriendTransfer
import com.fileapex.friends.transfer.FriendTransferDirection
import com.fileapex.friends.transfer.FriendTransferStatus
import com.fileapex.i18n.AppI18n
import com.fileapex.ui.formatBytes

typealias Translate = (String, Array<out Any>) -> String

val DefaultTranslate: Translate = { key, args -> AppI18n.t(key, *args) }

/** Seconds without a single byte before a row says so. */
const val TRANSFER_STALL_NOTICE_SECONDS = 15L

object TransferRowMappers {
    private fun percent(progress: Float): String = "${(progress * 100).toInt().coerceIn(0, 100)}%"

    fun fromLive(stats: LiveTransferStats, silentSeconds: Long, fallbackTitle: String, t: Translate): TransferRowModel {
        val known = stats.totalBytes > 0L
        val parts = buildList {
            if (known) add(percent(stats.progress))
            if (stats.speedFormatted.isNotBlank()) add(stats.speedFormatted)
            if (stats.etaFormatted.isNotBlank()) add(stats.etaFormatted)
        }
        return TransferRowModel(
            id = "live",
            title = stats.currentFileName.ifBlank { fallbackTitle },
            peer = stats.destinationDeviceName,
            state = if (stats.isActive) TransferRowState.Active else TransferRowState.Preparing,
            progress = if (known) stats.progress.coerceIn(0f, 1f) else null,
            statusText = parts.joinToString(" • "),
            error = null,
            warning = if (silentSeconds >= TRANSFER_STALL_NOTICE_SECONDS) t("transfer_no_data", arrayOf(silentSeconds.toString())) else null,
            canCancel = stats.cancelable,
            canRetry = false,
            canRemove = false,
            isSend = true,
            cancelPauses = stats.parksOnCancel
        )
    }

    fun fromPending(item: PendingTransferItem, live: LiveTransferStats, t: Translate): TransferRowModel {
        val liveNow = item.isSending && live.isActive
        val state = when {
            item.isSending -> TransferRowState.Active
            item.isPaused -> TransferRowState.Paused
            item.lastError != null -> TransferRowState.Failed
            else -> TransferRowState.Waiting
        }
        val statusText = when {
            item.isSending -> buildList {
                add(t("sending", emptyArray()))
                if (liveNow && live.totalBytes > 0L) add(percent(live.progress))
                if (live.speedFormatted.isNotBlank()) add(live.speedFormatted)
                if (live.etaFormatted.isNotBlank()) add(live.etaFormatted)
            }.joinToString(" • ")
            item.isPaused -> t("queue_paused", emptyArray())
            else -> t("waiting_for_devices", arrayOf(item.pendingDeviceNames.joinToString(", ")))
        }
        return TransferRowModel(
            id = item.id,
            title = item.displayLabel,
            peer = item.pendingDeviceNames.joinToString(", "),
            state = state,
            progress = if (liveNow && live.totalBytes > 0L) live.progress.coerceIn(0f, 1f) else null,
            statusText = statusText,
            error = item.lastError,
            warning = null,
            canCancel = item.isSending,
            canRetry = item.isPaused || item.lastError != null,
            canRemove = true,
            isSend = true,
            cancelPauses = item.isSending
        )
    }

    fun fromFriend(transfer: FriendTransfer, t: Translate): TransferRowModel {
        val send = transfer.direction == FriendTransferDirection.SEND
        val moving = transfer.status == FriendTransferStatus.ACTIVE && transfer.totalBytes > 0
        val statusText = if (moving) {
            val bytes = t("friends_progress", arrayOf(formatBytes(transfer.doneBytes), formatBytes(transfer.totalBytes)))
            if (transfer.speedBps > 0) bytes + " · " + t("friends_speed", arrayOf(formatBytes(transfer.speedBps))) else bytes
        } else {
            t(friendStatusKey(transfer, send), emptyArray())
        }
        return TransferRowModel(
            id = transfer.id,
            title = transfer.fileName,
            peer = transfer.friendLabel,
            state = when (transfer.status) {
                FriendTransferStatus.QUEUED -> TransferRowState.Queued
                FriendTransferStatus.PREPARING -> TransferRowState.Preparing
                FriendTransferStatus.CONNECTING -> TransferRowState.Connecting
                FriendTransferStatus.WAITING -> TransferRowState.Waiting
                FriendTransferStatus.VERIFYING -> TransferRowState.Verifying
                FriendTransferStatus.ACTIVE -> TransferRowState.Active
                FriendTransferStatus.DONE -> TransferRowState.Done
                FriendTransferStatus.REFUSED -> TransferRowState.Refused
                FriendTransferStatus.FAILED -> TransferRowState.Failed
                FriendTransferStatus.CANCELED -> TransferRowState.Canceled
            },
            progress = if (moving) transfer.fraction else null,
            statusText = statusText,
            error = null,
            warning = null,
            canCancel = !transfer.status.finished,
            canRetry = false,
            canRemove = false,
            isSend = send
        )
    }

    fun fromHistory(entry: TransferHistoryEntry, t: Translate): TransferRowModel = TransferRowModel(
        id = entry.id,
        title = entry.fileName,
        peer = entry.deviceName,
        state = TransferRowState.Done,
        progress = null,
        statusText = t("transfer_history_sent", arrayOf(formatBytes(entry.bytes))),
        error = null,
        warning = null,
        canCancel = false,
        canRetry = false,
        canRemove = false,
        isSend = true
    )

    private fun friendStatusKey(t: FriendTransfer, send: Boolean): String = when (t.status) {
        FriendTransferStatus.QUEUED -> "friends_status_queued"
        FriendTransferStatus.PREPARING -> "friends_status_preparing"
        FriendTransferStatus.CONNECTING -> "friends_status_connecting"
        FriendTransferStatus.WAITING -> "friends_status_waiting"
        FriendTransferStatus.VERIFYING -> "friends_status_verifying"
        FriendTransferStatus.ACTIVE -> if (send) "friends_status_sending" else "friends_status_receiving"
        FriendTransferStatus.DONE -> if (send) "friends_status_sent" else "friends_status_received"
        FriendTransferStatus.REFUSED -> when (t.note) { "size" -> "friends_refused_size"; "space" -> "friends_refused_space"; else -> "friends_status_refused" }
        FriendTransferStatus.FAILED -> when (t.note) {
            "unreachable" -> "friends_failed_unreachable"; "timeout" -> "friends_failed_timeout"; "lost" -> "friends_failed_lost"
            "answer" -> "friends_failed_answer"; "file" -> "friends_failed_file"; "gone" -> "friends_failed_gone"
            else -> "friends_status_failed"
        }
        FriendTransferStatus.CANCELED -> "friends_status_canceled"
    }
}
