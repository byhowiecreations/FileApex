package com.fileapex.platform

import com.fileapex.di.FileApexServices
import com.fileapex.domain.presence.PresenceForegroundRefresh
import java.awt.MenuItem
import java.awt.Point
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/**
 * Windows system-tray using AWT [SystemTray].
 *
 * Left-click: Mac-parity device popover (Ctrl multi-select → Drop Files panel).
 * Right-click: Show FileApex + Exit.
 */
object DesktopAwtTrayCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var mainWindow: Window? = null
    private var trayIcon: TrayIcon? = null
    private var observeJob: Job? = null
    private var installed = false
    private var minimizeToTrayOnClose = false
    private var onShowWindow: (() -> Unit)? = null
    private var onHideWindow: (() -> Unit)? = null
    private var onQuitApp: (() -> Unit)? = null
    private var devices: List<DesktopTrayDeviceSnapshot> = emptyList()
    private var trayPopover: DesktopWindowsTrayPopover? = null

    fun isInstalled(): Boolean = installed

    fun attachMainWindow(
        window: Window,
        onShowWindow: () -> Unit,
        onHideWindow: () -> Unit,
        onQuit: () -> Unit,
    ) {
        if (!DesktopPlatformPaths.isWindows() || installed) return
        mainWindow = window
        this.onShowWindow = onShowWindow
        this.onHideWindow = onHideWindow
        this.onQuitApp = onQuit

        if (!SystemTray.isSupported()) {
            println("DesktopAwtTrayCoordinator: SystemTray unavailable — close will exit app")
            return
        }

        runCatching {
            installTrayIcon()
            startDeviceSync()
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
        // Compose Window must use the `visible` parameter — raw isVisible breaks show (CMP #2928).
        onHideWindow?.invoke()
    }

    fun showMainWindow() {
        trayPopover?.hide()
        onShowWindow?.invoke()
        SwingUtilities.invokeLater {
            requestForeground()
            mainWindow?.toFront()
            mainWindow?.requestFocus()
        }
        PresenceForegroundRefresh.onAppForegrounded()
    }

    private fun requestForeground() {
        runCatching {
            val desktop = java.awt.Desktop.getDesktop()
            if (desktop.isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND)) {
                desktop.requestForeground(true)
            }
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
        observeJob?.cancel()
        observeJob = null
        trayPopover?.dispose()
        trayPopover = null
        DesktopWindowsDropBox.dispose()
        runCatching {
            val tray = SystemTray.getSystemTray()
            trayIcon?.let { tray.remove(it) }
        }.onFailure { error ->
            println("DesktopAwtTrayCoordinator: tray remove failed — ${error.message}")
        }
        trayIcon = null
        installed = false
        minimizeToTrayOnClose = false
        onShowWindow = null
        onHideWindow = null
        onQuitApp = null
        devices = emptyList()
    }

    private fun installTrayIcon() {
        runCatching {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        }

        trayPopover = DesktopWindowsTrayPopover(
            onLaunchApp = { showMainWindow() },
            onQuitApp = { quitFromTray() },
            onOpenDropBox = { deviceIds -> openDropBox(deviceIds) },
            onRefreshDevices = { refreshDevicesFromTray() }
        )

        val popup = PopupMenu()
        popup.add(
            MenuItem("Show FileApex").apply {
                addActionListener { showMainWindow() }
            }
        )
        popup.add(
            MenuItem("Exit").apply {
                addActionListener { quitFromTray() }
            }
        )

        val icon = TrayIcon(createTrayImage(), "FileApex", popup).apply {
            isImageAutoSize = true
            // Windows: ActionEvent is typically double-click; single left-click is MouseListener.
            addActionListener { showMainWindow() }
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseReleased(event: MouseEvent) {
                        if (!SwingUtilities.isLeftMouseButton(event) || event.isPopupTrigger) {
                            return
                        }
                        showLeftClickPopover(event.xOnScreen, event.yOnScreen)
                    }
                }
            )
        }
        SystemTray.getSystemTray().add(icon)
        trayIcon = icon
    }

    private fun startDeviceSync() {
        observeJob?.cancel()
        observeJob = scope.launch {
            if (!FileApexServices.isDatabaseReady()) return@launch
            combine(
                FileApexServices.deviceRepository.observeDevices(),
                FileApexServices.presenceMonitor.reachabilityEpochMs,
                FileApexServices.presenceMonitor.onlineDeviceIds,
                FileApexServices.presenceMonitor.onlineSnapshotEpochMs
            ) { roster, _, _, _ ->
                roster.map { device ->
                    DesktopTrayDeviceSnapshot(
                        id = device.deviceId,
                        name = device.deviceName,
                        isOnline = FileApexServices.presenceMonitor.isDeviceOnline(device)
                    )
                }
            }.collect { snapshots ->
                devices = snapshots
                withContext(Dispatchers.Swing) {
                    trayPopover?.updateDevices(snapshots)
                }
            }
        }
    }

    private fun refreshDevicesFromTray() {
        scope.launch {
            if (!FileApexServices.isDatabaseReady()) return@launch
            FileApexServices.presenceMonitor.refreshOnlineSnapshot()
            devices = FileApexServices.deviceRepository.listDevices().map { device ->
                DesktopTrayDeviceSnapshot(
                    id = device.deviceId,
                    name = device.deviceName,
                    isOnline = FileApexServices.presenceMonitor.isDeviceOnline(device)
                )
            }
            withContext(Dispatchers.Swing) {
                trayPopover?.updateDevices(devices)
            }
        }
    }

    private fun showLeftClickPopover(screenX: Int, screenY: Int) {
        scope.launch {
            if (FileApexServices.isDatabaseReady()) {
                FileApexServices.presenceMonitor.refreshOnlineSnapshot()
                devices = FileApexServices.deviceRepository.listDevices().map { device ->
                    DesktopTrayDeviceSnapshot(
                        id = device.deviceId,
                        name = device.deviceName,
                        isOnline = FileApexServices.presenceMonitor.isDeviceOnline(device)
                    )
                }
            }
            withContext(Dispatchers.Swing) {
                trayPopover?.toggleOrShow(Point(screenX, screenY), devices)
            }
        }
    }

    private fun openDropBox(deviceIds: List<String>) {
        DesktopWindowsDropBox.show(deviceIds) { ids, paths ->
            scope.launch(Dispatchers.Default) {
                try {
                    val batch = FileApexServices.transferManager.sendLocalPathsToDeviceIds(
                        absolutePaths = paths,
                        deviceIds = ids
                    )
                    val message = if (batch.allFailed) {
                        batch.summaryMessage
                    } else if (paths.size > 1) {
                        "${paths.size} Files Sent"
                    } else {
                        "File Sent"
                    }
                    showBalloon(message)
                } catch (error: Exception) {
                    showBalloon(error.message ?: "Send failed")
                } finally {
                    DesktopWindowsDropBox.onSendFinished()
                }
            }
        }
    }

    private fun quitFromTray() {
        val quit = onQuitApp
        dispose()
        quit?.invoke()
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
