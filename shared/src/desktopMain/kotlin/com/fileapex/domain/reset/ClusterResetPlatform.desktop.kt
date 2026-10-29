package com.fileapex.domain.reset

import com.fileapex.platform.DesktopPlatformPaths
import com.fileapex.platform.LegacyHandoffCleanup
import com.fileapex.util.cancellableCatching
import java.util.prefs.Preferences

actual object ClusterResetPlatform {
    actual val keepsAppRunning: Boolean = true

    actual fun eraseKeystoreAndSecureStorage() {
        runCatching {
            DesktopPlatformPaths.identityPropertiesFile().delete()
            DesktopPlatformPaths.legacyIdentityPropertiesCandidates().forEach { it.delete() }
        }
    }

    actual fun stateResetAndRestart() {
        cancellableCatching {
            val node = Preferences.userRoot().node("com.fileapex.settings")
            node.clear()
            // The database file survives a wipe, so without this the next launch counts as an old install and gets Clean.
            node.putBoolean(com.fileapex.data.settings.BaseAppSettings.KEY_THEME_DEFAULT_MIGRATED, true)
            node.flush()
        }
        // Sign-in tokens are not settings, so clearing the settings node alone would leave them behind.
        listOf("com.fileapex.firebase", "com.fileapex.drive").forEach { name ->
            cancellableCatching { Preferences.userRoot().node(name).also { it.clear(); it.flush() } }
        }
        runCatching {
            LegacyHandoffCleanup.run()
            // The roster backup would be read back into an empty roster at the next start.
            val backup = DesktopPlatformPaths.databasePreV3BackupFile()
            listOf("", "-wal", "-shm").forEach { suffix ->
                java.io.File(backup.parentFile, backup.name + suffix).delete()
            }
            // Present marker: legacy database locations are not imported into the emptied roster.
            DesktopPlatformPaths.rosterResolvedMarkerFile().writeText("ok", Charsets.UTF_8)
        }
        com.fileapex.network.ServerLifecycleManager.stop(fast = true)
    }
}
