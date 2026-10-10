package com.fileapex.platform

import com.fileapex.domain.backup.BackupConfig

actual fun backupSupported(): Boolean = false

actual fun backupStateDirectory(): String =
    java.io.File(DesktopPlatformPaths.applicationSupportDirectory(), "backup").also { it.mkdirs() }.absolutePath

actual object BackupScheduler {
    actual fun apply(config: BackupConfig) = Unit

    actual fun runNow(config: BackupConfig) = Unit
}
