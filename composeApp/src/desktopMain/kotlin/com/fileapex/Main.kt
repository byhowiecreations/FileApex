package com.fileapex

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Desktop
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.data.db.createFileApexDatabase
import com.fileapex.data.settings.DesktopLayoutMode
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.data.settings.createAppSettings
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
import com.fileapex.data.settings.AppTheme
import com.fileapex.platform.DesktopWindowBoundsStore
import com.fileapex.platform.MacOsExtensionRegistrar
import com.fileapex.platform.DesktopWindowsBackdrop
import com.fileapex.ui.devices.DeviceCardSlotHeight
import com.fileapex.ui.devices.DeviceListToAddGap
import com.fileapex.ui.ThemeDeviceIconPreloader
import com.fileapex.update.AppUpdateCoordinator
import com.fileapex.update.FileApexAppVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.withContext

import com.fileapex.domain.share.IncomingSharePayload
import com.fileapex.domain.launch.DesktopLaunchInbox
import com.fileapex.domain.launch.DesktopLaunchRequest
import com.fileapex.cli.ipc.CliIpcDaemon
import com.fileapex.platform.DesktopSingleInstance
import com.fileapex.platform.DesktopUriHandler

/** The window size is remembered per theme; Carousel's key is versioned so a new default takes effect once. */
private fun sizeKey(theme: AppTheme): String = if (theme == AppTheme.CAROUSEL) "CAROUSEL_2" else theme.name

private val DesktopWindowCompactWidth = 440.dp
private val DesktopWindowExpandedWidth = 1200.dp
private val DesktopWindowMinHeight = 560.dp
private val DesktopWindowMaxHeight = 900.dp

fun main(args: Array<String>) {
    com.fileapex.platform.ThreadTuning.apply()
    com.fileapex.util.LogRedaction.install()
    DesktopCrashHandler.install()
    try {
        if (isCliInvocation(args)) {
            if (com.fileapex.platform.DesktopPlatformPaths.isWindows()) {
                com.fileapex.platform.DesktopWindowsConsole.attachConsole()
            }
            com.fileapex.cli.CliRunner.run(if (args.isEmpty()) arrayOf("help") else args)
            return
        }
        val atSignIn = com.fileapex.platform.DesktopAutostart.isAutostartLaunch(args)
        val launch = DesktopLaunchRequest(
            args = args.filterNot { it.equals(com.fileapex.platform.DesktopAutostart.FLAG, ignoreCase = true) },
            cwd = System.getProperty("user.dir"), activate = !atSignIn
        )
        if (DesktopSingleInstance.start(DesktopPlatformPaths.applicationSupportDirectory(), launch, DesktopLaunchInbox.live::offer) == null) return
        com.fileapex.security.tls.PeerTlsConnector.configure { com.fileapex.security.tls.DesktopTlsIdentity.store() }
        DesktopJvmStartup.onMainEntry()
        com.fileapex.security.tls.TlsFrontFactory.install { com.fileapex.security.tls.DesktopTlsIdentity.store() }
        FileApexServices.beginBootstrap(
            createDatabase = { createFileApexDatabase() },
            legacyBulletinPath = com.fileapex.platform.DesktopPlatformPaths.bulletinBoardDatabaseFile().absolutePath
        )

        // A start at sign-in opens the window minimized (it stays in the taskbar), not in front of the user's work.
        com.fileapex.platform.DesktopAutostart.launchedAtSignIn = atSignIn
        Thread {
            runCatching { com.fileapex.platform.BootLaunchPreference.syncFromSettings() }
        }.apply { isDaemon = true; name = "autostart-sync" }.start()
        startDesktopApplication()
    } catch (t: Throwable) {
        DesktopCrashHandler.handleCrash(Thread.currentThread(), t)
        throw t
    }
}

private fun isCliInvocation(args: Array<String>): Boolean {
    val appPath = System.getProperty("jpackage.app-path").orEmpty().lowercase()
    if (appPath.endsWith("fileapexcli.exe") || appPath.endsWith("fileapex.cmd")) {
        return true
    }
    val command = ProcessHandle.current().info().command().orElse("").lowercase()
    if (command.endsWith("fileapexcli.exe")) {
        return true
    }
    val first = args.firstOrNull()?.lowercase() ?: return false
    return first in setOf(
        "help", "-h", "--help", "dash", "dashboard",
        "devices", "alias", "send", "clip", "queue", "--version", "-v"
    )
}

private fun startDesktopApplication() {
    if (DesktopPlatformPaths.isMacOs()) {
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
    }
    MacLaunchSplash.show()
    // Decode Freestyle/Flux PNGs off the UI thread while the window comes up.
    ThemeDeviceIconPreloader.startFor(createAppSettings().themeIconStyle.value)

    // Mac: never System.exit when Compose scope ends, native tray + share server keep running.
    application(exitProcessOnExit = !DesktopPlatformPaths.isMacOs()) {
        var servicesReady by remember { mutableStateOf(FileApexServices.isBootstrapComplete) }
        var mainWindowVisible by remember { mutableStateOf(true) }
        var desktopIncomingShare by remember { mutableStateOf<IncomingSharePayload?>(null) }
        val launchRequests by DesktopLaunchInbox.live.pending.collectAsState()

        LaunchedEffect(Unit) {
            val t0 = System.nanoTime()
            if (!servicesReady) {
                FileApexServices.awaitBootstrap()
            }
            servicesReady = true
            DesktopLifecycleLog.log(
                "Main: servicesReady after ${(System.nanoTime() - t0) / 1_000_000L}ms await"
            )
        }

        LaunchedEffect(servicesReady) {
            if (!servicesReady) return@LaunchedEffect
            withContext(Dispatchers.IO) { CliIpcDaemon.start() }
            launch(Dispatchers.Default) {
                AppUpdateCoordinator.onAppLaunch()
                com.fileapex.notice.NoticeCoordinator.onAppLaunch()
                GoogleLinkCoordinator.onAppLaunch()
                com.fileapex.cloud.drive.DriveRelayCoordinator.onAppLaunch()
                com.fileapex.platform.LegacyHandoffCleanup.run()
            }
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
        val deviceCountFlow = remember(servicesReady) {
            if (servicesReady) {
                FileApexServices.deviceRepository.observeDevices()
                    .map { it.size }
                    .distinctUntilChanged()
            } else {
                flowOf(0)
            }
        }
        val deviceCount by deviceCountFlow.collectAsState(initial = 0)
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
            position = initialPosition,
            isMinimized = com.fileapex.platform.DesktopAutostart.launchedAtSignIn
        )

        val themeFlow = remember(servicesReady) {
            if (servicesReady) FileApexServices.settings.appTheme else flowOf(null)
        }
        val appTheme by themeFlow.collectAsState(initial = null)
        var sizedTheme by remember { mutableStateOf<AppTheme?>(null) }
        // Each theme keeps its own window size; switching themes restores the size last used with that theme.
        LaunchedEffect(appTheme) {
            val theme = appTheme ?: return@LaunchedEffect
            sizedTheme?.let { DesktopWindowBoundsStore.persistSize(sizeKey(it), windowState.size) }
            val remembered = DesktopWindowBoundsStore.loadSize(sizeKey(theme))
            if (remembered != null) {
                windowState.size = remembered
            } else if (theme == AppTheme.CAROUSEL) {
                // Wide 16:9 the first time (the saved key is versioned so a changed default applies once); the size left behind is already saved under the previous theme.
                windowState.size = DpSize(1024.dp, 576.dp)
            }
            sizedTheme = theme
        }

        LaunchedEffect(servicesReady, deviceCount, desktopLayoutMode) {
            if (!servicesReady) return@LaunchedEffect
            if (!DesktopWindowBoundsStore.hasValidSaved()) {
                windowState.size = preferredWindowSize(
                    deviceCount = deviceCount,
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
                        sizedTheme?.let { DesktopWindowBoundsStore.persistSize(sizeKey(it), size) }
                    }
                }
        }

        fun shutdownDesktop() {
            DesktopLifecycleLog.log("shutdownDesktop")
            if (!windowState.isMinimized) {
                DesktopWindowBoundsStore.persist(windowState.size, windowState.position)
                sizedTheme?.let { DesktopWindowBoundsStore.persistSize(sizeKey(it), windowState.size) }
            }
            com.fileapex.cloud.DesktopAuthCoordinator.cancelPending()
            DesktopShareServerController.shutdownForQuit()
            DesktopTraySupport.dispose()
            DesktopUriHandler.stop()
            DesktopSingleInstance.stopLiveForwarding()
            CliIpcDaemon.stop()
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
            // Mac: never bind Compose visible to hide, sole Window hidden => application{} exits (#1897).
            // Windows: visible=false is the supported hide-to-tray path (CMP #2928).
            visible = if (DesktopPlatformPaths.isMacOs()) true else mainWindowVisible,
        ) {
            // Light shell first so splash hide + tray attach are not stuck behind Kinetic/PNG
            // first composition (that path was ~6 to 10s of Dock bounce / frozen splash).
            var mountApp by remember { mutableStateOf(false) }
            var launchRoutingReady by remember { mutableStateOf(false) }

            LaunchedEffect(window, mountApp) {
                // The tray and window-policy setup blocks the UI thread, so it runs after the app's first frame.
                if (!mountApp) return@LaunchedEffect
                withFrameNanos { }
                DesktopAppIcon.loadTrayImage()?.let { window.iconImage = it }
                if (DesktopPlatformPaths.isMacOs()) {
                    DesktopMacWindowClosePolicy.install(window)
                }
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
                launchRoutingReady = true
            }

            val nextLaunch = launchRequests.firstOrNull()
            LaunchedEffect(servicesReady, launchRoutingReady, nextLaunch?.id, desktopIncomingShare?.sessionId) {
                val input = nextLaunch ?: return@LaunchedEffect
                if (!servicesReady || !launchRoutingReady || (input.share != null && desktopIncomingShare != null)) return@LaunchedEffect
                if (input.activate) {
                    mainWindowVisible = true
                    windowState.isMinimized = false
                    DesktopTraySupport.showMainWindow()
                    window.toFront()
                    window.requestFocus()
                }
                input.share?.let { desktopIncomingShare = it }
                input.uris.forEach { text ->
                    val uri = java.net.URI(text)
                    if (uri.scheme.equals("fileapex", true) && uri.host == "friend")
                        com.fileapex.friends.ui.FriendsUi.openWithInvite(text)
                    else if (!DesktopUriHandler.handleOAuthCallback(uri))
                        println("Desktop launch: unsupported app URI")
                }
                DesktopLaunchInbox.live.consume(input.id)
            }

            LaunchedEffect(window) {
                withFrameNanos { }
                DesktopLifecycleLog.log("Main: first light frame")
                // The splash stays up until the app's first frame; this only guards against a stuck start.
                delay(15_000)
                MacLaunchSplash.hide()
            }

            LaunchedEffect(window, servicesReady) {
                if (!servicesReady) return@LaunchedEffect
                val t0 = System.nanoTime()
                val iconsReady = withContext(Dispatchers.Default) {
                    ThemeDeviceIconPreloader.awaitReady()
                }
                DesktopLifecycleLog.log(
                    "Main: theme icons ready=${iconsReady} " +
                        "after ${(System.nanoTime() - t0) / 1_000_000L}ms"
                )
                mountApp = true
            }

            LaunchedEffect(mountApp, mainWindowVisible) {
                if (!mountApp || !mainWindowVisible) return@LaunchedEffect
                withFrameNanos { }
                DesktopLifecycleLog.log("Main: first App frame")
                MacLaunchSplash.hide()
                DesktopShareServerController.start()
                // Let the window take input before LAN/clipboard/cloud storm.
                repeat(2) { withFrameNanos { } }
                delay(350)
                DesktopLifecycleLog.log("Main: ui interactive")
                FileApexServices.presenceMonitor.markUiInteractive()
                withContext(Dispatchers.Default) {
                    PresenceForegroundRefresh.onAppForegrounded()
                }
                MacOsExtensionRegistrar.registerOnLaunchDeferred()
                withContext(Dispatchers.IO) { com.fileapex.platform.LinuxDesktopIntegration.ensureInstalled() }
            }

            LaunchedEffect(window, desktopUiStyle) {
                if (!DesktopPlatformPaths.isWindows()) return@LaunchedEffect
                val fluent = desktopUiStyle == DesktopUiStyle.WindowsFluent
                DesktopWindowsBackdrop.applyMica(window, fluent)
            }

            if (!servicesReady || !mountApp) {
                // Same colour as the launch splash so the hand-off to the app is not visible.
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF122A2A)))
                return@Window
            }

            val desktopClipboardOptInSender by com.fileapex.platform.DesktopClipboardOptInState.pendingSender.collectAsState()
            val desktopOpenDeviceId by com.fileapex.platform.DesktopOpenDeviceRequest.pendingDeviceId.collectAsState()

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
                onIncomingShareConsumed = { desktopIncomingShare = null },
                pendingClipboardOptInSender = desktopClipboardOptInSender,
                onClipboardOptInConsumed = { com.fileapex.platform.DesktopClipboardOptInState.consume() },
                pendingOpenDeviceId = desktopOpenDeviceId,
                onOpenDeviceRequestConsumed = { com.fileapex.platform.DesktopOpenDeviceRequest.consume() }
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
