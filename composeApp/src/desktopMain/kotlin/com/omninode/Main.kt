package com.omninode

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.omninode.cloud.GoogleLinkCoordinator
import com.omninode.data.db.createOmniNodeDatabase
import com.omninode.di.OmniNodeServices
import com.omninode.network.DesktopShareServerController
import com.omninode.platform.DesktopSendHandoff
import com.omninode.platform.MacOsExtensionRegistrar
import com.omninode.ui.DeviceCardSlotHeight
import com.omninode.ui.DeviceListToAddGap
import com.omninode.update.AppUpdateCoordinator
import com.omninode.update.OmniNodeAppVersion
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.flow

private val DesktopWindowWidth = 440.dp
private val DesktopWindowMinHeight = 560.dp
private val DesktopWindowMaxHeight = 900.dp

fun main() {
    OmniNodeServices.init(createOmniNodeDatabase())
    AppUpdateCoordinator.onAppLaunch()
    GoogleLinkCoordinator.onAppLaunch()
    MacOsExtensionRegistrar.registerOnLaunch()
    DesktopSendHandoff.installOpenUriHandler()

    application {
        val devices by OmniNodeServices.deviceRepository.observeDevices()
            .collectAsState(initial = emptyList())
        val windowState = rememberWindowState(
            size = preferredWindowSize(deviceCount = devices.size)
        )

        LaunchedEffect(devices.size) {
            windowState.size = preferredWindowSize(deviceCount = devices.size)
        }

        // Finder Sync / Share Extension → same Multi Copy transfer stack as the main UI.
        LaunchedEffect(Unit) {
            val pending = flow {
                DesktopSendHandoff.listPendingJobIds().forEach { emit(it) }
            }
            merge(pending, DesktopSendHandoff.incomingJobIds).collect { jobId ->
                DesktopSendHandoff.processJob(jobId)
            }
        }

        Window(
            onCloseRequest = {
                DesktopShareServerController.shutdownBlocking()
                exitApplication()
            },
            title = "OmniNode",
            state = windowState
        ) {
            App(
                hasStoragePermission = true,
                hasUnrestrictedBattery = true,
                onRequestStoragePermission = {},
                onOpenStorageSettings = {},
                onRequestBatteryUnrestricted = {},
                onStartShareServer = DesktopShareServerController::start,
                onStopShareServer = DesktopShareServerController::stop,
                onExitApp = {
                    DesktopShareServerController.shutdownBlocking()
                    exitApplication()
                },
                onScanQr = {},
                appVersionName = OmniNodeAppVersion.NAME
            )
        }
    }
}

private fun preferredWindowSize(deviceCount: Int): DpSize {
    // Top bar + nav + compact Add (+ small padding around it).
    val chromeHeight = 286.dp
    val cardSlots = deviceCount + 1 // include "This device"
    // Tall enough for every card plus list bottom padding (~2 empty card rows).
    val height = (
        chromeHeight +
            DeviceCardSlotHeight * cardSlots +
            DeviceListToAddGap
        ).coerceIn(DesktopWindowMinHeight, DesktopWindowMaxHeight)
    return DpSize(width = DesktopWindowWidth, height = height)
}
