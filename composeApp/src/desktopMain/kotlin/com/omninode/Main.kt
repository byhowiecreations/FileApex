package com.omninode

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.omninode.cloud.GoogleLinkCoordinator
import com.omninode.data.db.createOmniNodeDatabase
import com.omninode.data.settings.DesktopLayoutMode
import com.omninode.di.OmniNodeServices
import com.omninode.network.DesktopShareServerController
import com.omninode.platform.DesktopMacTrayCoordinator
import com.omninode.platform.DesktopScreenGeometry
import com.omninode.platform.DesktopWindowBoundsStore
import com.omninode.platform.DesktopSendHandoff
import com.omninode.platform.MacOsExtensionRegistrar
import com.omninode.ui.DeviceCardSlotHeight
import com.omninode.ui.DeviceListToAddGap
import com.omninode.update.AppUpdateCoordinator
import com.omninode.update.OmniNodeAppVersion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.FlowPreview

private val DesktopWindowCompactWidth = 440.dp
private val DesktopWindowExpandedWidth = 1200.dp
private val DesktopWindowMinHeight = 560.dp
private val DesktopWindowMaxHeight = 900.dp

fun main() {
    OmniNodeServices.init(createOmniNodeDatabase())
    AppUpdateCoordinator.onAppLaunch()
    GoogleLinkCoordinator.onAppLaunch()
    MacOsExtensionRegistrar.registerOnLaunch()
    DesktopShareServerController.startWakeListener()
    DesktopSendHandoff.installOpenUriHandler()
    DesktopSendHandoff.startJobProcessor()

    application {
        val devices by OmniNodeServices.deviceRepository.observeDevices()
            .collectAsState(initial = emptyList())
        val desktopLayoutMode by OmniNodeServices.settings.desktopLayoutMode.collectAsState()
        val savedBounds = remember { DesktopWindowBoundsStore.loadValidated() }
        val defaultSize = preferredWindowSize(
            deviceCount = devices.size,
            layoutMode = desktopLayoutMode
        )
        val initialSize = savedBounds?.toDpSize() ?: defaultSize
        val initialPosition = savedBounds?.toWindowPosition()
            ?: DesktopScreenGeometry.primaryTopLeftPosition()

        val windowState = rememberWindowState(
            width = initialSize.width,
            height = initialSize.height,
            position = initialPosition
        )

        LaunchedEffect(devices.size, desktopLayoutMode) {
            if (!DesktopWindowBoundsStore.hasValidSaved()) {
                windowState.size = preferredWindowSize(
                    deviceCount = devices.size,
                    layoutMode = desktopLayoutMode
                )
            }
        }

        LaunchedEffect(windowState) {
            @OptIn(FlowPreview::class)
            snapshotFlow {
                Triple(windowState.size, windowState.position, windowState.isMinimized)
            }
                .distinctUntilChanged()
                .debounce(400)
                .collect { (size, position, minimized) ->
                    if (!minimized) {
                        DesktopWindowBoundsStore.persist(size, position)
                    }
                }
        }

        fun shutdownDesktop() {
            if (!windowState.isMinimized) {
                DesktopWindowBoundsStore.persist(windowState.size, windowState.position)
            }
            DesktopShareServerController.shutdownBlocking()
        }

        Window(
            onCloseRequest = {
                if (DesktopMacTrayCoordinator.handleCloseRequest()) {
                    return@Window
                }
                shutdownDesktop()
                exitApplication()
            },
            title = "OmniNode",
            state = windowState
        ) {
            LaunchedEffect(window) {
                DesktopMacTrayCoordinator.attachMainWindow(window) {
                    shutdownDesktop()
                    exitApplication()
                }
            }

            App(
                hasStoragePermission = true,
                hasUnrestrictedBattery = true,
                onRequestStoragePermission = {},
                onOpenStorageSettings = {},
                onRequestBatteryUnrestricted = {},
                onStartShareServer = DesktopShareServerController::start,
                onStopShareServer = DesktopShareServerController::stop,
                onExitApp = {
                    shutdownDesktop()
                    exitApplication()
                },
                onScanQr = {},
                appVersionName = OmniNodeAppVersion.NAME
            )
        }
    }
}

private fun preferredWindowSize(deviceCount: Int, layoutMode: DesktopLayoutMode): DpSize {
    val chromeHeight = 286.dp
    val cardSlots = deviceCount + 1
    val height = (
        chromeHeight +
            DeviceCardSlotHeight * cardSlots +
            DeviceListToAddGap
        ).coerceIn(DesktopWindowMinHeight, DesktopWindowMaxHeight)
    val width = when (layoutMode) {
        DesktopLayoutMode.Compact -> DesktopWindowCompactWidth
        DesktopLayoutMode.Expanded -> DesktopWindowExpandedWidth
    }
    return DpSize(width = width, height = height)
}
