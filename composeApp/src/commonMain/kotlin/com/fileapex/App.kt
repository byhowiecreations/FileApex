package com.fileapex

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.domain.pairing.PairingPayload
import com.fileapex.domain.share.IncomingSharePayload
import com.fileapex.data.settings.DesktopLayoutMode
import com.fileapex.di.FileApexServices
import com.fileapex.domain.presence.PresenceForegroundRefresh
import com.fileapex.navigation.AppRoute
import com.fileapex.platform.BackgroundPersistenceUiState
import com.fileapex.platform.FileApexBackHandler
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.presentation.DevicesViewModel
import com.fileapex.session.DeviceSessionManager
import com.fileapex.update.AppUpdateCoordinator
import com.fileapex.ui.DevicesScreen
import com.fileapex.ui.FileExplorerScreen
import com.fileapex.ui.GenerateQrScreen
import com.fileapex.ui.ExplorerViewModeToggle
import com.fileapex.ui.HomeTab
import com.fileapex.ui.SettingsScreen
import com.fileapex.ui.SettingsScreenLayoutMode
import com.fileapex.ui.ShareSendScreen
import com.fileapex.ui.StoragePermissionScreen
import com.fileapex.ui.UpdateAvailableSheet
import com.fileapex.ui.adaptive.AdaptiveWideHome
import com.fileapex.ui.adaptive.CompactPrimaryShell
import com.fileapex.ui.adaptive.widthSizeClassFor
import com.fileapex.ui.adaptive.isWide
import com.fileapex.ui.theme.FileApexTheme
import com.fileapex.ui.theme.FileApexTeal

@Composable
fun App(
    hasStoragePermission: Boolean,
    hasUnrestrictedBattery: Boolean = true,
    backgroundPersistence: BackgroundPersistenceUiState = BackgroundPersistenceUiState(),
    onRequestStoragePermission: () -> Unit,
    onOpenStorageSettings: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit = {},
    onOpenBackgroundPersistenceSettings: () -> Unit = {},
    onOpenUnusedAppRestrictionsSettings: () -> Unit = {},
    onOpenAppBatteryUsageSettings: () -> Unit = {},
    exactAlarmWarningActive: Boolean = false,
    onOpenExactAlarmSettings: () -> Unit = {},
    onOpenAppDetailsSettings: () -> Unit = {},
    onStartShareServer: () -> Unit,
    onStopShareServer: () -> Unit,
    onExitApp: () -> Unit,
    onScanQr: () -> Unit,
    appVersionName: String,
    scannedPayload: PairingPayload? = null,
    onScannedPayloadConsumed: () -> Unit = {},
    qrScanError: String? = null,
    onQrScanErrorConsumed: () -> Unit = {},
    onPermissionRecheck: () -> Unit = {},
    incomingShare: IncomingSharePayload? = null,
    isPreparingShare: Boolean = false,
    sharePrepareError: String? = null,
    onIncomingShareConsumed: () -> Unit = {},
    onShareFlowFinished: () -> Unit = {},
    onDismissShareError: () -> Unit = {},
    directShareDeviceId: String? = null,
    requestShowUpdateSheet: Boolean = false,
    onUpdateSheetRequestConsumed: () -> Unit = {}
) {
    var route by remember { mutableStateOf<AppRoute>(AppRoute.Devices) }
    val devicesViewModel: DevicesViewModel = viewModel { DevicesViewModel() }
    val setupComplete = hasStoragePermission

    // Wide-layout detail state (list-detail). Survives compact/wide transitions.
    var wideSelectedTarget by remember { mutableStateOf<BrowseTarget?>(null) }
    var wideHomeTab by remember { mutableStateOf(HomeTab.Devices) }
    var previouslyWide by remember { mutableStateOf(false) }

    LaunchedEffect(scannedPayload) {
        val payload = scannedPayload ?: return@LaunchedEffect
        devicesViewModel.pairFromQrPayload(payload)
        onScannedPayloadConsumed()
        route = AppRoute.Devices
        wideHomeTab = HomeTab.Devices
    }

    LaunchedEffect(qrScanError) {
        val message = qrScanError ?: return@LaunchedEffect
        devicesViewModel.reportScanError(message)
        onQrScanErrorConsumed()
        route = AppRoute.Devices
        wideHomeTab = HomeTab.Devices
    }

    LaunchedEffect(incomingShare, setupComplete, directShareDeviceId) {
        val payload = incomingShare ?: return@LaunchedEffect
        if (!setupComplete) return@LaunchedEffect
        route = AppRoute.ShareSend(payload, directShareDeviceId)
        onIncomingShareConsumed()
    }

    LaunchedEffect(requestShowUpdateSheet) {
        if (requestShowUpdateSheet) {
            AppUpdateCoordinator.requestShowUpdateSheet()
            onUpdateSheetRequestConsumed()
        }
    }

    val pendingUpdate by AppUpdateCoordinator.pendingUpdate.collectAsState()
    val showUpdateSheet by AppUpdateCoordinator.showUpdateSheet.collectAsState()
    val explorerViewMode by FileApexServices.settings.explorerViewMode.collectAsState()
    val devicesViewMode by FileApexServices.settings.devicesViewMode.collectAsState()

    val onNavigateHome: () -> Unit = {
        route = AppRoute.Devices
        wideHomeTab = HomeTab.Devices
    }

    // Platform exit hooks own teardown (Android stops FGS; desktop uses shutdownForQuit).
    val exitFileApex: () -> Unit = onExitApp

    val finishShareFlow: () -> Unit = {
        route = AppRoute.Devices
        wideHomeTab = HomeTab.Devices
        onShareFlowFinished()
    }

    FileApexBackHandler(
        enabled = route !is AppRoute.Devices &&
            route !is AppRoute.Explorer &&
            route !is AppRoute.ShareSend &&
            setupComplete
    ) {
        onNavigateHome()
    }

    FileApexTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FileApexTeal)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
                color = Color.White
            ) {
                if (!setupComplete) {
                    StoragePermissionScreen(
                        hasStoragePermission = hasStoragePermission,
                        hasUnrestrictedBattery = hasUnrestrictedBattery,
                        oemBackgroundGuidance = backgroundPersistence.oemGuidance,
                        onRequestStoragePermission = onRequestStoragePermission,
                        onOpenStorageSettings = onOpenStorageSettings,
                        onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
                        onOpenBackgroundPersistenceSettings = onOpenBackgroundPersistenceSettings
                    )
                } else if (isPreparingShare) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Preparing shared files…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else if (sharePrepareError != null && route !is AppRoute.ShareSend) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = sharePrepareError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        TextButton(onClick = onDismissShareError) {
                            Text("Close")
                        }
                    }
                } else {
                    LaunchedEffect(Unit) {
                        if (!usesDesktopFileSelection()) {
                            onStartShareServer()
                        }
                    }

                    // Overlay routes stay full-screen on every size class.
                    when (val overlay = route) {
                        AppRoute.GenerateQr -> GenerateQrScreen(onBack = onNavigateHome)
                        is AppRoute.ShareSend -> ShareSendScreen(
                            payload = overlay.payload,
                            directTargetDeviceId = overlay.directTargetDeviceId,
                            onFinished = finishShareFlow
                        )
                        AppRoute.ScanQr -> {
                            route = AppRoute.Devices
                        }
                        else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val widthClass = widthSizeClassFor(maxWidth)
                            val desktopLayoutMode = if (usesDesktopFileSelection()) {
                                FileApexServices.settings.desktopLayoutMode.collectAsState().value
                            } else {
                                null
                            }
                            val isWide = when (desktopLayoutMode) {
                                DesktopLayoutMode.Compact -> false
                                DesktopLayoutMode.Expanded -> true
                                null -> widthClass.isWide
                            }

                            // Fold / unfold synchronization with the selected detail target.
                            LaunchedEffect(isWide, wideSelectedTarget, route) {
                                if (!isWide && previouslyWide && wideSelectedTarget != null) {
                                    // Folded while browsing in dual-pane → push detail full-screen.
                                    route = AppRoute.Explorer(wideSelectedTarget!!)
                                } else if (isWide && route is AppRoute.Explorer) {
                                    // Unfolded while on explorer → restore list-detail.
                                    wideSelectedTarget = (route as AppRoute.Explorer).target
                                    wideHomeTab = HomeTab.Devices
                                    route = AppRoute.Devices
                                } else if (isWide && route is AppRoute.Settings) {
                                    wideHomeTab = HomeTab.Settings
                                    route = AppRoute.Devices
                                }
                                previouslyWide = isWide
                            }

                            if (isWide &&
                                route !is AppRoute.Explorer &&
                                route !is AppRoute.Settings
                            ) {
                                AdaptiveWideHome(
                                    selectedTab = wideHomeTab,
                                    onSelectTab = { wideHomeTab = it },
                                    selectedTarget = wideSelectedTarget,
                                    selectedDeviceId = wideSelectedTarget?.deviceId,
                                    onSelectDevice = { target ->
                                        wideSelectedTarget = target
                                        wideHomeTab = HomeTab.Devices
                                    },
                                    onOpenLocalFiles = {
                                        wideSelectedTarget = devicesViewModel.thisDeviceTarget()
                                        wideHomeTab = HomeTab.Files
                                    },
                                    devicesViewMode = devicesViewMode,
                                    onToggleDevicesViewMode = {
                                        FileApexServices.settings.setDevicesViewMode(
                                            devicesViewMode.toggled()
                                        )
                                    },
                                    explorerViewMode = explorerViewMode,
                                    onToggleExplorerViewMode = {
                                        FileApexServices.settings.setExplorerViewMode(
                                            explorerViewMode.toggled()
                                        )
                                    },
                                    onGenerateQr = {
                                        onStartShareServer()
                                        route = AppRoute.GenerateQr
                                    },
                                    onScanQr = onScanQr,
                                    onExitApp = exitFileApex,
                                    onClearDetail = {
                                        wideSelectedTarget?.deviceId?.let {
                                            DeviceSessionManager.clearSession(it)
                                        }
                                        wideSelectedTarget = null
                                        wideHomeTab = HomeTab.Devices
                                    },
                                    appVersionName = appVersionName,
                                    devicesViewModel = devicesViewModel,
                                    backgroundPersistence = backgroundPersistence,
                                    onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
                                    onOpenBackgroundPersistenceSettings = onOpenBackgroundPersistenceSettings,
                                    onOpenUnusedAppRestrictionsSettings = onOpenUnusedAppRestrictionsSettings,
                                    onOpenAppBatteryUsageSettings = onOpenAppBatteryUsageSettings,
                                    exactAlarmWarningActive = exactAlarmWarningActive,
                                    onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                                    onOpenAppDetailsSettings = onOpenAppDetailsSettings
                                )
                            } else {
                                CompactHomeContent(
                                    route = route,
                                    devicesViewModel = devicesViewModel,
                                    appVersionName = appVersionName,
                                    onOpenDevice = { route = AppRoute.Explorer(it) },
                                    onOpenLocalFiles = {
                                        route = AppRoute.Explorer(devicesViewModel.thisDeviceTarget())
                                    },
                                    onGenerateQr = {
                                        onStartShareServer()
                                        route = AppRoute.GenerateQr
                                    },
                                    onScanQr = onScanQr,
                                    onOpenSettings = { route = AppRoute.Settings },
                                    onNavigateHome = onNavigateHome,
                                    onExitApp = exitFileApex,
                                    backgroundPersistence = backgroundPersistence,
                                    onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
                                    onOpenBackgroundPersistenceSettings = onOpenBackgroundPersistenceSettings,
                                    onOpenUnusedAppRestrictionsSettings = onOpenUnusedAppRestrictionsSettings,
                                    onOpenAppBatteryUsageSettings = onOpenAppBatteryUsageSettings,
                                    exactAlarmWarningActive = exactAlarmWarningActive,
                                    onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                                    onOpenAppDetailsSettings = onOpenAppDetailsSettings
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(hasStoragePermission) {
        if (!hasStoragePermission) {
            onPermissionRecheck()
        } else if (!usesDesktopFileSelection()) {
            onStartShareServer()
            PresenceForegroundRefresh.onAppForegrounded()
        }
    }

    val offer = pendingUpdate
    if (showUpdateSheet && offer != null) {
        UpdateAvailableSheet(offer = offer)
    }
}

private fun compactHomeTab(route: AppRoute): HomeTab = when (route) {
    AppRoute.Devices -> HomeTab.Devices
    AppRoute.Settings -> HomeTab.Settings
    is AppRoute.Explorer -> if (route.target is BrowseTarget.Local) HomeTab.Files else HomeTab.Devices
    else -> HomeTab.Devices
}

@Composable
private fun CompactHomeContent(
    route: AppRoute,
    devicesViewModel: DevicesViewModel,
    appVersionName: String,
    onOpenDevice: (BrowseTarget) -> Unit,
    onOpenLocalFiles: () -> Unit,
    onGenerateQr: () -> Unit,
    onScanQr: () -> Unit,
    onOpenSettings: () -> Unit,
    onNavigateHome: () -> Unit,
    onExitApp: () -> Unit,
    backgroundPersistence: BackgroundPersistenceUiState = BackgroundPersistenceUiState(),
    onRequestBatteryUnrestricted: () -> Unit = {},
    onOpenBackgroundPersistenceSettings: () -> Unit = {},
    onOpenUnusedAppRestrictionsSettings: () -> Unit = {},
    onOpenAppBatteryUsageSettings: () -> Unit = {},
    exactAlarmWarningActive: Boolean = false,
    onOpenExactAlarmSettings: () -> Unit = {},
    onOpenAppDetailsSettings: () -> Unit = {}
) {
    var confirmExit by remember { mutableStateOf(false) }
    val selectedTab = compactHomeTab(route)
    val onMainHomeScreen = route is AppRoute.Devices
    val devicesViewMode by FileApexServices.settings.devicesViewMode.collectAsState()
    val explorerViewMode by FileApexServices.settings.explorerViewMode.collectAsState()
    val showDevicesViewToggle = route is AppRoute.Devices
    val showExplorerViewToggle = route is AppRoute.Explorer || selectedTab == HomeTab.Files
    CompactPrimaryShell(
        selectedTab = selectedTab,
        onMainHomeScreen = onMainHomeScreen,
        showExitPower = selectedTab == HomeTab.Devices,
        onDevices = onNavigateHome,
        onFiles = onOpenLocalFiles,
        onSettings = onOpenSettings,
        onExitApp = { confirmExit = true },
        tealStripActions = {
            when {
                showDevicesViewToggle -> {
                    ExplorerViewModeToggle(
                        viewMode = devicesViewMode,
                        onToggle = {
                            FileApexServices.settings.setDevicesViewMode(devicesViewMode.toggled())
                        }
                    )
                }
                showExplorerViewToggle -> {
                    ExplorerViewModeToggle(
                        viewMode = explorerViewMode,
                        onToggle = {
                            FileApexServices.settings.setExplorerViewMode(explorerViewMode.toggled())
                        }
                    )
                }
            }
        }
    ) {
        when (val current = route) {
            AppRoute.Devices -> DevicesScreen(
                onOpenDevice = onOpenDevice,
                onOpenLocalFiles = onOpenLocalFiles,
                onGenerateQr = onGenerateQr,
                onScanQr = onScanQr,
                onOpenSettings = onOpenSettings,
                onExitApp = onExitApp,
                viewModel = devicesViewModel,
                embeddedInCompactShell = true
            )
            AppRoute.Settings -> SettingsScreen(
                appVersionName = appVersionName,
                onBack = onNavigateHome,
                showRootBackNavigation = false,
                layoutMode = SettingsScreenLayoutMode.CompactShell,
                backgroundPersistence = backgroundPersistence,
                onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
                onOpenBackgroundPersistenceSettings = onOpenBackgroundPersistenceSettings,
                onOpenUnusedAppRestrictionsSettings = onOpenUnusedAppRestrictionsSettings,
                onOpenAppBatteryUsageSettings = onOpenAppBatteryUsageSettings,
                exactAlarmWarningActive = exactAlarmWarningActive,
                onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                onOpenAppDetailsSettings = onOpenAppDetailsSettings
            )
            is AppRoute.Explorer -> FileExplorerScreen(
                target = current.target,
                embeddedInCompactShell = true,
                onBack = {
                    DeviceSessionManager.clearSession(current.target.deviceId)
                    onNavigateHome()
                }
            )
            else -> DevicesScreen(
                onOpenDevice = onOpenDevice,
                onOpenLocalFiles = onOpenLocalFiles,
                onGenerateQr = onGenerateQr,
                onScanQr = onScanQr,
                onOpenSettings = onOpenSettings,
                onExitApp = onExitApp,
                viewModel = devicesViewModel,
                embeddedInCompactShell = true
            )
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Exit FileApex?") },
            text = { Text("Stop sharing and close the app.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmExit = false
                        onExitApp()
                    }
                ) { Text("Exit") }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text("Cancel") }
            }
        )
    }
}
