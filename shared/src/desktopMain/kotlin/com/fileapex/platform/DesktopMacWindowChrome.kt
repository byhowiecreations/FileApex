package com.fileapex.platform

import java.awt.Window
import javax.swing.JFrame
import javax.swing.JRootPane

/**
 * Configures a seamless macOS titlebar where the Compose window background extends behind the
 * system traffic-light controls instead of rendering a bright opaque titlebar strip.
 */
object DesktopMacWindowChrome {
    const val MAC_TOP_INSET_DP = 28

    fun topContentInsetDp(isMacOs: Boolean = DesktopPlatformPaths.isMacOs()): Int =
        if (isMacOs) MAC_TOP_INSET_DP else 0

    fun applySystemAppearanceEarly(isMacOs: Boolean = DesktopPlatformPaths.isMacOs()) {
        if (!isMacOs) return
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
    }

    fun applyToRootPane(rootPane: JRootPane) {
        rootPane.putClientProperty("apple.awt.fullWindowContent", true)
        rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
        rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
    }

    fun apply(window: Window) {
        if (!DesktopPlatformPaths.isMacOs()) return
        val frame = window as? JFrame ?: return
        applyToRootPane(frame.rootPane)
    }
}
