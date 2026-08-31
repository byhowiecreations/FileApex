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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Desktop
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.data.db.createFileApexDatabase
import com.fileapex.data.bulletin.createBulletinBoardDatabase
import com.fileapex.data.settings.DesktopLayoutMode
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.di.FileApexServices
import com.fileapex.network.DesktopShareServerController
import com.fileapex.domain.presence.PresenceForegroundRefresh
import com.fileapex.platform.DesktopAppIcon
import com.fileapex.platform.DesktopCrashHandler
import com.fileapex.platform.DesktopJvmStartup
import com.fileapex.platform.DesktopLifecycleLog
import com.fileapex.platform.DesktopMacTrayBridge
import com.fileapex.platform.DesktopMacWindowClosePolicy
import com.fileapex.platform.DesktopPlatformPaths
import com.fileapex.platform.MacLaunchSplash
import com.fileapex.platform.DesktopScreenGeometry
import com.fileapex.platform.DesktopTraySupport
import com.fileapex.platform.DesktopWindowBoundsStore
import com.fileapex.platform.MacOsExtensionRegistrar
import com.fileapex.platform.DesktopSendHandoff
import com.fileapex.platform.DesktopBulletinHandoff
import com.fileapex.platform.DesktopWindowsBackdrop
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

import com.fileapex.domain.share.IncomingShareFile
import com.fileapex.domain.share.IncomingSharePayload
import com.fileapex.platform.DesktopSingleInstance
import java.io.File
import java.util.UUID

private val DesktopWindowCompactWidth = 440.dp
private val DesktopWindowExpandedWidth = 1200.dp
private val DesktopWindowMinHeight = 560.dp
private val DesktopWindowMaxHeight = 900.dp

fun main(args: Array<String>) {
    DesktopCrashHandler.install()
    try {
        if (DesktopSingleInstance.handleSingleInstanceOrHandoff(args)) {
            return
        }
        DesktopJvmStartup.onMainEntry()
        FileApexServices.beginBootstrap(
            createDatabase = { createFileApexDatabase() },
            createBulletinBoard = { createBulletinBoardDatabase() }
        )

        val initialCliSharePayload = parseCliSharePayload(args)
        startDesktopApplication(initialCliSharePayload)
    } catch (t: Throwable) {
        DesktopCrashHandler.handleCrash(Thread.currentThread(), t)
        throw t
    }
}

private fun startDesktopApplication(initialCliSharePayload: IncomingSharePayload?) {
    MacLaunchSplash.show()

    // Mac: never System.exit when Compose scope ends — native tray + share server keep running.
    application(exitProcessOnExit = !DesktopPlatformPaths.isMacOs()) {
        var servicesReady by remember { mutableStateOf(FileApexServices.isBootstrapComplete) }
        var mainWindowVisible by remember { mutableStateOf(true) }
        var desktopIncomingShare by remember { mutableStateOf(initialCliSharePayload) }

        LaunchedEffect(Unit) {
            DesktopSingleInstance.incomingCliShares.collect { payload ->
                desktopIncomingShare = payload
                if (DesktopPlatformPaths.isMacOs()) {
                    DesktopTraySupport.showMainWindow()
                } else {
                    mainWindowVisible = true
                }
            }
        }

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
                com.fileapex.cloud.drive.DriveRelayCoordinator.onAppLaunch()
                DesktopSendHandoff.startJobProcessor()
                DesktopBulletinHandoff.startJobProcessor()
            }
        }

        // Defer share-server bind until the window is on screen (avoids Windows Firewall
        // "Java Platform SE binary" prompt before the user sees FileApex).
        LaunchedEffect(servicesReady, mainWindowVisible) {
            if (!servicesReady || !mainWindowVisible) return@LaunchedEffect
            DesktopShareServerController.start()
            PresenceForegroundRefresh.onAppForegrounded()
        }

        LaunchedEffect(mainWindowVisible) {
            if (!mainWindowVisible || !DesktopPlatformPaths.isWindows()) return@LaunchedEffect
            runCatching {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
                    desktop.requestForeground(true)
                }
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
        val uiStyleFlow = remember(servicesReady) {
            if (servicesReady) {
                FileApexServices.settings.desktopUiStyle
            } else {
                flowOf(DesktopUiStyle.Standard)
            }
        }
        val desktopUiStyle by uiStyleFlow.collectAsState(initial = DesktopUiStyle.Standard)
        val defaultBootstrapSize = DpSize(
            width = DesktopWindowCompactWidth,
            height = DesktopWindowMinHeight
        )
        val initialSize = savedBounds?.toDpSize() ?: defaultBootstrapSize
        val initialPosition = savedBounds?.toWindowPosition()
            ?: if (DesktopPlatformPaths.isWindows()) {
                WindowPosition(Alignment.Center)
            } else {
                DesktopScreenGeometry.primaryTopLeftPosition()
            }

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
            DesktopLifecycleLog.log("shutdownDesktop")
            if (!windowState.isMinimized) {
                DesktopWindowBoundsStore.persist(windowState.size, windowState.position)
            }
            com.fileapex.cloud.DesktopAuthCoordinator.cancelPending()
            DesktopShareServerController.shutdownForQuit()
            DesktopTraySupport.dispose()
        }

        fun quitDesktop() {
            shutdownDesktop()
            if (DesktopPlatformPaths.isMacOs()) {
                DesktopMacTrayBridge.requestAppTerminate()
            } else {
                exitApplication()
            }
        }

        Window(
            onCloseRequest = {
                DesktopLifecycleLog.log("onCloseRequest")
                if (DesktopTraySupport.handleCloseRequest()) return@Window
                quitDesktop()
            },
            title = "FileApex",
            state = windowState,
            // Mac: never bind Compose visible to hide — sole Window hidden => application{} exits (#1897).
            // Windows: visible=false is the supported hide-to-tray path (CMP #2928).
            visible = if (DesktopPlatformPaths.isMacOs()) true else mainWindowVisible,
        ) {
            LaunchedEffect(window) {
                run {
                    repeat(60) {
                        if (window.isShowing) return@run
                        withFrameNanos { }
                    }
                }
                MacLaunchSplash.hide()
            }

            LaunchedEffect(window) {
                DesktopAppIcon.loadTrayImage()?.let { window.iconImage = it }
                if (DesktopPlatformPaths.isMacOs()) {
                    DesktopMacWindowClosePolicy.install(window)
                }
                withFrameNanos { }
                withFrameNanos { }
                DesktopTraySupport.attachMainWindow(
                    window = window,
                    onShowWindow = {
                        if (!DesktopPlatformPaths.isMacOs()) mainWindowVisible = true
                    },
                    onHideWindow = {
                        if (!DesktopPlatformPaths.isMacOs()) mainWindowVisible = false
                    },
                ) {
                    quitDesktop()
                }
                if (DesktopPlatformPaths.isMacOs()) {
                    DesktopMacTrayBridge.installAppLifecycle()
                }
            }

            if (!servicesReady) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                return@Window
            }

            LaunchedEffect(servicesReady) {
                if (!servicesReady) return@LaunchedEffect
                MacOsExtensionRegistrar.registerOnLaunchDeferred()
            }

            LaunchedEffect(window, desktopUiStyle) {
                if (!DesktopPlatformPaths.isWindows()) return@LaunchedEffect
                val fluent = desktopUiStyle == DesktopUiStyle.WindowsFluent
                DesktopWindowsBackdrop.applyMica(window, fluent)
            }

            App(
                hasStoragePermission = true,
                hasUnrestrictedBattery = true,
                onRequestStoragePermission = {},
                onOpenStorageSettings = {},
                onRequestBatteryUnrestricted = {},
                onStartShareServer = DesktopShareServerController::start,
                onStopShareServer = DesktopShareServerController::stop,
                onExitApp = { quitDesktop() },
                appVersionName = FileApexAppVersion.NAME,
                incomingShare = desktopIncomingShare,
                onIncomingShareConsumed = { desktopIncomingShare = null }
            )
        }

    }
}

private fun parseCliSharePayload(args: Array<String>): IncomingSharePayload? {
    if (args.isEmpty()) return null
    val files = args.mapNotNull { pathStr ->
        val file = File(pathStr)
        if (file.exists()) {
            IncomingShareFile(
                fileName = file.name,
                absolutePath = file.absolutePath,
                sizeBytes = if (file.isFile) file.length() else 0L
            )
        } else null
    }
    if (files.isEmpty()) return null
    return IncomingSharePayload(
        sessionId = UUID.randomUUID().toString(),
        files = files
    )
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
