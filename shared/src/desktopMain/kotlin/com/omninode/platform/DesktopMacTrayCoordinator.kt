package com.omninode.platform

import com.omninode.di.OmniNodeServices
import java.awt.Window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * SSOT for macOS menu-bar tray: device roster sync, send handoff, passive presence badges, battery hints.
 */
object DesktopMacTrayCoordinator {
    private val json = Json { encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var mainWindow: Window? = null
    private var observeJob: Job? = null
    private var bindJob: Job? = null
    private var installed = false
    private var nativeMainWindowBound = false

    fun isMacOs(): Boolean =
        System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

    fun isInstalled(): Boolean = installed

    fun attachMainWindow(window: Window, onQuit: () -> Unit) {
        if (!isMacOs() || installed) return
        if (!DesktopMacTrayBridge.load()) return

        mainWindow = window
        DesktopMacTrayBridge.registerCallbacks(
            onSend = { deviceIdsJson, filePathsJson -> handleSend(deviceIdsJson, filePathsJson) },
            onPopoverVisible = { visible ->
                if (visible) {
                    refreshDeviceSnapshotFromTray()
                }
            },
            onDropBoxVisible = { visible ->
                if (visible) {
                    refreshDeviceSnapshotFromTray()
                }
            },
            onRefreshDevices = { refreshDeviceSnapshotFromTray() },
            onPrepareDropBox = { DesktopMacTrayBridge.resyncDropBoxFrame() },
            onQuit = { dispatchToSwing(onQuit) },
            onShowMainWindow = { syncMainWindowOnSwing() }
        )
        DesktopMacTrayBridge.setup()
        scheduleMainWindowBinding(window)
        startDeviceSync()
        installed = true
        refreshDeviceSnapshotFromTray()
        println("DesktopMacTrayCoordinator: native tray installed")
    }

    /** Returns true when the close request was consumed (hide-to-tray). */
    fun handleCloseRequest(): Boolean {
        if (!installed) return false
        hideMainWindow()
        return true
    }

    fun hideMainWindow() {
        scope.launch(Dispatchers.Swing) {
            mainWindow?.isVisible = false
        }
        if (nativeMainWindowBound) {
            DesktopMacTrayBridge.hideMainWindow()
        }
    }

    fun showMainWindow() {
        showMainWindowFromTray()
    }

    private fun dispatchToSwing(block: () -> Unit) {
        scope.launch(Dispatchers.Swing) {
            block()
        }
    }

    private fun showMainWindowFromTray() {
        DesktopMacTrayBridge.showMainWindow()
        syncMainWindowOnSwing()
    }

    private fun syncMainWindowOnSwing() {
        scope.launch(Dispatchers.Swing) {
            mainWindow?.isVisible = true
            mainWindow?.toFront()
            mainWindow?.requestFocus()
        }
        refreshDeviceSnapshotFromTray()
    }

    private fun refreshDeviceSnapshotFromTray() {
        scope.launch {
            OmniNodeServices.presenceMonitor.refreshOnlineSnapshot()
            pushDeviceSnapshot()
        }
    }

    private fun scheduleMainWindowBinding(window: Window) {
        bindJob?.cancel()
        bindJob = scope.launch(Dispatchers.Swing) {
            repeat(30) {
                val ptr = DesktopMacNativeWindow.nsWindowPointer(window)
                if (ptr != null) {
                    DesktopMacTrayBridge.bindMainWindow(ptr)
                    nativeMainWindowBound = true
                    println("DesktopMacTrayCoordinator: bound NSWindow delegate")
                    return@launch
                }
                delay(100)
            }
            println("DesktopMacTrayCoordinator: NSWindow bind skipped — using Compose hide-on-close")
        }
    }

    private fun startDeviceSync() {
        observeJob?.cancel()
        observeJob = scope.launch {
            combine(
                OmniNodeServices.deviceRepository.observeDevices(),
                OmniNodeServices.presenceMonitor.reachabilityEpochMs,
                OmniNodeServices.presenceMonitor.onlineDeviceIds,
                OmniNodeServices.presenceMonitor.onlineSnapshotEpochMs
            ) { devices, _, _, _ ->
                devices.map { device ->
                    TrayDeviceSnapshot(
                        id = device.deviceId,
                        name = device.deviceName,
                        isOnline = OmniNodeServices.presenceMonitor.isDeviceOnline(device)
                    )
                }
            }.collect { snapshots ->
                DesktopMacTrayBridge.updateDevices(json.encodeToString(snapshots))
            }
        }
    }

    private suspend fun pushDeviceSnapshot() {
        val devices = OmniNodeServices.deviceRepository.listDevices()
        val snapshots = devices.map { device ->
            TrayDeviceSnapshot(
                id = device.deviceId,
                name = device.deviceName,
                isOnline = OmniNodeServices.presenceMonitor.isDeviceOnline(device)
            )
        }
        DesktopMacTrayBridge.updateDevices(json.encodeToString(snapshots))
    }

    private fun handleSend(deviceIdsJson: String, filePathsJson: String) {
        scope.launch {
            val deviceIds = runCatching {
                json.decodeFromString<List<String>>(deviceIdsJson)
            }.getOrDefault(emptyList())
            val filePaths = runCatching {
                json.decodeFromString<List<String>>(filePathsJson)
            }.getOrDefault(emptyList())
            if (deviceIds.isEmpty() || filePaths.isEmpty()) return@launch

            DesktopMacTrayBridge.beginBackgroundActivity()
            try {
                val batch = OmniNodeServices.transferManager.sendLocalPathsToDeviceIds(
                    absolutePaths = filePaths,
                    deviceIds = deviceIds
                )
                val toastMessage = if (batch.allFailed) {
                    batch.summaryMessage
                } else if (filePaths.size > 1) {
                    "${filePaths.size} Files Sent"
                } else {
                    "File Sent"
                }
                DesktopMacTrayBridge.showToast(toastMessage)
            } catch (error: Exception) {
                DesktopMacTrayBridge.showToast(error.message ?: "Send failed")
            } finally {
                DesktopMacTrayBridge.endBackgroundActivity()
                DesktopMacTrayBridge.closeDropBox()
            }
        }
    }
}

@Serializable
private data class TrayDeviceSnapshot(
    val id: String,
    val name: String,
    val isOnline: Boolean
)
