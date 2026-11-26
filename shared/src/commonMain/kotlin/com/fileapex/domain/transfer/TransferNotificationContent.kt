package com.fileapex.domain.transfer

import com.fileapex.i18n.AppI18n

/** What the ongoing transfer notification shows. */
data class TransferNotificationContent(
    val title: String,
    val subText: String,
    val text: String,
    val percent: Int,
    val indeterminate: Boolean,
    val cancelable: Boolean
)

fun transferNotificationContent(stats: LiveTransferStats, queueCount: Int): TransferNotificationContent {
    val percent = (stats.progress * 100).toInt().coerceIn(0, 100)
    val indeterminate = stats.totalBytes <= 0L
    val parts = buildList {
        if (!indeterminate) add("$percent%")
        if (stats.speedFormatted.isNotBlank()) add(stats.speedFormatted)
        if (stats.etaFormatted.isNotBlank()) add(stats.etaFormatted)
        if (queueCount > 1) add(AppI18n.plural("items_in_queue", queueCount, queueCount.toString()))
    }
    return TransferNotificationContent(
        title = stats.currentFileName.ifBlank { AppI18n.t(if (stats.isReceiving) "receiving_file" else "sending_files") },
        subText = stats.destinationDeviceName,
        text = parts.joinToString(" • ").ifBlank { AppI18n.t("active_transfer") },
        percent = percent,
        indeterminate = indeterminate,
        cancelable = stats.cancelable
    )
}
