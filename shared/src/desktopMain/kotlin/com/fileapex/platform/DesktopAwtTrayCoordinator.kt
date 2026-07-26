package com.fileapex.platform

import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/**
 * Windows (and generic JVM) system-tray fallback using AWT [SystemTray].
 * Provides hide-to-tray on close when supported; otherwise close exits normally.
 */
object DesktopAwtTrayCoordinator {
    private var mainWindow: Window? = null
    private var trayIcon: TrayIcon? = null
    private var installed = false
    private var minimizeToTrayOnClose = false

    fun isInstalled(): Boolean = installed

    fun attachMainWindow(window: Window, onQuit: () -> Unit) {
        if (!DesktopPlatformPaths.isWindows() || installed) return
        mainWindow = window

        if (!SystemTray.isSupported()) {
            println("DesktopAwtTrayCoordinator: SystemTray unavailable — close will exit app")
            return
        }

        runCatching {
            installTrayIcon(onQuit)
            installed = true
            minimizeToTrayOnClose = true
            println("DesktopAwtTrayCoordinator: AWT tray installed")
        }.onFailure { error ->
            println("DesktopAwtTrayCoordinator: tray setup failed — ${error.message}")
        }
    }

    /** Returns true when the close request was consumed (hide-to-tray). */
    fun handleCloseRequest(): Boolean {
        if (!DesktopPlatformPaths.isWindows() || !installed || !minimizeToTrayOnClose) {
            return false
        }
        hideMainWindow()
        showBalloon("FileApex is still running in the system tray.")
        return true
    }

    fun hideMainWindow() {
        SwingUtilities.invokeLater {
            mainWindow?.isVisible = false
        }
    }

    fun showMainWindow() {
        SwingUtilities.invokeLater {
            mainWindow?.isVisible = true
            mainWindow?.toFront()
            mainWindow?.requestFocus()
        }
    }

    fun showBalloon(message: String) {
        if (!DesktopPlatformPaths.isWindows()) return
        val icon = trayIcon
        if (icon != null) {
            icon.displayMessage("FileApex", message, TrayIcon.MessageType.INFO)
        } else {
            println("DesktopAwtTrayCoordinator: $message")
        }
    }

    fun dispose() {
        if (!installed) return
        runCatching {
            val tray = SystemTray.getSystemTray()
            trayIcon?.let { tray.remove(it) }
        }.onFailure { error ->
            println("DesktopAwtTrayCoordinator: tray remove failed — ${error.message}")
        }
        trayIcon = null
        installed = false
        minimizeToTrayOnClose = false
    }

    private fun installTrayIcon(onQuit: () -> Unit) {
        val popup = PopupMenu()
        popup.add(
            MenuItem("Show FileApex").apply {
                addActionListener { showMainWindow() }
            }
        )
        popup.add(
            MenuItem("Exit").apply {
                addActionListener {
                    dispose()
                    onQuit()
                }
            }
        )

        val icon = TrayIcon(createTrayImage(), "FileApex", popup).apply {
            isImageAutoSize = true
            addActionListener { showMainWindow() }
        }
        SystemTray.getSystemTray().add(icon)
        trayIcon = icon
    }

    private fun createTrayImage(): java.awt.Image {
        return DesktopAppIcon.loadTrayImage() ?: run {
            val size = 16
            val buffered = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val graphics = buffered.createGraphics()
            try {
                graphics.color = java.awt.Color(0x1B, 0x5E, 0x4B)
                graphics.fillRect(0, 0, size, size)
            } finally {
                graphics.dispose()
            }
            buffered
        }
    }
}
