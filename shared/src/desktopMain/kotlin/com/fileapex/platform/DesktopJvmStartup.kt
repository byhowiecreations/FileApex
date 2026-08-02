package com.fileapex.platform

/**
 * Cross-platform desktop JVM entry hooks. Mac-only registrars and native bridges
 * are invoked only when [DesktopPlatformPaths.isMacOs].
 */
object DesktopJvmStartup {
    fun onMainEntry() {
        if (DesktopPlatformPaths.isMacOs()) {
            MacOsExtensionRegistrar.registerOnLaunchDeferred()
        }
        DesktopSendHandoff.installOpenUriHandler()
    }
}
