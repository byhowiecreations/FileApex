package com.fileapex.platform

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.presence.PresenceForegroundRefresh
import com.fileapex.i18n.AppI18n
import com.fileapex.util.cancellableCatching
import java.awt.MouseInfo
import java.awt.Point
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/**
 * Windows system-tray coordinator using AWT [SystemTray] and Compose tray flyout.
 *
 * Click (left or right): Mac-parity dark Compose device popover (Ctrl multi-select -> Drop Files panel).
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
    private var trayFlyout: DesktopWindowsTrayFlyout? = null

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
            println("DesktopAwtTrayCoordinator: SystemTray unavailable - close will exit app")
            return
        }

        cancellableCatching {
            installTrayIcon()
            startDeviceSync()
            installed = true
            minimizeToTrayOnClose = true
            println("DesktopAwtTrayCoordinator: AWT tray installed")
            com.fileapex.i18n.DesktopI18nRuntime.sync()
        }.onFailure { error ->
            println("DesktopAwtTrayCoordinator: tray setup failed - ${error.message}")
        }
    }

    /** Returns true when the close request was consumed (hide-to-tray). */
    fun handleCloseRequest(): Boolean {
        if (!DesktopPlatformPaths.isWindows() || !installed || !minimizeToTrayOnClose) {
            return false
        }
        hideMainWindow()
        showBalloon(AppI18n.t("still_running_in_tray"))
        return true
    }

    fun hideMainWindow() {
        // Compose Window must use the visible parameter, raw isVisible breaks show (CMP #2928).
        onHideWindow?.invoke()
    }

    fun showMainWindow() {
        val startedAt = System.nanoTime()
        trayFlyout?.dismissFlyout()
        onShowWindow?.invoke()
        SwingUtilities.invokeLater {
            requestForeground()
            mainWindow?.toFront()
            mainWindow?.requestFocus()
        }
        // The refresh can wait on the network; on the UI thread it froze the window for seconds.
        scope.launch { PresenceForegroundRefresh.onAppForegrounded() }
        DesktopLifecycleLog.log("DesktopAwtTrayCoordinator: showMainWindow returned after ${(System.nanoTime() - startedAt) / 1_000_000L}ms")
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
        trayFlyout?.dispose()
        trayFlyout = null
        DesktopWindowsDropBox.dispose()
        cancellableCatching {
            val tray = SystemTray.getSystemTray()
            trayIcon?.let { tray.remove(it) }
        }.onFailure { error ->
            println("DesktopAwtTrayCoordinator: tray remove failed - ${error.message}")
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
        trayFlyout = DesktopWindowsTrayFlyout(
            onLaunchApp = { showMainWindow() },
            onQuitApp = { quitFromTray() },
            onOpenDropBox = { deviceIds -> openDropBox(deviceIds) },
            onRefreshDevices = { refreshDevicesFromTray() },
            onSendClipboard = { deviceId -> sendClipboardFromTray(deviceId) },
            onBrowse = { deviceId -> browseFromTray(deviceId) },
            onPostNote = { text -> postNoteFromTray(text) },
            onCheckBatteries = { checkBatteriesFromTray() }
        )

        val icon = TrayIcon(createTrayImage(), "FileApex").apply {
            isImageAutoSize = true
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseReleased(event: MouseEvent) {
                        val anchor = MouseInfo.getPointerInfo()?.location
                            ?: Point(event.xOnScreen, event.yOnScreen)
                        toggleTrayFlyout(anchor)
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
            awaitDatabase()
            combine(
                combine(
                    FileApexServices.deviceRepository.observeDevices(),
                    FileApexServices.presenceMonitor.reachabilityEpochMs,
                    FileApexServices.presenceMonitor.onlineDeviceIds,
                    FileApexServices.presenceMonitor.onlineSnapshotEpochMs,
                    com.fileapex.friends.FriendsHub.friends
                ) { roster, _, _, _, _ -> roster },
                DesktopTrayBatteries.readings
            ) { roster, _ -> snapshotsOf(roster) }.collect { snapshots ->
                devices = snapshots
                withContext(Dispatchers.Swing) {
                    trayFlyout?.updateDevices(snapshots)
                }
            }
        }
    }

    private fun refreshDevicesFromTray() {
        scope.launch {
            devices = loadDeviceSnapshots()
            withContext(Dispatchers.Swing) {
                trayFlyout?.updateDevices(devices)
            }
        }
    }

    private fun toggleTrayFlyout(anchor: Point) {
        scope.launch {
            devices = loadDeviceSnapshots()
            withContext(Dispatchers.Swing) {
                trayFlyout?.toggleOrShow(anchor, devices)
            }
        }
    }

    private suspend fun awaitDatabase() {
        if (!FileApexServices.isDatabaseReady()) {
            FileApexServices.awaitBootstrap()
        }
    }

    private suspend fun loadDeviceSnapshots(): List<DesktopTrayDeviceSnapshot> {
        awaitDatabase()
        FileApexServices.presenceMonitor.refreshOnlineSnapshot()
        return snapshotsOf(FileApexServices.deviceRepository.listDevices())
    }

    private fun snapshotsOf(roster: List<PairedDeviceEntity>): List<DesktopTrayDeviceSnapshot> =
        withTrayBatteries(
            orderTrayDevices(
                roster.map { device -> trayDeviceSnapshot(device, FileApexServices.presenceMonitor.isDeviceOnline(device)) } +
                    friendTraySnapshots()
            ),
            DesktopTrayBatteries.readings.value
        )

    private fun sendClipboardFromTray(deviceId: String) {
        scope.launch {
            showBalloon(sendTrayClipboard(listOf(deviceId)).text)
        }
    }

    private fun browseFromTray(deviceId: String) {
        DesktopOpenDeviceRequest.request(deviceId)
        showMainWindow()
    }

    private fun postNoteFromTray(text: String) {
        scope.launch {
            showBalloon(postTrayNote(text).text)
        }
    }

    private fun checkBatteriesFromTray() {
        scope.launch {
            DesktopTrayBatteries.check(devices)
            withContext(Dispatchers.Swing) { trayFlyout?.batteryCheckFinished() }
        }
    }

    private fun openDropBox(deviceIds: List<String>) {
        val singleName = if (deviceIds.size == 1) {
            devices.firstOrNull { it.id == deviceIds.first() }?.name
        } else null
        DesktopWindowsDropBox.show(deviceIds, singleName) { ids, paths ->
            scope.launch(Dispatchers.Default) {
                try {
                    val realIds = sendTrayFriends(ids, paths)
                    if (realIds.isEmpty()) {
                        showBalloon(AppI18n.plural("n_files_sent_short", paths.size, paths.size.toString()))
                        return@launch
                    }
                    val outcome = FileApexServices.transferQueue.sendLocalPathsOrQueue(
                        absolutePaths = paths,
                        deviceIds = realIds
                    )
                    val batch = outcome.batch
                    val message = when {
                        outcome.hadQueue && (batch == null || batch.allFailed) -> outcome.message
                        batch?.allFailed == true -> outcome.message
                        outcome.hadQueue -> outcome.message
                        else -> AppI18n.plural("n_files_sent_short", paths.size, paths.size.toString())
                    }
                    showBalloon(message)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    println("DesktopAwtTrayCoordinator: send failed - ${error::class.simpleName}")
                    showBalloon(error.message ?: AppI18n.t("send_failed"))
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
