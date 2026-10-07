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
import androidx.compose.material3.Button
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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.domain.pairing.PairingPayload
import com.fileapex.domain.share.IncomingSharePayload
import androidx.compose.ui.graphics.Brush
import com.fileapex.data.settings.backgroundBrush
import com.fileapex.data.settings.DesktopLayoutMode
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.traits
import com.fileapex.ui.theme.JadedSteelWash
import com.fileapex.ui.theme.KineticStyleLook
import com.fileapex.ui.theme.ProvideJadedHaze
import com.fileapex.ui.theme.rememberJadedHazeState

import com.fileapex.data.settings.DesktopUiStyle

import com.fileapex.di.FileApexServices
import com.fileapex.domain.presence.PresenceForegroundRefresh
import com.fileapex.navigation.AppRoute
import com.fileapex.platform.BackgroundPersistenceUiState
import com.fileapex.platform.FileApexBackHandler
import com.fileapex.platform.OnboardingPermissionStep
import com.fileapex.platform.supportsWindowsFluentDesign
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.ExplorerSplitSession
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.presentation.ExplorerViewModel
import com.fileapex.presentation.DevicesViewModel
import com.fileapex.session.DeviceSessionManager
import com.fileapex.update.AppUpdateCoordinator
import com.fileapex.ui.DevicesScreen
import com.fileapex.ui.dialogs.ClipboardOptInDialog
import com.fileapex.ui.FileExplorerScreen
import com.fileapex.ui.GenerateQrScreen
import com.fileapex.ui.JoinDeviceScreen
import com.fileapex.ui.ExplorerViewModeToggle
import com.fileapex.ui.HomeTab
import com.fileapex.ui.KineticDropFxLayer
import com.fileapex.ui.KineticSphereWallpaperBackground
import com.fileapex.ui.SettingsScreen
import com.fileapex.ui.SettingsScreenLayoutMode
import com.fileapex.ui.QueuedFilesButton
import com.fileapex.ui.ShareSendScreen
import com.fileapex.ui.TransferQueueScreen
import com.fileapex.ui.NotesScreen
import com.fileapex.presentation.TransferQueueViewModel
import com.fileapex.ui.OnboardingScreen
import com.fileapex.ui.UpdateAvailableSheet
import com.fileapex.ui.adaptive.AdaptiveWideHome
import com.fileapex.ui.adaptive.CompactPrimaryShell
import com.fileapex.ui.adaptive.widthSizeClassFor
import com.fileapex.ui.adaptive.isWide
import com.fileapex.ui.theme.FileApexTheme
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.AppLocale
import com.fileapex.i18n.ProvideAppLocale
import com.fileapex.i18n.applyStoredAppLanguage
import com.fileapex.i18n.defaultLanguageIfNoPrompt
import com.fileapex.i18n.detectedPromptLocale
import com.fileapex.i18n.needsLanguagePrompt
import com.fileapex.i18n.persistAppLanguage
import com.fileapex.i18n.stringRes
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun App(
    hasStoragePermission: Boolean,
    onboardingSteps: List<OnboardingPermissionStep> = emptyList(),
    onboardingComplete: Boolean = hasStoragePermission,
    deniedOnboardingStepIds: Set<String> = emptySet(),
    onGrantOnboardingStep: (String) -> Unit = {},
    onSkipOnboardingStep: (String) -> Unit = {},
    onContinueToApp: () -> Unit = {},
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
    onBeforeAllowOverCellularEnabled: (onProceed: () -> Unit) -> Unit = { it() },
    onStartShareServer: () -> Unit,
    onStopShareServer: () -> Unit,
    onExitApp: () -> Unit,
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
    onUpdateSheetRequestConsumed: () -> Unit = {},
    pendingOpenNoteId: String? = null,
    onOpenNoteRequestConsumed: () -> Unit = {},
    pendingOpenBulletinBoard: Boolean = false,
    onOpenBulletinBoardConsumed: () -> Unit = {},
    pendingOpenDeviceId: String? = null,
    onOpenDeviceRequestConsumed: () -> Unit = {},
    pendingClipboardOptInSender: String? = null,
    onClipboardOptInConsumed: () -> Unit = {}
) {
    var route by remember { mutableStateOf<AppRoute>(AppRoute.Devices) }
    val devicesViewModel: DevicesViewModel = viewModel { DevicesViewModel() }
    val transferQueueViewModel: TransferQueueViewModel = viewModel { TransferQueueViewModel() }
    val setupComplete = onboardingComplete

    // Wide-layout detail state (list-detail). Survives compact/wide transitions.
    var wideSelectedTarget by remember { mutableStateOf<BrowseTarget?>(null) }
    var wideHomeTab by remember { mutableStateOf(HomeTab.Devices) }
    var tabWhileWide by remember { mutableStateOf(HomeTab.Devices) }
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
        val staged = payload.files.isNotEmpty() &&
            payload.files.all { it.absolutePath.isNotBlank() && it.sizeBytes > 0L }
        if (staged) onIncomingShareConsumed()
    }

    LaunchedEffect(requestShowUpdateSheet) {
        if (requestShowUpdateSheet) {
            AppUpdateCoordinator.requestShowUpdateSheet()
            onUpdateSheetRequestConsumed()
        }
    }

    LaunchedEffect(pendingOpenNoteId) {
        val noteId = pendingOpenNoteId?.trim().orEmpty()
        if (noteId.isEmpty()) return@LaunchedEffect
        route = AppRoute.Notes
    }

    LaunchedEffect(pendingOpenBulletinBoard, setupComplete) {
        if (!pendingOpenBulletinBoard || !setupComplete) return@LaunchedEffect
        route = AppRoute.Notes
        onOpenBulletinBoardConsumed()
    }

    LaunchedEffect(pendingOpenDeviceId, setupComplete) {
        val deviceId = pendingOpenDeviceId?.trim().orEmpty()
        if (deviceId.isEmpty() || !setupComplete) return@LaunchedEffect
        devicesViewModel.openDeviceOrExplain(deviceId) { target ->
            wideSelectedTarget = target
            wideHomeTab = HomeTab.Devices
            route = AppRoute.Explorer(target)
        }
        onOpenDeviceRequestConsumed()
    }

    val pendingUpdate by AppUpdateCoordinator.pendingUpdate.collectAsState()
    val showUpdateSheet by AppUpdateCoordinator.showUpdateSheet.collectAsState()
    val explorerViewMode by FileApexServices.settings.explorerViewMode.collectAsState()
    val devicesViewMode by FileApexServices.settings.devicesViewMode.collectAsState()
    val pendingCellularSend by com.fileapex.cloud.drive.DriveRelayCoordinator.pendingSendPrompt.collectAsState()
    val pendingCellularReceive by com.fileapex.cloud.drive.DriveRelayCoordinator.pendingReceivePrompt.collectAsState()
    val driveCancelChoice by com.fileapex.domain.transfer.TransferActivityGuard.driveCancelChoicePending.collectAsState()

    val onNavigateHome: () -> Unit = {
        route = AppRoute.Devices
        wideHomeTab = HomeTab.Devices
        wideSelectedTarget = null
        tabWhileWide = HomeTab.Devices
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
            route !is AppRoute.TransferQueue &&
            setupComplete
    ) {
        onNavigateHome()
    }

    val desktopUiStyleFlow = remember {


        if (supportsWindowsFluentDesign()) {
            FileApexServices.settings.desktopUiStyle
        } else {
            MutableStateFlow(DesktopUiStyle.Standard)
        }
    }
    val desktopUiStyle by desktopUiStyleFlow.collectAsState()
    val appTheme by FileApexServices.settings.appTheme.collectAsState()
    val themeIconStyle by FileApexServices.settings.themeIconStyle.collectAsState()
    val kineticStyle by FileApexServices.settings.kineticStyle.collectAsState()
    val windowsFluent = desktopUiStyle == DesktopUiStyle.WindowsFluent
    val kineticSphereWallpaperOn by FileApexServices.settings.kineticSphereOrbitalRingsEnabled.collectAsState()
    val kineticSpherePersistentWallpaperOn by FileApexServices.settings.kineticSpherePersistentWallpaperEnabled.collectAsState()
    val isKineticSphere = appTheme.traits.orbitalHome
    val isCustomGlass = appTheme.traits.glassChrome
    val appLocale by AppI18n.localeFlowState
    var showLanguagePrompt by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        applyStoredAppLanguage()
        if (needsLanguagePrompt()) {
            showLanguagePrompt = true
        } else {
            defaultLanguageIfNoPrompt()
        }
    }

    ProvideAppLocale(appLocale) {
    key(appLocale) {
    FileApexTheme(
        uiStyle = desktopUiStyle,
        appTheme = appTheme,
        themeIconStyle = themeIconStyle,
        kineticStyle = kineticStyle
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
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

            val onDevicesPage = if (isWide) {
                route !is AppRoute.Explorer && route !is AppRoute.Settings &&
                    wideHomeTab == HomeTab.Devices && wideSelectedTarget == null
            } else {
                route is AppRoute.Devices
            }
            // Share intake paints a spinner, then the destination list. Neither needs the
            // orbital canvas or a haze layer; building those first was delaying the sheet.
            val shareOverlay = isPreparingShare ||
                incomingShare != null ||
                sharePrepareError != null ||
                route is AppRoute.ShareSend

            val jadedHazeState = rememberJadedHazeState()
            val jadedSteel = isKineticSphere && kineticStyle == KineticStyle.JADED_STEEL
            val showKineticWallpaper = !shareOverlay &&
                isKineticSphere && kineticStyle != KineticStyle.JADED_STEEL &&
                kineticSphereWallpaperOn && (kineticSpherePersistentWallpaperOn || onDevicesPage)

            val bgBrush = when {
                showKineticWallpaper -> null
                jadedSteel -> null
                else -> appTheme.backgroundBrush()
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (bgBrush != null) {
                            Modifier.background(bgBrush)
                        } else if (jadedSteel) {
                            Modifier
                        } else if (isKineticSphere) {
                            Modifier.background(Color(0xFF02050B))
                        } else {
                            Modifier.background(
                                if (windowsFluent) MaterialTheme.colorScheme.background
                                else FileApexTeal
                            )
                        }
                    )
            ) {
                if (!shareOverlay && jadedSteel) {
                    JadedSteelWash(
                        modifier = Modifier.fillMaxSize(),
                        hazeState = jadedHazeState
                    )
                }
                if (showKineticWallpaper) {
                    KineticSphereWallpaperBackground(modifier = Modifier.fillMaxSize())
                }

                ProvideJadedHaze(if (!shareOverlay && jadedSteel) jadedHazeState else null) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    color = if (isCustomGlass || bgBrush != null) {
                        Color.Transparent
                    } else if (windowsFluent) {
                        MaterialTheme.colorScheme.background
                    } else {
                        Color.White
                    },
                tonalElevation = 0.dp
            ) {


                if (!setupComplete && onboardingSteps.isNotEmpty()) {
                    OnboardingScreen(
                        steps = onboardingSteps,
                        deniedStepIds = deniedOnboardingStepIds,
                        onGrantStep = onGrantOnboardingStep,
                        onSkipStep = onSkipOnboardingStep,
                        onContinueToApp = onContinueToApp
                    )
                } else if (incomingShare != null || route is AppRoute.ShareSend) {
                    val liveShare = incomingShare
                    val routedShare = route as? AppRoute.ShareSend
                    ShareSendScreen(
                        payload = liveShare ?: routedShare!!.payload,
                        directTargetDeviceId = if (liveShare != null) {
                            directShareDeviceId
                        } else {
                            routedShare?.directTargetDeviceId
                        },
                        stagingError = sharePrepareError,
                        onFinished = finishShareFlow
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
                            text = stringRes("preparing_shared_files"),
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
                            Text(stringRes("close"))
                        }
                    }
                } else {
                    val localFilesTarget = remember { devicesViewModel.thisDeviceTarget() }
                    viewModel(key = localFilesTarget.deviceId) { ExplorerViewModel(localFilesTarget) }
                    LaunchedEffect(Unit) {
                        if (!usesDesktopFileSelection()) {
                            onStartShareServer()
                        }
                    }

                    // Overlay routes stay full-screen on every size class.
                    when (val overlay = route) {
                        AppRoute.GenerateQr -> GenerateQrScreen(onBack = onNavigateHome)
                        AppRoute.Join -> JoinDeviceScreen(
                            onBack = onNavigateHome,
                            viewModel = devicesViewModel
                        )
                        is AppRoute.ShareSend -> Unit
                        AppRoute.TransferQueue -> TransferQueueScreen(
                            onBack = onNavigateHome,
                            viewModel = transferQueueViewModel
                        )
                        AppRoute.Notes -> NotesScreen(
                            onBack = onNavigateHome,
                            focusNoteId = pendingOpenNoteId,
                            onFocusNoteConsumed = onOpenNoteRequestConsumed
                        )
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

                            SideEffect {
                                if (isWide) tabWhileWide = wideHomeTab
                            }
                            val compactRoute = if (!isWide && previouslyWide) {
                                when (tabWhileWide) {
                                    HomeTab.Settings -> AppRoute.Settings
                                    HomeTab.Files -> AppRoute.Explorer(
                                        (wideSelectedTarget as? BrowseTarget.Local)
                                            ?: devicesViewModel.thisDeviceTarget()
                                    )
                                    HomeTab.Devices -> wideSelectedTarget?.let { AppRoute.Explorer(it) }
                                        ?: AppRoute.Devices
                                }
                            } else {
                                route
                            }
                            val routeTarget = (route as? AppRoute.Explorer)?.target
                            val wideTab = when {
                                route is AppRoute.Settings -> HomeTab.Settings
                                routeTarget is BrowseTarget.Local -> HomeTab.Files
                                routeTarget != null -> HomeTab.Devices
                                else -> wideHomeTab
                            }
                            val wideTarget = when {
                                routeTarget != null -> routeTarget
                                wideTab != HomeTab.Files && wideSelectedTarget is BrowseTarget.Local -> null
                                else -> wideSelectedTarget
                            }

                            // Fold / unfold synchronization with the selected detail target.
                            // previouslyWide is updated before route writes so a restart of this
                            // effect cannot treat the new route as another collapse.
                            val splitSession = viewModel { ExplorerSplitSession() }

                            LaunchedEffect(isWide, wideSelectedTarget, route) {
                                val wasWide = previouslyWide
                                previouslyWide = isWide
                                when {
                                    !isWide && wasWide -> {
                                        val next = when (tabWhileWide) {
                                            HomeTab.Settings -> AppRoute.Settings
                                            HomeTab.Files -> AppRoute.Explorer(
                                                (wideSelectedTarget as? BrowseTarget.Local)
                                                    ?: devicesViewModel.thisDeviceTarget()
                                            )
                                            HomeTab.Devices -> wideSelectedTarget?.let { AppRoute.Explorer(it) }
                                                ?: AppRoute.Devices
                                        }
                                        if (route != next) route = next
                                    }
                                    isWide && route is AppRoute.Explorer -> {
                                        val explorerRoute = route as AppRoute.Explorer
                                        wideSelectedTarget = explorerRoute.target
                                        wideHomeTab = if (explorerRoute.target is BrowseTarget.Local) {
                                            HomeTab.Files
                                        } else {
                                            HomeTab.Devices
                                        }
                                        route = AppRoute.Devices
                                    }
                                    isWide && route is AppRoute.Settings -> {
                                        wideHomeTab = HomeTab.Settings
                                        route = AppRoute.Devices
                                    }
                                    isWide && wideHomeTab != HomeTab.Files &&
                                        wideSelectedTarget is BrowseTarget.Local -> {
                                        wideSelectedTarget = null
                                    }
                                }
                            }

                            if (isWide) {
                                AdaptiveWideHome(
                                    selectedTab = wideTab,
                                    onSelectTab = { wideHomeTab = it },
                                    selectedTarget = wideTarget,
                                    selectedDeviceId = wideTarget?.deviceId,
                                    onSelectDevice = { target ->
                                        wideSelectedTarget = target
                                        wideHomeTab = HomeTab.Devices
                                    },
                                    onOpenLocalFiles = {
                                        if (!hasStoragePermission) {
                                             onRequestStoragePermission()
                                        } else {
                                             wideSelectedTarget = devicesViewModel.thisDeviceTarget()
                                             wideHomeTab = HomeTab.Files
                                        }
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
                                            explorerViewMode.cycled()
                                        )
                                    },
                                    onGenerateQr = {
                                        onStartShareServer()
                                        route = AppRoute.GenerateQr
                                    },
                                    onJoinDevice = {
                                        onStartShareServer()
                                        route = AppRoute.Join
                                    },
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
                                    splitSession = splitSession,
                                    backgroundPersistence = backgroundPersistence,
                                    onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
                                    onOpenBackgroundPersistenceSettings = onOpenBackgroundPersistenceSettings,
                                    onOpenUnusedAppRestrictionsSettings = onOpenUnusedAppRestrictionsSettings,
                                    onOpenAppBatteryUsageSettings = onOpenAppBatteryUsageSettings,
                                    exactAlarmWarningActive = exactAlarmWarningActive,
                                    onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                                    onOpenAppDetailsSettings = onOpenAppDetailsSettings,
                                    onBeforeAllowOverCellularEnabled = onBeforeAllowOverCellularEnabled,
                                    onOpenTransferQueue = { route = AppRoute.TransferQueue },
                                    onOpenNotes = { route = AppRoute.Notes },
                                    onboardingSteps = onboardingSteps,
                                    deniedOnboardingStepIds = deniedOnboardingStepIds,
                                    onGrantOnboardingStep = onGrantOnboardingStep
                                )
                            } else {
                                CompactHomeContent(
                                    route = compactRoute,
                                    devicesViewModel = devicesViewModel,
                                    splitSession = splitSession,
                                    appVersionName = appVersionName,
                                    onOpenDevice = { route = AppRoute.Explorer(it) },
                                    onOpenLocalFiles = {
                                        if (!hasStoragePermission) {
                                            onRequestStoragePermission()
                                        } else {
                                            route = AppRoute.Explorer(devicesViewModel.thisDeviceTarget())
                                        }
                                    },
                                    onGenerateQr = {
                                        onStartShareServer()
                                        route = AppRoute.GenerateQr
                                    },
                                    onJoinDevice = {
                                        onStartShareServer()
                                        route = AppRoute.Join
                                    },
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
                                    onOpenAppDetailsSettings = onOpenAppDetailsSettings,
                                    onBeforeAllowOverCellularEnabled = onBeforeAllowOverCellularEnabled,
                                    onOpenTransferQueue = { route = AppRoute.TransferQueue },
                                    onOpenNotes = { route = AppRoute.Notes },
                                    onboardingSteps = onboardingSteps,
                                    deniedOnboardingStepIds = deniedOnboardingStepIds,
                                    onGrantOnboardingStep = onGrantOnboardingStep
                                )
                            }
                            KineticDropFxLayer()
                        }
                    }
                }
            }
        }
        }
    }
    }

    LaunchedEffect(onboardingComplete) {
        if (!onboardingComplete) {
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
    if (pendingCellularSend) {
        AlertDialog(
            onDismissRequest = { com.fileapex.cloud.drive.DriveRelayCoordinator.dismissSendPrompt() },
            title = { Text(stringRes("send_over_cellular")) },
            text = {
                Text(
                    if (com.fileapex.cloud.currentPlatformLabel() == "Android") {
                        stringRes("send_cellular_android")
                    } else {
                        stringRes("send_cellular_desktop")
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { com.fileapex.cloud.drive.DriveRelayCoordinator.acknowledgeSendPrompt() }
                ) { Text(stringRes("send_via_drive")) }
            },
            dismissButton = {
                TextButton(
                    onClick = { com.fileapex.cloud.drive.DriveRelayCoordinator.dismissSendPrompt() }
                ) { Text(stringRes("not_now")) }
            }
        )
    }
    if (driveCancelChoice) {
        AlertDialog(
            onDismissRequest = {
                com.fileapex.domain.transfer.TransferActivityGuard.resolveDriveCancelChoice(null)
            },
            title = { Text(stringRes("cancel_drive_title")) },
            text = { Text(stringRes("cancel_drive_body")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        com.fileapex.domain.transfer.TransferActivityGuard.resolveDriveCancelChoice(false)
                    }
                ) { Text(stringRes("cancel_try_later")) }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    TextButton(
                        onClick = {
                            com.fileapex.domain.transfer.TransferActivityGuard.resolveDriveCancelChoice(true)
                        }
                    ) { Text(stringRes("cancel_remove_from_drive")) }
                    TextButton(
                        onClick = {
                            com.fileapex.domain.transfer.TransferActivityGuard.resolveDriveCancelChoice(null)
                        }
                    ) { Text(stringRes("cancel_keep_going")) }
                }
            }
        )
    }
    if (pendingCellularReceive) {
        AlertDialog(
            onDismissRequest = { com.fileapex.cloud.drive.DriveRelayCoordinator.dismissReceivePrompt() },
            title = { Text(stringRes("receive_over_cellular")) },
            text = {
                Text(stringRes("receive_cellular_body"))
            },
            confirmButton = {
                TextButton(
                    onClick = { com.fileapex.cloud.drive.DriveRelayCoordinator.acknowledgeReceivePrompt() }
                ) { Text(stringRes("allow")) }
            },
            dismissButton = {
                TextButton(
                    onClick = { com.fileapex.cloud.drive.DriveRelayCoordinator.dismissReceivePrompt() }
                ) { Text(stringRes("not_now")) }
            }
        )
    }
    if (showLanguagePrompt) {
        val detected = detectedPromptLocale()
        val body = when (detected) {
            AppLocale.ES -> stringRes("language_prompt_body_es")
            AppLocale.ZH_HANS -> stringRes("language_prompt_body_zh")
            AppLocale.EN -> stringRes("language_prompt_body", detected.nativeName)
        }
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringRes("language_prompt_title"), softWrap = true) },
            text = { Text(body, softWrap = true) },
            confirmButton = {
                TextButton(
                    onClick = {
                        persistAppLanguage(detected)
                        showLanguagePrompt = false
                    }
                ) { Text(stringRes("language_use_detected", detected.nativeName), softWrap = true) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        persistAppLanguage(AppLocale.EN)
                        showLanguagePrompt = false
                    }
                ) { Text(stringRes("language_use_english"), softWrap = true) }
            }
        )
    }

    val optInPromptShown by FileApexServices.settings.clipboardOptInPromptShown.collectAsState()
    val clipboardSharingEnabled by FileApexServices.settings.clipboardSharingEnabled.collectAsState()

    LaunchedEffect(pendingClipboardOptInSender, optInPromptShown) {
        if (pendingClipboardOptInSender != null && optInPromptShown) {
            onClipboardOptInConsumed()
        }
    }

    if (pendingClipboardOptInSender != null && !optInPromptShown) {
        val coroutineScope = rememberCoroutineScope()
        ClipboardOptInDialog(
            senderDeviceName = pendingClipboardOptInSender,
            sharingEnabled = clipboardSharingEnabled,
            onToggleSharing = { enabled ->
                FileApexServices.settings.setClipboardSharingEnabled(enabled)
                com.fileapex.platform.ClipboardShareChrome.fire()
                if (enabled) {
                    com.fileapex.domain.clipboard.ClipboardPendingOptInStore.consume()?.let { pending ->
                        coroutineScope.launch {
                            com.fileapex.domain.clipboard.ClipboardShareCoordinator.applyInbound(
                                senderDeviceId = pending.senderDeviceId,
                                senderDeviceName = pending.senderDeviceName,
                                senderPublicKey = pending.senderPublicKey,
                                ciphertext = pending.ciphertext,
                                capturedAtEpochMs = pending.capturedAtEpochMs
                            )
                        }
                    }
                }
            },
            onDone = {
                FileApexServices.settings.setClipboardOptInPromptShown(true)
                if (clipboardSharingEnabled) {
                    com.fileapex.domain.clipboard.ClipboardPendingOptInStore.consume()?.let { pending ->
                        coroutineScope.launch {
                            com.fileapex.domain.clipboard.ClipboardShareCoordinator.applyInbound(
                                senderDeviceId = pending.senderDeviceId,
                                senderDeviceName = pending.senderDeviceName,
                                senderPublicKey = pending.senderPublicKey,
                                ciphertext = pending.ciphertext,
                                capturedAtEpochMs = pending.capturedAtEpochMs
                            )
                        }
                    }
                } else {
                    com.fileapex.domain.clipboard.ClipboardPendingOptInStore.clear()
                }
                onClipboardOptInConsumed()
            }
        )
    }
    }
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
    splitSession: ExplorerSplitSession,
    appVersionName: String,
    onOpenDevice: (BrowseTarget) -> Unit,
    onOpenLocalFiles: () -> Unit,
    onGenerateQr: () -> Unit,
    onJoinDevice: () -> Unit,
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
    onOpenAppDetailsSettings: () -> Unit = {},
    onBeforeAllowOverCellularEnabled: (onProceed: () -> Unit) -> Unit = { it() },
    onOpenTransferQueue: () -> Unit = {},
    onOpenNotes: () -> Unit = {},
    onboardingSteps: List<OnboardingPermissionStep> = emptyList(),
    deniedOnboardingStepIds: Set<String> = emptySet(),
    onGrantOnboardingStep: (String) -> Unit = {}
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
            QueuedFilesButton(onClick = onOpenTransferQueue)
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
                        includeSplit = true,
                        onToggle = {
                            FileApexServices.settings.setExplorerViewMode(explorerViewMode.cycled())
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
                onJoinDevice = onJoinDevice,
                onOpenSettings = onOpenSettings,
                onExitApp = onExitApp,
                onOpenNotes = onOpenNotes,
                onOpenTransferQueue = onOpenTransferQueue,
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
                onOpenAppDetailsSettings = onOpenAppDetailsSettings,
                onBeforeAllowOverCellularEnabled = onBeforeAllowOverCellularEnabled,
                onOpenTransferQueue = onOpenTransferQueue,
                onboardingSteps = onboardingSteps,
                deniedOnboardingStepIds = deniedOnboardingStepIds,
                onGrantOnboardingStep = onGrantOnboardingStep,
                onExitApp = onExitApp
            )
            is AppRoute.Explorer -> {
                val secondaryTarget by splitSession.secondaryTarget.collectAsState()
                val deviceRows by devicesViewModel.deviceRows.collectAsState()
                FileExplorerScreen(
                    target = current.target,
                    embeddedInCompactShell = true,
                    onOpenTransferQueue = onOpenTransferQueue,
                    onExitApp = onExitApp,
                    secondaryTarget = if (current.target is BrowseTarget.Local) secondaryTarget else null,
                    readyDevices = deviceRows.filter { it.online },
                    onSelectSecondaryLocal = splitSession::selectLocal,
                    onSelectSecondaryDevice = { deviceId ->
                        devicesViewModel.openDeviceOrExplain(deviceId) { opened ->
                            splitSession.select(opened)
                        }
                    },
                    onBack = {
                        DeviceSessionManager.clearSession(current.target.deviceId)
                        onNavigateHome()
                    }
                )
            }
            else -> DevicesScreen(
                onOpenDevice = onOpenDevice,
                onOpenLocalFiles = onOpenLocalFiles,
                onGenerateQr = onGenerateQr,
                onJoinDevice = onJoinDevice,
                onOpenSettings = onOpenSettings,
                onExitApp = onExitApp,
                onOpenNotes = onOpenNotes,
                onOpenTransferQueue = onOpenTransferQueue,
                viewModel = devicesViewModel,
                embeddedInCompactShell = true
            )
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringRes("exit_fileapex_q")) },
            text = { Text(stringRes("stop_sharing_close")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmExit = false
                        onExitApp()
                    }
                ) { Text(stringRes("exit")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text(stringRes("cancel")) }
            }
        )
    }
}
