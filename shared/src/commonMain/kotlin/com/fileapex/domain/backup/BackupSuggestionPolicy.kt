package com.fileapex.domain.backup

sealed interface BackupStatusKind {
    data class Running(val done: Int, val total: Int) : BackupStatusKind
    data class Synced(val uploaded: Int, val failed: Int) : BackupStatusKind
    data class NeedsAttention(val failed: Int) : BackupStatusKind
    data object Ready : BackupStatusKind
}

/** Pure policy governing the post-pair Android Wi-Fi photo backup prompt and status summary. */
object BackupSuggestionPolicy {
    data class DesktopPeer(val deviceId: String, val name: String)

    fun targetDesktop(
        isAndroid: Boolean,
        pairedDesktops: List<DesktopPeer>,
        config: BackupConfig
    ): DesktopPeer? {
        if (!isAndroid || config.enabled || config.promptDismissed) return null
        return pairedDesktops.firstOrNull { it.deviceId.isNotBlank() }
    }

    fun enableSuggestedConfig(
        current: BackupConfig,
        desktopDeviceId: String,
        presetPaths: List<String>
    ): BackupConfig {
        var next = current.copy(
            enabled = true,
            destinationDeviceId = desktopDeviceId,
            wifiOnly = true
        )
        presetPaths.forEach { path -> next = next.withSource(path) }
        return next
    }
}

object BackupStatusLabelPolicy {
    fun classify(
        config: BackupConfig,
        status: BackupStatus,
        lastRun: BackupSummary?
    ): BackupStatusKind? {
        if (!config.isRunnable) return null
        return when (status) {
            is BackupStatus.Running -> BackupStatusKind.Running(
                done = status.done + 1,
                total = status.total
            )
            BackupStatus.Idle -> when {
                lastRun == null -> BackupStatusKind.Ready
                lastRun.failed > 0 || !lastRun.error.isNullOrBlank() -> BackupStatusKind.NeedsAttention(lastRun.failed)
                else -> BackupStatusKind.Synced(lastRun.uploaded, lastRun.failed)
            }
        }
    }
}
