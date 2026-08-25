package com.fileapex.platform

/**
 * Cross-platform desktop JVM entry hooks. Mac-only registrars and native bridges
 * are invoked only when [DesktopPlatformPaths.isMacOs].
 */
object DesktopJvmStartup {
    fun onMainEntry() {
        DesktopCrashHandler.install()
        sanitizeTempDirectories()
        configureWindowsSkikoRendering()
        if (DesktopPlatformPaths.isMacOs()) {
            DesktopMacTrayBridge.preload()
        }
        if (DesktopPlatformPaths.isWindows()) {
            DesktopWindowsRegistration.registerWindowsContextMenuAndSendTo()
        }
        DesktopSendHandoff.installOpenUriHandler()
    }

    private fun sanitizeTempDirectories() {
        if (!DesktopPlatformPaths.isWindows()) return
        val tmpDir = System.getProperty("java.io.tmpdir").orEmpty()
        val userHome = System.getProperty("user.home").orEmpty()
        if (tmpDir.contains("!") || tmpDir.contains("#") || userHome.contains("!") || userHome.contains("#")) {
            val programData = System.getenv("ProgramData")?.trim().takeIf { !it.isNullOrBlank() && !it.contains("!") && !it.contains("#") }
                ?: "C:\\ProgramData"
            val safeTemp = java.io.File(programData, "FileApex\\temp")
            runCatching {
                safeTemp.mkdirs()
                System.setProperty("java.io.tmpdir", safeTemp.absolutePath)
                System.setProperty("jna.tmpdir", safeTemp.absolutePath)
            }
        }
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
