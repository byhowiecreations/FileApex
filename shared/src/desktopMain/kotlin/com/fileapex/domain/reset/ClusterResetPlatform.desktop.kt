package com.fileapex.domain.reset

import com.fileapex.platform.DesktopPlatformPaths
import java.util.prefs.Preferences
import kotlin.system.exitProcess

actual object ClusterResetPlatform {
    actual fun eraseKeystoreAndSecureStorage() {
        runCatching {
            DesktopPlatformPaths.identityPropertiesFile().delete()
            DesktopPlatformPaths.legacyIdentityPropertiesCandidates().forEach { it.delete() }
        }
    }

    actual fun stateResetAndRestart() {
        runCatching {
            Preferences.userRoot().node("com.fileapex.settings").clear()
        }
        runCatching {
            DesktopPlatformPaths.rosterResolvedMarkerFile().delete()
            DesktopPlatformPaths.sendJobsDirectory().deleteRecursively()
        }
        com.fileapex.network.ServerLifecycleManager.stop(fast = true)
        exitProcess(0)
    }
}
