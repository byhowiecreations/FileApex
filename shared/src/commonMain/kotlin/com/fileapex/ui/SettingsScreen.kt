package com.fileapex.ui

import androidx.compose.material3.Switch
import androidx.compose.material3.ListItem
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.data.settings.DesktopLayoutMode
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.UpdateCheckFrequency
import com.fileapex.data.settings.traits
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes
import com.fileapex.platform.BackgroundPersistenceUiState
import com.fileapex.platform.Diagnostics
import com.fileapex.platform.FileApexBackHandler
import com.fileapex.platform.OnboardingPermissionStep
import com.fileapex.platform.supportsDeviceNameSettingsPage
import com.fileapex.platform.supportsWindowsFluentDesign
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.presentation.SettingsUiState
import com.fileapex.presentation.SettingsViewModel
import com.fileapex.tailscale.TailscaleNodeRuntime
import com.fileapex.ui.dialogs.ClipboardTargetConfigDialog
import com.fileapex.ui.settings.AboutSettingsPage
import com.fileapex.ui.settings.SettingsAdvancedAndAboutRows
import com.fileapex.ui.settings.rememberAdvancedSettingsInUse
import com.fileapex.ui.settings.AutoLaunchOnRebootSettingsPage
import com.fileapex.ui.settings.BackgroundPersistenceSettingsPage
import com.fileapex.ui.settings.BackupSyncSettingsPage
import com.fileapex.ui.settings.BroadcastNotificationsSettingsPage
import com.fileapex.ui.settings.BulletinBoardHubPage
import com.fileapex.ui.settings.BulletinBoardStylesSettingsPage
import com.fileapex.ui.settings.CheckForUpdatesSettingsPage
import com.fileapex.ui.settings.ClipboardAccessibilityPage
import com.fileapex.ui.settings.ClipboardDiagnosticsSettingsPage
import com.fileapex.ui.settings.ClipboardSettingsPage
import com.fileapex.ui.settings.ClipboardShareTargetsPage
import com.fileapex.ui.settings.DesktopLayoutSettingsPage
import com.fileapex.ui.settings.DeviceDetailsSettingsPage
import com.fileapex.ui.settings.DeviceNameSettingsPage
import com.fileapex.ui.settings.DisclosureScreen
import com.fileapex.ui.settings.FileTransferNotificationsSettingsPage
import com.fileapex.ui.settings.GoogleAccountSettingsPage
import com.fileapex.ui.settings.IrohSettingsPage
import com.fileapex.ui.settings.LanguageSettingsPage
import com.fileapex.ui.settings.LocalSettingsExitAction
import com.fileapex.ui.settings.LocalSettingsHomeAction
import com.fileapex.ui.settings.NotificationsSettingsPage
import com.fileapex.ui.settings.OnboardingPermissionsSettingsPage
import com.fileapex.ui.settings.PinRequiredSettingsPage
import com.fileapex.ui.settings.RemoteFileDeletionSettingsPage
import com.fileapex.ui.settings.SettingsNavItem
import com.fileapex.ui.settings.SettingsPageShell
import com.fileapex.ui.settings.TailscaleSettingsPage
import com.fileapex.ui.settings.ThemesSettingsPage
import com.fileapex.ui.settings.WindowsDesignSettingsPage
import com.fileapex.ui.settings.backupSummary
import com.fileapex.ui.settings.clipboardShareTargetsSubtitle
import com.fileapex.ui.settings.googleAccountSubtitle
import com.fileapex.ui.settings.irohRowSubtitle
import com.fileapex.ui.settings.localizedBulletinBoardStyleName
import com.fileapex.ui.settings.localizedPinIdle
import com.fileapex.ui.settings.localizedThemeName
import com.fileapex.ui.settings.tailscaleRowSubtitle
import com.fileapex.util.TimeUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appVersionName: String,
    onBack: () -> Unit,
    /**
     * When false (wide NavigationRail layout), root Settings has no up/back affordance;
     * leave via the rail. Sub-pages still show back to Settings root. Compact stays true.
     */
    showRootBackNavigation: Boolean = true,
    layoutMode: SettingsScreenLayoutMode = SettingsScreenLayoutMode.FullScreen,
    backgroundPersistence: BackgroundPersistenceUiState = BackgroundPersistenceUiState(),
    onRequestBatteryUnrestricted: () -> Unit = {},
    onOpenBackgroundPersistenceSettings: () -> Unit = {},
    onOpenUnusedAppRestrictionsSettings: () -> Unit = {},
    onOpenAppBatteryUsageSettings: () -> Unit = {},
    exactAlarmWarningActive: Boolean = false,
    onOpenExactAlarmSettings: () -> Unit = {},
    onOpenAppDetailsSettings: () -> Unit = {},
    /** Android: gate cellular opt-in behind READ_PHONE_STATE; other platforms invoke [onProceed] immediately. */
    onBeforeAllowOverCellularEnabled: (onProceed: () -> Unit) -> Unit = { it() },
    onOpenTransferQueue: () -> Unit = {},
    onboardingSteps: List<OnboardingPermissionStep> = emptyList(),
    deniedOnboardingStepIds: Set<String> = emptySet(),
    onGrantOnboardingStep: (String) -> Unit = {},
    onExitApp: (() -> Unit)? = null,
    deepLink: SettingsDeepLink? = null,
    onDeepLinkConsumed: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel { SettingsViewModel() }
) {
    val state by viewModel.uiState.collectAsState()
    val updateStatus by viewModel.updateStatusMessage.collectAsState()
    val googleLinkStatus by viewModel.googleLinkStatus.collectAsState()
    val navigation = viewModel { SettingsNavigationState() }
    var page by navigation.page
    LaunchedEffect(deepLink) {
        val target = deepLink ?: return@LaunchedEffect
        page = settingsPageFor(target)
        onDeepLinkConsumed()
    }
    LaunchedEffect(page) {
        if (page == SettingsPage.Themes) FileApexServices.settings.setOtherThemesHintShown(true)
    }

    val currentTheme = LocalAppTheme.current
    val isFreestyleCompact = currentTheme.traits.canvasHome && layoutMode == SettingsScreenLayoutMode.CompactShell
    val allowRootBack = showRootBackNavigation || isFreestyleCompact

    val goUp: () -> Unit = { page = page.parent ?: SettingsPage.Root }
    val leavePage: () -> Unit = {
        if (page == SettingsPage.Root) {
            if (allowRootBack) onBack()
        } else {
            goUp()
        }
    }

    FileApexBackHandler(
        enabled = page != SettingsPage.Root || allowRootBack,
        onBack = leavePage
    )

    val handleGoHome: () -> Unit = {
        page = SettingsPage.Root
        onBack()
    }

    CompositionLocalProvider(
        LocalSettingsHomeAction provides handleGoHome,
        LocalSettingsExitAction provides onExitApp
    ) {
        when (page) {
        SettingsPage.Root -> SettingsRootPage(
            appVersionName = appVersionName,
            state = state,
            onBack = onBack,
            showBackNavigation = showRootBackNavigation,
            layoutMode = layoutMode,
            onOpenCheckForUpdates = { page = SettingsPage.CheckForUpdates },
            onOpenPinRequired = { page = SettingsPage.PinRequired },
            onOpenBackgroundPersistence = { page = SettingsPage.BackgroundPersistence },
            onOpenAutoLaunchOnReboot = { page = SettingsPage.AutoLaunchOnReboot },
            onOpenDeviceName = { page = SettingsPage.DeviceName },
            onOpenLanguage = { page = SettingsPage.Language },
            onOpenNotifications = { page = SettingsPage.Notifications },
            onOpenThemes = { page = SettingsPage.Themes },
            onOpenBulletinBoard = { page = SettingsPage.BulletinBoard },
            onOpenBackupSync = { page = SettingsPage.BackupSync },
            onOpenClipboard = { page = SettingsPage.Clipboard },
            onOpenDeviceDetails = { page = SettingsPage.DeviceDetails },
            onOpenGoogleAccount = { page = SettingsPage.GoogleAccount },
            onOpenDesktopLayout = { page = SettingsPage.DesktopLayout },
            onOpenWindowsDesign = { page = SettingsPage.WindowsDesign },
            onOpenLeaveClusterWipe = { page = SettingsPage.LeaveClusterWipe },
            onOpenTailscale = { page = SettingsPage.Tailscale },
            onOpenIroh = { page = SettingsPage.Iroh },
            onOpenAbout = { page = SettingsPage.About },
            onToggleGeneralGroup = viewModel::toggleGeneralGroup,
            onShowAdvancedChange = viewModel::setShowAdvancedSettings,
            onToggleSystemPerformanceGroup = viewModel::toggleSystemPerformanceGroup,
            onToggleAppearanceBehaviorGroup = viewModel::toggleAppearanceBehaviorGroup,
            onToggleSecurityAccountGroup = viewModel::toggleSecurityAccountGroup,
            onToggleRemoteAccessGroup = viewModel::toggleRemoteAccessGroup,
            onVersionNumberEasterEgg = viewModel::onVersionNumberEasterEgg,
            backgroundPersistence = backgroundPersistence,
            exactAlarmWarningActive = exactAlarmWarningActive,
            onOpenTransferQueue = onOpenTransferQueue,
            onboardingSteps = onboardingSteps,
            onOpenOnboardingPermissions = { page = SettingsPage.OnboardingPermissions }
        )
        SettingsPage.OnboardingPermissions -> OnboardingPermissionsSettingsPage(
            onboardingSteps = onboardingSteps,
            deniedOnboardingStepIds = deniedOnboardingStepIds,
            onGrantOnboardingStep = onGrantOnboardingStep,
            layoutMode = layoutMode,
            onBack = goUp
        )
        SettingsPage.Notifications -> NotificationsSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = goUp,
            onToggleFileTransferNotifications = viewModel::setFileTransferNotifications,
            onToggleNotesNotifications = viewModel::setNotesNotifications,
            onToggleDriveRelayNotifications = viewModel::setDriveRelayNotifications,
            onToggleLiveTransferCapsule = viewModel::setLiveTransferCapsule,
            onOpenBroadcastNotifications = { page = SettingsPage.BroadcastNotifications }
        )
        SettingsPage.BroadcastNotifications -> BroadcastNotificationsSettingsPage(
            layoutMode = layoutMode,
            onBack = goUp,
            onOpenAppDetailsSettings = onOpenAppDetailsSettings
        )
        SettingsPage.CheckForUpdates -> {
            if (com.fileapex.di.FileApexServices.managedByStore) {
                page = SettingsPage.Root
            } else {
                val isAndroid = currentPlatformLabel() == "Android"
                if (isAndroid && !state.installPackagesDisclosureAcknowledged) {
                    DisclosureScreen(
                        title = stringRes("install_packages_disclosure_title"),
                        icon = Icons.Filled.SystemUpdate,
                        whatText = stringRes("install_packages_disclosure_what"),
                        whyText = stringRes("install_packages_disclosure_why"),
                        layoutMode = layoutMode,
                        onBack = goUp,
                        onAgree = { viewModel.setInstallPackagesDisclosureAcknowledged(true) }
                    )
                } else {
                    CheckForUpdatesSettingsPage(
                        state = state,
                        updateStatus = updateStatus,
                        layoutMode = layoutMode,
                        onBack = goUp,
                        onToggle = viewModel::setCheckForUpdates,
                        onUnitSelected = viewModel::setCheckForUpdatesUnit,
                        onAmountTextChange = viewModel::setCheckForUpdatesAmountText,
                        onWeekAmountSelected = viewModel::setCheckForUpdatesWeekAmount
                    )
                }
            }
        }
        SettingsPage.PinRequired -> PinRequiredSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = goUp,
            onToggle = viewModel::setPinRequired,
            onPinChange = viewModel::setDevicePin,
            onIdleTimeoutSelected = viewModel::setPinIdleTimeout
        )
        SettingsPage.BackgroundPersistence -> BackgroundPersistenceSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = goUp,
            onEnableServiceWatchdog = viewModel::setEnableServiceWatchdog,
            backgroundPersistence = backgroundPersistence,
            onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
            onOpenBackgroundPersistenceSettings = onOpenBackgroundPersistenceSettings,
            onOpenUnusedAppRestrictionsSettings = onOpenUnusedAppRestrictionsSettings,
            onOpenAppBatteryUsageSettings = onOpenAppBatteryUsageSettings,
            exactAlarmWarningActive = exactAlarmWarningActive,
            onOpenExactAlarmSettings = onOpenExactAlarmSettings,
            onOpenAppDetailsSettings = onOpenAppDetailsSettings
        )
        SettingsPage.AutoLaunchOnReboot -> AutoLaunchOnRebootSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = goUp,
            onToggle = viewModel::setAutoLaunchOnReboot
        )
        SettingsPage.FileTransferNotifications -> FileTransferNotificationsSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = goUp,
            onToggle = viewModel::setFileTransferNotifications
        )

        SettingsPage.Themes -> ThemesSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = goUp,
            onSelectTheme = viewModel::setAppTheme,
            onSelectThemeIconStyle = { viewModel.setThemeIconStyle(state.appTheme, it) },
            onSelectKineticStyle = viewModel::setKineticStyle,
            onToggleConnectedLines = viewModel::setKineticSphereConnectedLinesEnabled,
            onToggleOrbitalRings = viewModel::setKineticSphereOrbitalRingsEnabled,
            onTogglePersistentWallpaper = viewModel::setKineticSpherePersistentWallpaperEnabled
        )

        SettingsPage.BulletinBoard -> BulletinBoardHubPage(
            state = state,
            layoutMode = layoutMode,
            onBack = goUp,
            onOpenStyles = { page = SettingsPage.BulletinBoardStyles },
            onOpenRemoteDeletion = { page = SettingsPage.RemoteFileDeletion }
        )

        SettingsPage.BackupSync -> BackupSyncSettingsPage(
            layoutMode = layoutMode,
            onBack = goUp
        )

        SettingsPage.BulletinBoardStyles -> BulletinBoardStylesSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = goUp,
            onSelectStyle = viewModel::setBulletinBoardStyle
        )

        SettingsPage.Clipboard -> {
            if (!state.clipboardDisclosureAcknowledged) {
                DisclosureScreen(
                    title = stringRes("clipboard_disclosure_title"),
                    icon = Icons.Filled.ContentPaste,
                    whatText = stringRes("clipboard_disclosure_what"),
                    whyText = stringRes("clipboard_disclosure_why"),
                    layoutMode = layoutMode,
                    onBack = goUp,
                    onAgree = { viewModel.setClipboardDisclosureAcknowledged(true) }
                )
            } else {
                ClipboardSettingsPage(
                    state = state,
                    layoutMode = layoutMode,
                    onBack = goUp,
                    onToggle = viewModel::setClipboardSharing,
                    onToggleViaCellular = viewModel::setClipboardViaCellular,
                    onToggleSendNotification = viewModel::setClipboardSendNotification,
                    onToggleAutoSend = viewModel::setClipboardAutoSend,
                    onOpenAccessibility = { page = SettingsPage.ClipboardAccessibility },
                    onOpenShareTargets = { page = SettingsPage.ClipboardShareTargets },
                    onOpenDiagnostics = { page = SettingsPage.ClipboardDiagnostics }
                )
            }
        }
        SettingsPage.ClipboardAccessibility -> {
            if (com.fileapex.di.FileApexServices.isPlayStoreBuild) {
                page = SettingsPage.Clipboard
            } else if (!state.accessibilityDisclosureAcknowledged) {
                DisclosureScreen(
                    title = stringRes("accessibility_disclosure_title"),
                    icon = Icons.Filled.Accessibility,
                    whatText = stringRes("accessibility_disclosure_what"),
                    whyText = stringRes("accessibility_disclosure_why"),
                    layoutMode = layoutMode,
                    onBack = goUp,
                    onAgree = { viewModel.setAccessibilityDisclosureAcknowledged(true) }
                )
            } else {
                ClipboardAccessibilityPage(
                    state = state,
                    layoutMode = layoutMode,
                    onBack = goUp,
                    onToggleAccessibility = viewModel::setClipboardAccessibility,
                    onToggleShizuku = viewModel::setClipboardShizuku,
                    onDismissRestrictedHelp = viewModel::dismissAccessibilityRestrictedHelp,
                    onOpenAppInfo = viewModel::openAccessibilityAppInfo,
                    onOpenAccessibilitySettings = viewModel::openAccessibilitySystemSettings
                )
            }
        }
        SettingsPage.ClipboardShareTargets -> ClipboardShareTargetsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = goUp,
            onShareModeChange = viewModel::setClipboardShareMode,
            onTogglePeer = viewModel::setClipboardTargetDevice
        )
        SettingsPage.ClipboardDiagnostics -> ClipboardDiagnosticsSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = goUp,
            onOpenAccessibility = viewModel::openAccessibilitySystemSettings,
            onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
            onOpenAppInfo = viewModel::openAccessibilityAppInfo
        )
        SettingsPage.DeviceDetails -> DeviceDetailsSettingsPage(
            preferences = state.deviceDetailsDisplayPreferences,
            allowOverCellular = state.deviceDetailsAllowOverCellular,
            layoutMode = layoutMode,
            onBack = goUp,
            onBeforeAllowOverCellularEnabled = onBeforeAllowOverCellularEnabled,
            onAllowOverCellularChange = viewModel::setDeviceDetailsAllowOverCellular,
            onFieldVisibleChange = viewModel::setDeviceDetailsFieldVisible,
            onReorderFields = viewModel::reorderDeviceDetailsFields,
            onReset = viewModel::resetDeviceDetailsDisplayPreferences
        )
        SettingsPage.GoogleAccount -> GoogleAccountSettingsPage(
            state = state,
            linkStatus = googleLinkStatus,
            layoutMode = layoutMode,
            onBack = goUp,
            onDisable = viewModel::disableGoogleAccountLink,
            onIdToken = viewModel::onGoogleIdToken,
            onCellularChange = viewModel::setCellularEnabled,
            onDriveRelayChange = viewModel::setGoogleDriveRelayEnabled,
            onDriveRelayMaxMbSelected = viewModel::setDriveRelayMaxMb,
            onDriveAuthResult = viewModel::onGoogleDriveAuthResult,
            onEncryptionChange = viewModel::setDriveRelayEncryptionEnabled,
            onPurgeChange = viewModel::setDrivePurgeAfter72Hours,
            onPurgeNow = viewModel::purgeDriveRelayNow
        )
        SettingsPage.RemoteFileDeletion -> RemoteFileDeletionSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = goUp,
            onAllowRemoteFileDeletionChange = viewModel::setAllowRemoteFileDeletion
        )
        SettingsPage.DesktopLayout -> DesktopLayoutSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = goUp,
            onExpanded = { expanded ->
                viewModel.setDesktopLayoutMode(
                    if (expanded) DesktopLayoutMode.Expanded else DesktopLayoutMode.Compact
                )
            }
        )
        SettingsPage.WindowsDesign -> WindowsDesignSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = goUp,
            onFluent = { enabled ->
                viewModel.setDesktopUiStyle(
                    if (enabled) DesktopUiStyle.WindowsFluent else DesktopUiStyle.Standard
                )
            }
        )
        SettingsPage.Language -> LanguageSettingsPage(
            layoutMode = layoutMode,
            onBack = goUp
        )
        SettingsPage.DeviceName -> DeviceNameSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = goUp,
            onDraftChange = viewModel::setDeviceNameDraft,
            onSave = viewModel::saveDeviceName
        )
        SettingsPage.Tailscale -> TailscaleSettingsPage(
            layoutMode = layoutMode,
            setupExpanded = state.tailscaleSetupExpanded,
            onSetupExpandedChange = viewModel::setTailscaleSetupExpanded,
            onBack = goUp
        )
        SettingsPage.Iroh -> IrohSettingsPage(layoutMode, goUp)
        SettingsPage.LeaveClusterWipe -> LeaveClusterWipeSettingsPage(layoutMode, goUp)
        SettingsPage.About -> AboutSettingsPage(layoutMode, goUp)
    }
    }

    if (state.showClipboardConfigDialog) {
        ClipboardTargetConfigDialog(
            initialMode = state.clipboardShareMode,
            initialTargetIds = state.clipboardTargetDeviceIds,
            peers = state.clipboardConfigPeers,
            onConfirm = { mode, targets ->
                viewModel.confirmClipboardConfig(mode, targets)
            },
            onDismiss = viewModel::dismissClipboardConfigDialog
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRootPage(
    appVersionName: String,
    state: SettingsUiState,
    onBack: () -> Unit,
    showBackNavigation: Boolean,
    layoutMode: SettingsScreenLayoutMode,
    onOpenCheckForUpdates: () -> Unit,
    onOpenPinRequired: () -> Unit,
    onOpenBackgroundPersistence: () -> Unit,
    onOpenAutoLaunchOnReboot: () -> Unit,
    onOpenDeviceName: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenThemes: () -> Unit,
    onOpenBulletinBoard: () -> Unit,
    onOpenBackupSync: () -> Unit = {},
    onOpenClipboard: () -> Unit,
    onOpenDeviceDetails: () -> Unit,
    onOpenGoogleAccount: () -> Unit,
    onOpenDesktopLayout: () -> Unit,
    onOpenWindowsDesign: () -> Unit,
    onOpenLeaveClusterWipe: () -> Unit = {},
    onOpenTailscale: () -> Unit = {},
    onOpenIroh: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onToggleGeneralGroup: () -> Unit,
    onShowAdvancedChange: (Boolean) -> Unit,
    onToggleSystemPerformanceGroup: () -> Unit,
    onToggleAppearanceBehaviorGroup: () -> Unit,
    onToggleSecurityAccountGroup: () -> Unit,
    onToggleRemoteAccessGroup: () -> Unit,
    onVersionNumberEasterEgg: () -> Unit,
    backgroundPersistence: BackgroundPersistenceUiState,
    exactAlarmWarningActive: Boolean,
    onOpenTransferQueue: () -> Unit = {},
    onboardingSteps: List<OnboardingPermissionStep> = emptyList(),
    onOpenOnboardingPermissions: () -> Unit = {}
) {
    var versionTapCount by remember { mutableIntStateOf(0) }
    var lastVersionTapEpochMs by remember { mutableLongStateOf(0L) }
    val versionTapInteraction = remember { MutableInteractionSource() }
    var showFeedbackDialog by remember { mutableStateOf(false) }

    SettingsPageShell(
        title = stringRes("settings"),
        layoutMode = layoutMode,
        onBack = onBack.takeIf { showBackNavigation },
        onOpenTransferQueue = onOpenTransferQueue
    ) { contentModifier ->
        Box(modifier = contentModifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 48.dp)
            ) {
                val ungrantedSteps = onboardingSteps.filter { !it.granted }
                if (ungrantedSteps.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clickable(onClick = onOpenOnboardingPermissions),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringRes("onboarding_permissions_title"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringRes("onboarding_permissions_desc"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
                val tailscale by TailscaleNodeRuntime.state.collectAsState()
                val menuEntries = settingsMenuEntries(
                    playStoreBuild = com.fileapex.di.FileApexServices.managedByStore,
                    googleCloud = !com.fileapex.di.FileApexServices.isFdroidBuild,
                    tailscale = !com.fileapex.di.FileApexServices.isFdroidBuild,
                    iroh = !com.fileapex.di.FileApexServices.isFdroidBuild,
                    desktopFileSelection = usesDesktopFileSelection(),
                    deviceNamePage = supportsDeviceNameSettingsPage(),
                    windowsFluent = supportsWindowsFluentDesign(),
                    backupSync = com.fileapex.platform.backupSupported(),
                    autoLaunchOnDesktop = com.fileapex.platform.BootLaunchPreference.usesDesktopAutostart(),
                    showAdvanced = state.showAdvancedSettings,
                    inUseAdvancedIds = rememberAdvancedSettingsInUse(state)
                )
                SettingsMenuSection.entries.forEach { section ->
                    val rows = menuEntries.filter { it.section == section }
                    if (rows.isEmpty()) return@forEach
                    val expanded = when (section) {
                        SettingsMenuSection.General -> state.generalExpanded
                        SettingsMenuSection.SystemPerformance -> state.systemPerformanceExpanded
                        SettingsMenuSection.Appearance -> state.appearanceBehaviorExpanded
                        SettingsMenuSection.Security -> state.securityAccountExpanded
                        SettingsMenuSection.RemoteAccess -> state.remoteAccessExpanded
                    }
                    val onToggle = when (section) {
                        SettingsMenuSection.General -> onToggleGeneralGroup
                        SettingsMenuSection.SystemPerformance -> onToggleSystemPerformanceGroup
                        SettingsMenuSection.Appearance -> onToggleAppearanceBehaviorGroup
                        SettingsMenuSection.Security -> onToggleSecurityAccountGroup
                        SettingsMenuSection.RemoteAccess -> onToggleRemoteAccessGroup
                    }
                    SettingsCategoryGroup(
                        title = stringRes(section.titleKey()),
                        expanded = expanded,
                        onToggle = onToggle
                    ) {
                        rows.forEach { entry ->
                            when (entry.id) {
                                SettingsMenuIds.DEVICE_NAME -> SettingsNavItem(
                                    title = stringRes("device_name"),
                                    subtitle = state.deviceNameBroadcasting.ifBlank { stringRes("paired_device") },
                                    onClick = onOpenDeviceName
                                )
                                SettingsMenuIds.LANGUAGE -> SettingsNavItem(
                                    title = stringRes("language"),
                                    subtitle = AppI18n.languageRowLabel(AppI18n.locale),
                                    onClick = onOpenLanguage
                                )
                                SettingsMenuIds.CHECK_FOR_UPDATES -> SettingsNavItem(
                                    title = stringRes("check_for_updates"),
                                    subtitle = if (state.checkForUpdatesEnabled) {
                                        UpdateCheckFrequency.label(
                                            state.checkForUpdatesIntervalUnit,
                                            state.checkForUpdatesIntervalAmount
                                        )
                                    } else {
                                        stringRes("off")
                                    },
                                    onClick = onOpenCheckForUpdates
                                )
                                SettingsMenuIds.REPORT_ISSUE -> SettingsNavItem(
                                    title = stringRes("report_issue_feedback"),
                                    subtitle = stringRes("report_issue_feedback_desc"),
                                    onClick = { showFeedbackDialog = true }
                                )
                                SettingsMenuIds.BACKGROUND_PERSISTENCE -> SettingsNavItem(
                                    title = stringRes("background_persistence"),
                                    subtitle = backgroundPersistenceSubtitle(
                                        watchdogEnabled = state.enableServiceWatchdog,
                                        backgroundPersistence = backgroundPersistence,
                                        exactAlarmWarningActive = exactAlarmWarningActive
                                    ),
                                    onClick = onOpenBackgroundPersistence
                                )
                                SettingsMenuIds.AUTO_LAUNCH -> SettingsNavItem(
                                    title = stringRes(if (com.fileapex.platform.BootLaunchPreference.usesDesktopAutostart()) "auto_launch_on_signin" else "auto_launch_on_reboot"),
                                    subtitle = if (state.autoLaunchOnReboot) stringRes("on") else stringRes("off"),
                                    onClick = onOpenAutoLaunchOnReboot
                                )
                                SettingsMenuIds.TAILSCALE -> SettingsNavItem(
                                    title = stringRes("tailscale_overlay"),
                                    subtitle = tailscaleRowSubtitle(tailscale),
                                    onClick = onOpenTailscale
                                )
                                SettingsMenuIds.IROH -> SettingsNavItem(
                                    title = stringRes("iroh_title"),
                                    subtitle = irohRowSubtitle(),
                                    onClick = onOpenIroh
                                )
                                SettingsMenuIds.THEMES -> SettingsNavItem(
                                    title = stringRes("themes"),
                                    subtitle = localizedThemeName(state.appTheme),
                                    onClick = onOpenThemes
                                )
                                SettingsMenuIds.BULLETIN_BOARD -> SettingsNavItem(
                                    title = stringRes("bulletin_board"),
                                    subtitle = localizedBulletinBoardStyleName(state.bulletinBoardStyle),
                                    onClick = onOpenBulletinBoard
                                )
                                SettingsMenuIds.BACKUP_SYNC -> SettingsNavItem(
                                    title = stringRes("backup_sync"),
                                    subtitle = backupSummary(),
                                    onClick = onOpenBackupSync
                                )
                                SettingsMenuIds.NOTIFICATIONS -> SettingsNavItem(
                                    title = stringRes("notifications"),
                                    subtitle = if (
                                        state.notesNotificationsEnabled ||
                                        state.driveRelayNotificationsEnabled ||
                                        state.fileTransferNotificationsEnabled ||
                                        state.liveTransferCapsuleEnabled
                                    ) stringRes("on") else stringRes("off"),
                                    onClick = onOpenNotifications
                                )
                                SettingsMenuIds.CLIPBOARD -> SettingsNavItem(
                                    title = stringRes("clipboard"),
                                    subtitle = clipboardSettingsSubtitle(state),
                                    onClick = onOpenClipboard
                                )
                                SettingsMenuIds.DEVICE_DETAILS -> SettingsNavItem(
                                    title = stringRes("device_details"),
                                    subtitle = stringRes("peer_telemetry_fields"),
                                    onClick = onOpenDeviceDetails
                                )
                                SettingsMenuIds.DESKTOP_LAYOUT -> SettingsNavItem(
                                    title = stringRes("desktop_layout"),
                                    subtitle = localizedDesktopLayout(state.desktopLayoutMode),
                                    onClick = onOpenDesktopLayout
                                )
                                SettingsMenuIds.WINDOWS_DESIGN -> SettingsNavItem(
                                    title = stringRes("windows_design"),
                                    subtitle = localizedDesktopUiStyle(state.desktopUiStyle),
                                    onClick = onOpenWindowsDesign
                                )
                                SettingsMenuIds.PIN_REQUIRED -> SettingsNavItem(
                                    title = stringRes("pin_required"),
                                    subtitle = AppI18n.t(
                                        "pin_subtitle",
                                        if (state.pinRequiredEnabled) AppI18n.t("on") else AppI18n.t("off"),
                                        localizedPinIdle(state.pinIdleTimeout)
                                    ),
                                    onClick = onOpenPinRequired
                                )
                                SettingsMenuIds.DRIVE_RELAY -> SettingsNavItem(
                                    title = stringRes("drive_relay"),
                                    subtitle = when {
                                        !state.googleAccountLinkEnabled -> stringRes("google_unlinked")
                                        state.googleDriveRelayEnabled -> stringRes("on")
                                        else -> stringRes("off")
                                    },
                                    onClick = onOpenGoogleAccount
                                )
                                SettingsMenuIds.GOOGLE_ACCOUNT -> SettingsNavItem(
                                    title = stringRes("google_account"),
                                    subtitle = googleAccountSubtitle(state),
                                    onClick = onOpenGoogleAccount
                                )
                                SettingsMenuIds.LEAVE_CLUSTER -> SettingsNavItem(
                                    title = stringRes("leave_cluster_wipe_title"),
                                    subtitle = stringRes("leave_cluster_wipe_desc"),
                                    isDestructive = true,
                                    leadingIcon = Icons.Filled.Warning,
                                    onClick = onOpenLeaveClusterWipe
                                )
                            }
                        }
                    }
                }
                SettingsAdvancedAndAboutRows(state.showAdvancedSettings, onShowAdvancedChange, onOpenAbout)
            }
            val cleanVersion = appVersionName.removePrefix("v").removePrefix("V")
            val isAndroidGitHub = !usesDesktopFileSelection() && !com.fileapex.di.FileApexServices.managedByStore
            val versionDisplayText = if (isAndroidGitHub) {
                "FileApex v$cleanVersion (GitHub)"
            } else if (com.fileapex.di.FileApexServices.isFdroidBuild) {
                "FileApex v$cleanVersion (F-Droid)"
            } else {
                "FileApex v$cleanVersion"
            }
            Text(
                text = versionDisplayText,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .clickable(
                        interactionSource = versionTapInteraction,
                        indication = null,
                        onClick = {
                            if (!TimeUtils.isWithinWindow(
                                    lastVersionTapEpochMs,
                                    VERSION_EASTER_EGG_TAP_WINDOW_MS
                                )
                            ) {
                                versionTapCount = 0
                            }
                            lastVersionTapEpochMs = TimeUtils.now()
                            versionTapCount += 1
                            if (versionTapCount >= VERSION_EASTER_EGG_TAP_COUNT) {
                                versionTapCount = 0
                                onVersionNumberEasterEgg()
                            }
                        }
                    ),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                    fontSize = 12.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
            )
        }

        if (showFeedbackDialog) {
            AlertDialog(
                onDismissRequest = { showFeedbackDialog = false },
                modifier = Modifier.widthIn(max = 440.dp),
                title = {
                    Text(
                        text = stringRes("report_issue_feedback"),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FeedbackOptionCard(
                            icon = Icons.Default.Email,
                            title = stringRes("feedback_option_email"),
                            subtitle = stringRes("feedback_option_email_desc"),
                            onClick = {
                                showFeedbackDialog = false
                                Diagnostics.sendFeedbackEmail()
                            }
                        )
                        FeedbackOptionCard(
                            icon = Icons.Default.Feedback,
                            title = stringRes("feedback_option_form"),
                            subtitle = stringRes("feedback_option_form_desc"),
                            onClick = {
                                showFeedbackDialog = false
                                Diagnostics.openAnonymousFeedbackForm()
                            }
                        )
                        FeedbackOptionCard(
                            icon = Icons.Default.OpenInBrowser,
                            title = stringRes("feedback_option_github"),
                            subtitle = stringRes("feedback_option_github_desc"),
                            onClick = {
                                showFeedbackDialog = false
                                Diagnostics.openGitHubIssuesTracker()
                            }
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showFeedbackDialog = false }) {
                        Text(stringRes("cancel"))
                    }
                }
            )
        }
    }
}

@Composable
private fun FeedbackOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun backgroundPersistenceSubtitle(
    watchdogEnabled: Boolean,
    backgroundPersistence: BackgroundPersistenceUiState,
    exactAlarmWarningActive: Boolean
): String {
    val status = if (watchdogEnabled) AppI18n.t("on") else AppI18n.t("off")
    val warnings = buildList {
        if (backgroundPersistence.backgroundRestricted) add(AppI18n.t("warn_background_restricted"))
        if (backgroundPersistence.batteryOptimizationRestricted) add(AppI18n.t("warn_battery_optimized"))
        if (backgroundPersistence.unusedAppRestrictionsActive) add(AppI18n.t("warn_hibernation"))
        if (!com.fileapex.di.FileApexServices.isPlayStoreBuild && exactAlarmWarningActive) add(AppI18n.t("warn_alarms_off"))
        if (backgroundPersistence.showOemSetup &&
            !backgroundPersistence.persistenceRestricted &&
            !backgroundPersistence.unusedAppRestrictionsActive
        ) {
            add(AppI18n.t("warn_oem_app_launch"))
        }
    }
    return if (warnings.isEmpty()) {
        status
    } else {
        AppI18n.t("status_on_warnings", status, warnings.joinToString(", "))
    }
}

private fun clipboardSettingsSubtitle(state: SettingsUiState): String {
    if (!state.clipboardSharingEnabled) return AppI18n.t("off")
    val mode = clipboardShareTargetsSubtitle(state)
    val extras = buildList {
        if (currentPlatformLabel() == "Android" && state.clipboardAccessibilityEnabled) add(AppI18n.t("accessibility"))
        if (currentPlatformLabel() == "Android" && !com.fileapex.di.FileApexServices.isPlayStoreBuild && state.clipboardShizukuEnabled) add(AppI18n.t("diag_shizuku_active"))
        if (currentPlatformLabel() != "Android" && state.clipboardAutoSendEnabled) add(AppI18n.t("auto_send"))
        if (state.clipboardViaCellularEnabled && currentPlatformLabel() == "Android") add(AppI18n.t("cellular"))
    }
    return if (extras.isEmpty()) {
        AppI18n.t("clipboard_on_mode", mode)
    } else {
        AppI18n.t("clipboard_on_mode_extras", mode, extras.joinToString(" · "))
    }
}

@Composable
private fun localizedDesktopLayout(mode: DesktopLayoutMode): String = when (mode) {
    DesktopLayoutMode.Compact -> stringRes("layout_compact")
    DesktopLayoutMode.Expanded -> stringRes("layout_expanded")
}

@Composable
private fun localizedDesktopUiStyle(style: DesktopUiStyle): String = when (style) {
    DesktopUiStyle.Standard -> stringRes("windows_standard")
    DesktopUiStyle.WindowsFluent -> stringRes("windows_fluent")
}

private const val VERSION_EASTER_EGG_TAP_COUNT = 5

private const val VERSION_EASTER_EGG_TAP_WINDOW_MS = 2_000L

@Composable
private fun CollapsibleCategoryHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isFluxGlass = LocalAppTheme.current.traits.fluxSurfaces
    val rotationAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "ChevronRotation"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = 13.sp,
                letterSpacing = 0.8.sp
            ),
            fontWeight = FontWeight.Bold,
            color = if (isFluxGlass) Color.White else MaterialTheme.colorScheme.primary
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) {
                stringRes("collapse_section", title)
            } else {
                stringRes("expand_section", title)
            },
            modifier = Modifier.rotate(rotationAngle),
            tint = if (isFluxGlass) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsCategoryGroup(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        CollapsibleCategoryHeader(
            title = title,
            expanded = expanded,
            onToggle = onToggle
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    }
}
