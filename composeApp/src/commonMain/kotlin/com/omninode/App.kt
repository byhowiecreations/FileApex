package com.omninode

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omninode.domain.pairing.PairingPayload
import com.omninode.domain.share.IncomingSharePayload
import com.omninode.navigation.AppRoute
import com.omninode.platform.OmniBackHandler
import com.omninode.presentation.BrowseTarget
import com.omninode.presentation.DevicesViewModel
import com.omninode.session.DeviceSessionManager
import com.omninode.ui.DevicesScreen
import com.omninode.ui.FileExplorerScreen
import com.omninode.ui.GenerateQrScreen
import com.omninode.ui.HomeTab
import com.omninode.ui.SettingsScreen
import com.omninode.ui.ShareSendScreen
import com.omninode.ui.StoragePermissionScreen
import com.omninode.ui.adaptive.AdaptiveWideHome
import com.omninode.ui.adaptive.widthSizeClassFor
import com.omninode.ui.adaptive.isWide
import com.omninode.ui.theme.OmniNodeTheme
import com.omninode.ui.theme.OmniTeal

@Composable
fun App(
    hasStoragePermission: Boolean,
    hasUnrestrictedBattery: Boolean = true,
    onRequestStoragePermission: () -> Unit,
    onOpenStorageSettings: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit = {},
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
    onPermissionRecheck: () -> Unit = {},
    incomingShare: IncomingSharePayload? = null,
    isPreparingShare: Boolean = false,
    sharePrepareError: String? = null,
    onIncomingShareConsumed: () -> Unit = {},
    onShareFlowFinished: () -> Unit = {},
    onDismissShareError: () -> Unit = {}
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

    LaunchedEffect(incomingShare, setupComplete) {
        val payload = incomingShare ?: return@LaunchedEffect
        if (!setupComplete) return@LaunchedEffect
        route = AppRoute.ShareSend(payload)
        onIncomingShareConsumed()
    }

    val onNavigateHome: () -> Unit = {
        route = AppRoute.Devices
        wideHomeTab = HomeTab.Devices
    }

    val exitOmniNode: () -> Unit = {
        onStopShareServer()
        onExitApp()
    }

    val finishShareFlow: () -> Unit = {
        route = AppRoute.Devices
        wideHomeTab = HomeTab.Devices
        onShareFlowFinished()
    }

    OmniBackHandler(
        enabled = route !is AppRoute.Devices &&
            route !is AppRoute.Explorer &&
            route !is AppRoute.ShareSend &&
            setupComplete
    ) {
        onNavigateHome()
    }

    OmniNodeTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(OmniTeal)
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
                        onRequestStoragePermission = onRequestStoragePermission,
                        onOpenStorageSettings = onOpenStorageSettings,
                        onRequestBatteryUnrestricted = onRequestBatteryUnrestricted
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
                        onStartShareServer()
                    }

                    // Overlay routes stay full-screen on every size class.
                    when (val overlay = route) {
                        AppRoute.GenerateQr -> GenerateQrScreen(onBack = onNavigateHome)
                        is AppRoute.ShareSend -> ShareSendScreen(
                            payload = overlay.payload,
                            onFinished = finishShareFlow
                        )
                        AppRoute.ScanQr -> {
                            route = AppRoute.Devices
                        }
                        else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val widthClass = widthSizeClassFor(maxWidth)
                            val isWide = widthClass.isWide

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
                                    onGenerateQr = {
                                        onStartShareServer()
                                        route = AppRoute.GenerateQr
                                    },
                                    onScanQr = onScanQr,
                                    onExitApp = exitOmniNode,
                                    onClearDetail = {
                                        wideSelectedTarget?.deviceId?.let {
                                            DeviceSessionManager.clearSession(it)
                                        }
                                        wideSelectedTarget = null
                                        wideHomeTab = HomeTab.Devices
                                    },
                                    appVersionName = appVersionName,
                                    devicesViewModel = devicesViewModel,
                                    batteryOptimizationRestricted = !hasUnrestrictedBattery,
                                    onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
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
                                    onExitApp = exitOmniNode,
                                    batteryOptimizationRestricted = !hasUnrestrictedBattery,
                                    onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
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
        } else {
            onStartShareServer()
        }
    }
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
    batteryOptimizationRestricted: Boolean = false,
    onRequestBatteryUnrestricted: () -> Unit = {},
    exactAlarmWarningActive: Boolean = false,
    onOpenExactAlarmSettings: () -> Unit = {},
    onOpenAppDetailsSettings: () -> Unit = {}
) {
    when (val current = route) {
        AppRoute.Devices -> DevicesScreen(
            onOpenDevice = onOpenDevice,
            onOpenLocalFiles = onOpenLocalFiles,
            onGenerateQr = onGenerateQr,
            onScanQr = onScanQr,
            onOpenSettings = onOpenSettings,
            onExitApp = onExitApp,
            viewModel = devicesViewModel
        )
        AppRoute.Settings -> SettingsScreen(
            appVersionName = appVersionName,
            onBack = onNavigateHome,
            batteryOptimizationRestricted = batteryOptimizationRestricted,
            onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
            exactAlarmWarningActive = exactAlarmWarningActive,
            onOpenExactAlarmSettings = onOpenExactAlarmSettings,
            onOpenAppDetailsSettings = onOpenAppDetailsSettings
        )
        is AppRoute.Explorer -> FileExplorerScreen(
            target = current.target,
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
            viewModel = devicesViewModel
        )
    }
}
