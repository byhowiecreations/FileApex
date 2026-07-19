package com.omninode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.omninode.presentation.DevicesViewModel
import com.omninode.session.DeviceSessionManager
import com.omninode.ui.DevicesScreen
import com.omninode.ui.FileExplorerScreen
import com.omninode.ui.GenerateQrScreen
import com.omninode.ui.SettingsScreen
import com.omninode.ui.ShareSendScreen
import com.omninode.ui.StoragePermissionScreen
import com.omninode.ui.theme.OmniNodeTheme
import com.omninode.ui.theme.OmniTeal

@Composable
fun App(
    hasStoragePermission: Boolean,
    hasUnrestrictedBattery: Boolean = true,
    onRequestStoragePermission: () -> Unit,
    onOpenStorageSettings: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit = {},
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
    val setupComplete = hasStoragePermission && hasUnrestrictedBattery

    LaunchedEffect(scannedPayload) {
        val payload = scannedPayload ?: return@LaunchedEffect
        devicesViewModel.pairFromQrPayload(payload)
        onScannedPayloadConsumed()
        route = AppRoute.Devices
    }

    LaunchedEffect(incomingShare, setupComplete) {
        val payload = incomingShare ?: return@LaunchedEffect
        if (!setupComplete) return@LaunchedEffect
        route = AppRoute.ShareSend(payload)
        onIncomingShareConsumed()
    }

    val onNavigateHome: () -> Unit = { route = AppRoute.Devices }

    // Power exit always tears down the share server; Home/swipe-away leaves it running.
    val exitOmniNode: () -> Unit = {
        onStopShareServer()
        onExitApp()
    }

    val finishShareFlow: () -> Unit = {
        route = AppRoute.Devices
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
        // Teal behind system bars; app content is inset so bars stay visible without flicker.
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
                    // Keep the share server listening while the app has storage access so
                    // recently paired peers can browse this device without "connection refused".
                    LaunchedEffect(Unit) {
                        onStartShareServer()
                    }

                    when (val current = route) {
                        AppRoute.Devices -> DevicesScreen(
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
                            onExitApp = exitOmniNode,
                            viewModel = devicesViewModel
                        )

                        AppRoute.GenerateQr -> GenerateQrScreen(
                            onBack = onNavigateHome
                        )

                        AppRoute.ScanQr -> {
                            route = AppRoute.Devices
                        }

                        AppRoute.Settings -> SettingsScreen(
                            appVersionName = appVersionName,
                            onBack = onNavigateHome
                        )

                        is AppRoute.Explorer -> FileExplorerScreen(
                            target = current.target,
                            onBack = {
                                DeviceSessionManager.clearSession(current.target.deviceId)
                                onNavigateHome()
                            }
                        )

                        is AppRoute.ShareSend -> ShareSendScreen(
                            payload = current.payload,
                            onFinished = finishShareFlow
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(hasStoragePermission, hasUnrestrictedBattery) {
        if (!setupComplete) {
            onPermissionRecheck()
        } else {
            onStartShareServer()
        }
    }
}
