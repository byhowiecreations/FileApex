package com.fileapex.platform

import com.fileapex.domain.backup.BackupConfig

/** Folder backup ships on Android first; the settings entry is hidden elsewhere. */
expect fun backupSupported(): Boolean

/** Private folder that holds the per-destination manifests. */
expect fun backupStateDirectory(): String

/** Keeps the OS scheduler in step with [BackupConfig]: periodic runs while it is runnable, none otherwise. */
expect object BackupScheduler {
    fun apply(config: BackupConfig)

    /** Starts a run as soon as the network rules allow. */
    fun runNow(config: BackupConfig)
}
