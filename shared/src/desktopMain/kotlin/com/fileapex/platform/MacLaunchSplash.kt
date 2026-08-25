package com.fileapex.platform

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.Image
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Cheap AWT window so macOS cold start is not 10s of Dock bounce with no UI.
 * Compose/Skiko still takes several seconds; this only covers that gap.
 */
object MacLaunchSplash {
    private var window: JWindow? = null

    fun show() {
        if (!DesktopPlatformPaths.isMacOs()) return
        runCatching {
            SwingUtilities.invokeAndWait {
                if (window != null) return@invokeAndWait
                val icon = DesktopAppIcon.loadTrayImage()?.getScaledInstance(96, 96, Image.SCALE_SMOOTH)
                val label = JLabel("FileApex").apply {
                    foreground = Color.WHITE
                    font = Font("SansSerif", Font.BOLD, 22)
                    horizontalAlignment = SwingConstants.CENTER
                    verticalAlignment = SwingConstants.CENTER
                    horizontalTextPosition = SwingConstants.CENTER
                    verticalTextPosition = SwingConstants.BOTTOM
                    if (icon != null) this.icon = ImageIcon(icon)
                    iconTextGap = 16
                }
                val panel = JPanel(BorderLayout()).apply {
                    background = Color(0x12, 0x2A, 0x2A)
                    add(label, BorderLayout.CENTER)
                }
                window = JWindow().apply {
                    contentPane = panel
                    setSize(440, 560)
                    setLocationRelativeTo(null)
                    isVisible = true
                }
            }
        }
    }

    fun hide() {
        if (!DesktopPlatformPaths.isMacOs()) return
        val toClose = window ?: return
        window = null
        runCatching {
            SwingUtilities.invokeLater {
                toClose.isVisible = false
                toClose.dispose()
            }
        }
    }
}
