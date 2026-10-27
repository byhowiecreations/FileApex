package com.fileapex.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.data.settings.DesktopLayoutMode
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import com.fileapex.platform.BackgroundPersistenceUiState
import com.fileapex.platform.OnboardingPermissionStep
import com.fileapex.presentation.SettingsUiState
import com.fileapex.presentation.SettingsViewModel
import com.fileapex.ui.settings.AboutSettingsPage
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
import com.fileapex.ui.settings.NotificationsSettingsPage
import com.fileapex.ui.settings.OnboardingPermissionsSettingsPage
import com.fileapex.ui.settings.PinRequiredSettingsPage
import com.fileapex.ui.settings.RemoteFileDeletionSettingsPage
import com.fileapex.ui.settings.TailscaleSettingsPage
import com.fileapex.ui.settings.ThemesSettingsPage
import com.fileapex.ui.settings.WindowsDesignSettingsPage

internal class SettingsPageScope(
    val state: SettingsUiState,
    val updateStatus: String?,
    val googleLinkStatus: String?,
    val viewModel: SettingsViewModel,
    pageState: MutableState<SettingsPage>,
    val layoutMode: SettingsScreenLayoutMode,
    val onBack: () -> Unit,
    val goUp: () -> Unit,
    val appVersionName: String,
    val showRootBackNavigation: Boolean,
    val backgroundPersistence: BackgroundPersistenceUiState,
    val exactAlarmWarningActive: Boolean,
    val onOpenTransferQueue: () -> Unit,
    val onboardingSteps: List<OnboardingPermissionStep>,
    val deniedOnboardingStepIds: Set<String>,
    val onGrantOnboardingStep: (String) -> Unit,
    val onRequestBatteryUnrestricted: () -> Unit,
    val onOpenBackgroundPersistenceSettings: () -> Unit,
    val onOpenUnusedAppRestrictionsSettings: () -> Unit,
    val onOpenAppBatteryUsageSettings: () -> Unit,
    val onOpenExactAlarmSettings: () -> Unit,
    val onOpenAppDetailsSettings: () -> Unit,
    val onBeforeAllowOverCellularEnabled: (onProceed: () -> Unit) -> Unit
) {
    var page by pageState
}

@Composable
internal fun SettingsPageScope.PagesGeneral() {
    with(this) {
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
            onOpenGroups = { page = SettingsPage.Groups },
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

            else -> Unit
        }
    }
}

@Composable
internal fun SettingsPageScope.PagesClipboard() {
    with(this) {
        when (page) {
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
            else -> Unit
        }
    }
}

@Composable
internal fun SettingsPageScope.PagesDevice() {
    with(this) {
        when (page) {
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
        SettingsPage.Groups -> com.fileapex.friends.ui.GroupsSettingsPage(layoutMode, goUp)
        SettingsPage.LeaveClusterWipe -> LeaveClusterWipeSettingsPage(layoutMode, goUp)
        SettingsPage.About -> AboutSettingsPage(layoutMode, goUp)
            else -> Unit
        }
    }
}
