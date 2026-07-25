package com.fileapex.platform

import java.awt.Window

/**
 * Cross-platform desktop tray facade.
 * - macOS: native menu-bar tray ([DesktopMacTrayCoordinator])
 * - Windows: AWT system-tray fallback ([DesktopAwtTrayCoordinator])
 * - Other: no tray; window close exits the app
 */
object DesktopTraySupport {
    val isAvailable: Boolean
        get() = when {
            DesktopPlatformPaths.isMacOs() -> DesktopMacTrayCoordinator.isInstalled()
            DesktopPlatformPaths.isWindows() -> DesktopAwtTrayCoordinator.isInstalled()
            else -> false
        }

    fun attachMainWindow(window: Window, onQuit: () -> Unit) {
        when {
            DesktopPlatformPaths.isMacOs() ->
                DesktopMacTrayCoordinator.attachMainWindow(window, onQuit)
            DesktopPlatformPaths.isWindows() ->
                DesktopAwtTrayCoordinator.attachMainWindow(window, onQuit)
        }
    }

    /** Returns true when the close request was consumed (hide-to-tray). */
    fun handleCloseRequest(): Boolean = when {
        DesktopPlatformPaths.isMacOs() -> DesktopMacTrayCoordinator.handleCloseRequest()
        DesktopPlatformPaths.isWindows() -> DesktopAwtTrayCoordinator.handleCloseRequest()
        else -> false
    }

    /** Removes AWT tray icons before JVM exit (Windows). macOS native tray needs no teardown. */
    fun dispose() {
        if (DesktopPlatformPaths.isWindows()) {
            DesktopAwtTrayCoordinator.dispose()
        }
    }
}
