package com.fileapex.platform

/**
 * Cross-platform desktop JVM entry hooks. Mac-only registrars and native bridges
 * are invoked only when [DesktopPlatformPaths.isMacOs].
 */
object DesktopJvmStartup {
    fun onMainEntry() {
        DesktopCrashHandler.install()
        configureWindowsSkikoRendering()
        if (DesktopPlatformPaths.isMacOs()) {
            MacOsExtensionRegistrar.registerOnLaunchDeferred()
        }
        if (DesktopPlatformPaths.isWindows()) {
            DesktopWindowsRegistration.registerWindowsContextMenuAndSendTo()
        }
        DesktopSendHandoff.installOpenUriHandler()
    }

    /**
     * Skiko defaults to Direct3D on Windows, which stretches the framebuffer during live
     * resize until Compose redraws — visibly jerky compared to macOS Metal. OpenGL
     * repaints on each size change (Skiko #923, Compose Multiplatform #2925).
     *
     * Must run before any Compose/Skiko window is created. Honors an explicit
     * [skiko.renderApi] override from the environment or launcher.
     */
    private fun configureWindowsSkikoRendering() {
        if (!DesktopPlatformPaths.isWindows()) return
        if (!System.getProperty("skiko.renderApi").isNullOrBlank()) return
        System.setProperty("skiko.renderApi", "OPENGL")
    }
}
