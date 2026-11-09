package com.fileapex.platform

/**
 * Cross-platform desktop JVM entry hooks. Mac-only registrars and native bridges
 * are invoked only when [DesktopPlatformPaths.isMacOs].
 */
object DesktopJvmStartup {
    /** The window class on Linux; every package launcher names it in StartupWMClass (`composeApp/linux/FileApex.desktop`). */
    const val LINUX_WINDOW_CLASS = "FileApex"

    fun onMainEntry() {
        DesktopLifecycleLog.markProcessStart()
        DesktopCrashHandler.install()
        sanitizeTempDirectories()
        configureWindowsSkikoRendering()
        configureLinuxWindowClass()
        if (DesktopPlatformPaths.isMacOs()) {
            DesktopMacTrayBridge.preload()
        }
        DesktopUriHandler.installOpenUriHandler()
    }

    /** X11 names the window after the main class, so the launcher's StartupWMClass never matches. Needs --add-opens for sun.awt.X11. */
    private fun configureLinuxWindowClass() {
        if (!DesktopPlatformPaths.isLinux()) return
        runCatching {
            val toolkit = java.awt.Toolkit.getDefaultToolkit()
            val field = toolkit.javaClass.getDeclaredField("awtAppClassName")
            field.isAccessible = true
            field.set(null, LINUX_WINDOW_CLASS)
        }.onFailure { println("DesktopJvmStartup: window class not set - ${it.message}") }
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
     * Skiko's own default on Windows is Direct3D, which stretched the framebuffer during live resize with the Skiko
     * bundled until now (Skiko #923, Compose Multiplatform #2925), so the default stays OpenGL. The user can pick
     * Direct3D in Settings; the choice is read here, before any Compose or Skiko window exists.
     */
    private fun configureWindowsSkikoRendering() {
        if (!DesktopPlatformPaths.isWindows()) return
        val mode = WindowsGraphicsMode.load(DesktopPlatformPaths.applicationSupportDirectory())
        System.setProperty("skiko.renderApi", WindowsGraphicsMode.effectiveRenderApi(System.getProperty("skiko.renderApi"), mode))
    }
}
