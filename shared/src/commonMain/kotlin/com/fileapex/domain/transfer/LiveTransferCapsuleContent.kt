package com.fileapex.domain.transfer

import com.fileapex.i18n.AppI18n
import com.fileapex.platform.formatHostFileSize

data class LiveTransferCapsuleContent(
    val title: String,
    val subtitle: String,
    val percentLabel: String,
    val progress: Float,
    val indeterminate: Boolean,
    val isReceiving: Boolean
)

fun liveTransferCapsuleContent(
    stats: LiveTransferStats,
    queueCount: Int,
    activeSendingSummary: String? = null
): LiveTransferCapsuleContent {
    val isSingle = (activeSendingSummary?.contains(",") != true) && queueCount <= 1
    val fallbackTitle = when {
        stats.isReceiving -> AppI18n.t("receiving_file")
        isSingle -> AppI18n.t("sending_file")
        else -> AppI18n.t("sending_files")
    }
    val title = stats.currentFileName.ifBlank { fallbackTitle }
    val indeterminate = stats.totalBytes <= 0L
    val progress = if (indeterminate) 0f else stats.progress.coerceIn(0f, 1f)
    val percentLabel = if (indeterminate) "" else "${(progress * 100).toInt().coerceIn(0, 100)}%"
    val metrics = buildList {
        if (!indeterminate) {
            add("${formatHostFileSize(stats.sentBytes.coerceIn(0L, stats.totalBytes))} / ${formatHostFileSize(stats.totalBytes)}")
        }
        if (stats.speedFormatted.isNotBlank()) add(stats.speedFormatted)
        if (stats.etaFormatted.isNotBlank()) add(stats.etaFormatted)
    }.joinToString(" • ")
    val subtitle = when {
        metrics.isNotBlank() && queueCount > 1 -> "$metrics ($queueCount)"
        metrics.isNotBlank() -> metrics
        queueCount > 1 -> AppI18n.plural("items_in_queue", queueCount, queueCount.toString())
        else -> AppI18n.t("active_transfer")
    }
    return LiveTransferCapsuleContent(
        title = title,
        subtitle = subtitle,
        percentLabel = percentLabel,
        progress = progress,
        indeterminate = indeterminate,
        isReceiving = stats.isReceiving
    )
}
