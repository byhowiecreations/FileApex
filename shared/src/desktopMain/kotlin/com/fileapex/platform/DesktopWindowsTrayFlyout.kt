package com.fileapex.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposePanel
import java.awt.Color
import java.awt.Point
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JDialog
import javax.swing.Timer

private const val SCREEN_MARGIN_DP = 16

/** Undecorated floating JDialog hosting [DesktopWindowsTrayMenu] on Windows. */
internal class DesktopWindowsTrayFlyout(
    private val onLaunchApp: () -> Unit,
    private val onQuitApp: () -> Unit,
    private val onOpenDropBox: (deviceIds: List<String>) -> Unit,
    private val onRefreshDevices: () -> Unit,
    private val onSendClipboard: (deviceId: String) -> Unit,
    private val onBrowse: (deviceId: String) -> Unit,
    private val onPostNote: (text: String) -> Unit,
    private val onCheckBatteries: () -> Unit
) {
    private var devicesState by mutableStateOf<List<DesktopTrayDeviceSnapshot>>(emptyList())
    private var selectedIdsState by mutableStateOf<Set<String>>(emptySet())
    private var columnsState by mutableStateOf(1)
    private var checkingBatteriesState by mutableStateOf(false)
    private var availableHeightDp = 0

    private var hasGainedFocus = false
    private var isSettling = false
    private var lastDismissEpochMs = 0L

    private val composePanel = ComposePanel().apply {
        background = Color(DesktopTrayStyle.BACKGROUND_RGB)
        setContent {
            DesktopWindowsTrayMenu(
                devices = devicesState,
                selectedDeviceIds = selectedIdsState,
                columns = columnsState,
                checkingBatteries = checkingBatteriesState,
                onDeviceClick = { device ->
                    dismissFlyout()
                    onOpenDropBox(listOf(device.id))
                },
                onDeviceCtrlClick = { device ->
                    val current = selectedIdsState.toMutableSet()
                    if (device.id in current) {
                        current.remove(device.id)
                    } else {
                        current.add(device.id)
                    }
                    selectedIdsState = current
                },
                onSendClipboard = { device ->
                    dismissFlyout()
                    onSendClipboard(device.id)
                },
                onBrowse = { device ->
                    dismissFlyout()
                    onBrowse(device.id)
                },
                onPostNote = { text ->
                    dismissFlyout()
                    onPostNote(text)
                },
                onCheckBatteries = {
                    checkingBatteriesState = true
                    onCheckBatteries()
                },
                onSendMulti = {
                    val ids = selectedIdsState.toList()
                    if (ids.isNotEmpty()) {
                        dismissFlyout()
                        onOpenDropBox(ids)
                    }
                },
                onLaunchApp = {
                    dismissFlyout()
                    onLaunchApp()
                },
                onQuitApp = {
                    dismissFlyout()
                    onQuitApp()
                }
            )
        }
    }

    private val dialog = JDialog(null as java.awt.Frame?).apply {
        isUndecorated = true
        isAlwaysOnTop = true
        type = java.awt.Window.Type.POPUP
        focusableWindowState = true
        background = Color(DesktopTrayStyle.BACKGROUND_RGB)
        contentPane = composePanel

        addWindowFocusListener(object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent?) {
                hasGainedFocus = true
            }

            override fun windowLostFocus(e: WindowEvent?) {
                if (DesktopTrayFocusGuard.shouldDismissOnLostFocus(hasGainedFocus, isSettling)) {
                    lastDismissEpochMs = System.currentTimeMillis()
                    dismissFlyout()
                }
            }
        })
    }

    val isShowing: Boolean
        get() = dialog.isVisible

    fun toggleOrShow(anchor: Point, devices: List<DesktopTrayDeviceSnapshot>) {
        val now = System.currentTimeMillis()
        if (DesktopTrayFocusGuard.shouldIgnoreTrayClick(lastDismissEpochMs, now)) {
            return
        }
        if (dialog.isVisible) {
            dismissFlyout()
            return
        }
        show(anchor, devices)
    }

    fun show(anchor: Point, devices: List<DesktopTrayDeviceSnapshot>) {
        onRefreshDevices()
        val workArea = DesktopTrayPositioning.resolveWorkArea(anchor)
        availableHeightDp = workArea.height - SCREEN_MARGIN_DP
        devicesState = devices
        selectedIdsState = emptySet()
        checkingBatteriesState = false
        columnsState = columnsFor(devices)
        hasGainedFocus = false
        isSettling = true

        dialog.pack()
        val preferred = dialog.preferredSize
        val bounds = DesktopTrayPositioning.calculateBounds(anchor, preferred, workArea)

        dialog.bounds = bounds
        dialog.isVisible = true
        dialog.toFront()
        dialog.requestFocus()

        // Windows reports a focus loss while the dialog is still activating; ignore it for this long.
        val settleTimer = Timer(180) {
            isSettling = false
        }
        settleTimer.isRepeats = false
        settleTimer.start()
    }

    fun dismissFlyout() {
        if (!dialog.isVisible) return
        dialog.isVisible = false
        selectedIdsState = emptySet()
        hasGainedFocus = false
        isSettling = false
    }

    fun updateDevices(devices: List<DesktopTrayDeviceSnapshot>) {
        devicesState = devices
        selectedIdsState = selectedIdsState.filter { id -> devices.any { it.id == id } }.toSet()
        columnsState = columnsFor(devices)
        if (devices.any { it.batteryText.isNotEmpty() }) {
            checkingBatteriesState = false
        }
        if (dialog.isVisible) {
            dialog.pack()
        }
    }

    /** Called when a battery check ended with no reading at all, so the button works again. */
    fun batteryCheckFinished() {
        checkingBatteriesState = false
    }

    private fun columnsFor(devices: List<DesktopTrayDeviceSnapshot>): Int =
        trayColumnCount(devices.size, availableHeightDp, TRAY_ROW_HEIGHT_DP, TRAY_CHROME_HEIGHT_DP)

    fun dispose() {
        dismissFlyout()
        dialog.dispose()
    }
}
