package com.fileapex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.data.db.createFileApexDatabase
import com.fileapex.data.settings.DesktopLayoutMode
import com.fileapex.di.FileApexServices
import com.fileapex.network.DesktopShareServerController
import com.fileapex.platform.DesktopMacTrayCoordinator
import com.fileapex.platform.DesktopScreenGeometry
import com.fileapex.platform.DesktopWindowBoundsStore
import com.fileapex.platform.DesktopSendHandoff
import com.fileapex.platform.MacOsExtensionRegistrar
import com.fileapex.ui.DeviceCardSlotHeight
import com.fileapex.ui.DeviceListToAddGap
import com.fileapex.update.AppUpdateCoordinator
import com.fileapex.update.FileApexAppVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview

private val DesktopWindowCompactWidth = 440.dp
private val DesktopWindowExpandedWidth = 1200.dp
private val DesktopWindowMinHeight = 560.dp
private val DesktopWindowMaxHeight = 900.dp

fun main() {
    MacOsExtensionRegistrar.registerOnLaunchDeferred()
    DesktopSendHandoff.installOpenUriHandler()
    FileApexServices.beginBootstrap { createFileApexDatabase() }

    application {
        var servicesReady by remember { mutableStateOf(FileApexServices.isBootstrapComplete) }

        LaunchedEffect(Unit) {
            if (!servicesReady) {
                FileApexServices.awaitBootstrap()
            }
            servicesReady = true
        }

        LaunchedEffect(servicesReady) {
            if (!servicesReady) return@LaunchedEffect
            launch(Dispatchers.Default) {
                AppUpdateCoordinator.onAppLaunch()
                GoogleLinkCoordinator.onAppLaunch()
                DesktopSendHandoff.startJobProcessor()
            }
        }

        val savedBounds = remember { DesktopWindowBoundsStore.loadValidated() }
        val deviceFlow = remember(servicesReady) {
            if (servicesReady) {
                FileApexServices.deviceRepository.observeDevices()
            } else {
                flowOf(emptyList())
            }
        }
        val devices by deviceFlow.collectAsState(initial = emptyList())
        val layoutModeFlow = remember(servicesReady) {
            if (servicesReady) {
                FileApexServices.settings.desktopLayoutMode
            } else {
                flowOf(DesktopLayoutMode.Compact)
            }
        }
        val desktopLayoutMode by layoutModeFlow.collectAsState(initial = DesktopLayoutMode.Compact)
        val defaultBootstrapSize = DpSize(
            width = DesktopWindowCompactWidth,
            height = DesktopWindowMinHeight
        )
        val initialSize = savedBounds?.toDpSize() ?: defaultBootstrapSize
        val initialPosition = savedBounds?.toWindowPosition()
            ?: DesktopScreenGeometry.primaryTopLeftPosition()

        val windowState = rememberWindowState(
            width = initialSize.width,
            height = initialSize.height,
            position = initialPosition
        )

        LaunchedEffect(servicesReady, devices.size, desktopLayoutMode) {
            if (!servicesReady) return@LaunchedEffect
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
            com.fileapex.cloud.DesktopAuthCoordinator.cancelPending()
            DesktopShareServerController.shutdownForQuit()
        }

        Window(
            onCloseRequest = {
                if (DesktopMacTrayCoordinator.handleCloseRequest()) {
                    return@Window
                }
                shutdownDesktop()
                exitApplication()
            },
            title = "FileApex",
            state = windowState
        ) {
            if (!servicesReady) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                return@Window
            }

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
                appVersionName = FileApexAppVersion.NAME
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
