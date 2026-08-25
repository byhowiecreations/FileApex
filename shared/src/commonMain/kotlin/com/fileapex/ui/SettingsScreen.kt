package com.fileapex.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.ui.theme.isFileApexCustomGlassTheme
import androidx.compose.ui.graphics.Color

import androidx.compose.material3.RadioButtonDefaults
import com.fileapex.domain.diagnostics.DeviceDetailsDisplayPreferences


import com.fileapex.domain.diagnostics.DeviceDetailsFieldId
import kotlin.math.roundToInt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.domain.clipboard.ClipboardShareMode
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.data.settings.PinIdleTimeout
import com.fileapex.data.settings.DesktopLayoutMode
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.data.settings.DriveRelayMaxMb
import com.fileapex.data.settings.UpdateCheckFrequency
import com.fileapex.data.settings.UpdateCheckUnit
import com.fileapex.platform.BackgroundPersistenceUiState
import com.fileapex.platform.ClipboardDiagnosticsPolicy
import com.fileapex.platform.ClipboardRuntimeDiagnostics
import com.fileapex.platform.ClipboardShizukuPolicy
import com.fileapex.platform.FileApexBackHandler
import com.fileapex.platform.supportsWindowsFluentDesign
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.util.TimeUtils
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.AppLocale
import com.fileapex.i18n.persistAppLanguage
import com.fileapex.i18n.stringRes
import com.fileapex.platform.rememberGoogleSignInLauncher
import com.fileapex.platform.rememberGoogleDriveAuthLauncher
import com.fileapex.cloud.drive.GoogleDriveAuth
import com.fileapex.ui.dialogs.GoogleDrivePermissionDialog
import com.fileapex.presentation.SettingsUiState
import com.fileapex.presentation.SettingsViewModel
import com.fileapex.ui.adaptive.CompactHomeTitleBand
import com.fileapex.ui.adaptive.CompactHomeTitleStyle
import com.fileapex.ui.adaptive.FileApexPaneSectionHeader
import com.fileapex.ui.theme.fileApexTopAppBarColors
import com.fileapex.update.rememberRequestInstallUnknownAppsPermission
import kotlinx.coroutines.delay

private enum class SettingsPage {
    Root,
    CheckForUpdates,
    PinRequired,
    BackgroundPersistence,
    AutoLaunchOnReboot,
    Notifications,
    FileTransferNotifications,
    Themes,
    Clipboard,
    ClipboardShareTargets,
    ClipboardDiagnostics,
    DeviceDetails,
    GoogleAccount,
    RemoteFileDeletion,
    DesktopLayout,
    WindowsDesign,
    Language
}



enum class SettingsScreenLayoutMode {
    /** Phone / compact: teal top bar scaffold. */
    FullScreen,
    /** Wide navigation rail: white pane header matching Devices list pane. */
    ListPane,
    /** Compact primary shell: pane title band on root, section header on sub-pages. */
    CompactShell
}

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
    viewModel: SettingsViewModel = viewModel { SettingsViewModel() }
) {
    val state by viewModel.uiState.collectAsState()
    val updateStatus by viewModel.updateStatusMessage.collectAsState()
    val googleLinkStatus by viewModel.googleLinkStatus.collectAsState()
    var page by remember { mutableStateOf(SettingsPage.Root) }

    val leavePage: () -> Unit = {
        when (page) {
            SettingsPage.Root -> if (showRootBackNavigation) onBack()
            SettingsPage.FileTransferNotifications -> page = SettingsPage.Notifications
            SettingsPage.ClipboardDiagnostics,
            SettingsPage.ClipboardShareTargets -> page = SettingsPage.Clipboard
            else -> page = SettingsPage.Root
        }
    }

    FileApexBackHandler(
        enabled = page != SettingsPage.Root || showRootBackNavigation,
        onBack = leavePage
    )

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
            onOpenLanguage = { page = SettingsPage.Language },
            onOpenNotifications = { page = SettingsPage.Notifications },
            onOpenThemes = { page = SettingsPage.Themes },
            onOpenClipboard = { page = SettingsPage.Clipboard },

            onOpenDeviceDetails = { page = SettingsPage.DeviceDetails },
            onOpenGoogleAccount = { page = SettingsPage.GoogleAccount },
            onOpenRemoteFileDeletion = { page = SettingsPage.RemoteFileDeletion },
            onOpenDesktopLayout = { page = SettingsPage.DesktopLayout },
            onOpenWindowsDesign = { page = SettingsPage.WindowsDesign },
            onToggleSystemPerformanceGroup = viewModel::toggleSystemPerformanceGroup,
            onToggleAppearanceBehaviorGroup = viewModel::toggleAppearanceBehaviorGroup,
            onToggleSecurityAccountGroup = viewModel::toggleSecurityAccountGroup,
            onVersionNumberEasterEgg = viewModel::onVersionNumberEasterEgg,
            backgroundPersistence = backgroundPersistence,
            exactAlarmWarningActive = exactAlarmWarningActive,
            onOpenTransferQueue = onOpenTransferQueue
        )
        SettingsPage.Notifications -> NotificationsSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = { page = SettingsPage.Root },
            onToggleFileTransferNotifications = viewModel::setFileTransferNotifications,
            onToggleNotesNotifications = viewModel::setNotesNotifications,
            onToggleDriveRelayNotifications = viewModel::setDriveRelayNotifications,
            onToggleLiveTransferCapsule = viewModel::setLiveTransferCapsule
        )
        SettingsPage.CheckForUpdates -> CheckForUpdatesSettingsPage(
            state = state,
            updateStatus = updateStatus,
            layoutMode = layoutMode,
            onBack = { page = SettingsPage.Root },
            onToggle = viewModel::setCheckForUpdates,
            onUnitSelected = viewModel::setCheckForUpdatesUnit,
            onAmountTextChange = viewModel::setCheckForUpdatesAmountText,
            onWeekAmountSelected = viewModel::setCheckForUpdatesWeekAmount
        )
        SettingsPage.PinRequired -> PinRequiredSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = { page = SettingsPage.Root },
            onToggle = viewModel::setPinRequired,
            onPinChange = viewModel::setDevicePin,
            onIdleTimeoutSelected = viewModel::setPinIdleTimeout
        )
        SettingsPage.BackgroundPersistence -> BackgroundPersistenceSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = { page = SettingsPage.Root },
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
            onBack = { page = SettingsPage.Root },
            onToggle = viewModel::setAutoLaunchOnReboot
        )
        SettingsPage.FileTransferNotifications -> FileTransferNotificationsSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = { page = SettingsPage.Notifications },
            onToggle = viewModel::setFileTransferNotifications
        )

        SettingsPage.Themes -> ThemesSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = { page = SettingsPage.Root },
            onSelectTheme = viewModel::setAppTheme,
            onToggleConnectedLines = viewModel::setKineticSphereConnectedLinesEnabled,
            onToggleOrbitalRings = viewModel::setKineticSphereOrbitalRingsEnabled
        )

        SettingsPage.Clipboard -> ClipboardSettingsPage(

            state = state,
            layoutMode = layoutMode,
            onBack = { page = SettingsPage.Root },
            onToggle = viewModel::setClipboardSharing,
            onToggleViaCellular = viewModel::setClipboardViaCellular,
            onToggleAccessibility = viewModel::setClipboardAccessibility,
            onToggleSendNotification = viewModel::setClipboardSendNotification,
            onToggleShizuku = viewModel::setClipboardShizuku,
            onToggleAutoSend = viewModel::setClipboardAutoSend,
            onDismissRestrictedHelp = viewModel::dismissAccessibilityRestrictedHelp,
            onOpenAppInfo = viewModel::openAccessibilityAppInfo,
            onOpenAccessibilitySettings = viewModel::openAccessibilitySystemSettings,
            onOpenShareTargets = { page = SettingsPage.ClipboardShareTargets },
            onOpenDiagnostics = { page = SettingsPage.ClipboardDiagnostics }
        )
        SettingsPage.ClipboardShareTargets -> ClipboardShareTargetsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = { page = SettingsPage.Clipboard },
            onShareModeChange = viewModel::setClipboardShareMode,
            onTogglePeer = viewModel::setClipboardTargetDevice
        )
        SettingsPage.ClipboardDiagnostics -> SettingsPageShell(
            title = stringRes("clipboard_diagnostics"),
            layoutMode = layoutMode,
            onBack = { page = SettingsPage.Clipboard }
        ) { contentModifier ->
            Column(
                modifier = contentModifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                ClipboardDiagnosticsContent(
                    sharingEnabled = state.clipboardSharingEnabled,
                    recipientsChosen = ClipboardDiagnosticsPolicy.recipientsChosen(
                        shareModeAll = state.clipboardShareMode == ClipboardShareMode.ALL,
                        shareModeSpecific = state.clipboardShareMode == ClipboardShareMode.SPECIFIC,
                        specificTargetCount = state.clipboardTargetDeviceIds.size
                    ),
                    accessibilityEnabled = state.clipboardAccessibilityEnabled,
                    shizukuOptedIn = state.clipboardShizukuEnabled,
                    onOpenAccessibility = viewModel::openAccessibilitySystemSettings,
                    onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
                    onOpenAppInfo = viewModel::openAccessibilityAppInfo
                )
            }
        }
        SettingsPage.DeviceDetails -> DeviceDetailsSettingsPage(
            preferences = state.deviceDetailsDisplayPreferences,
            allowOverCellular = state.deviceDetailsAllowOverCellular,
            layoutMode = layoutMode,
            onBack = { page = SettingsPage.Root },
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
            onBack = { page = SettingsPage.Root },
            onDisable = viewModel::disableGoogleAccountLink,
            onIdToken = viewModel::onGoogleIdToken,
            onCellularChange = viewModel::setCellularEnabled,
            onDriveRelayChange = viewModel::setGoogleDriveRelayEnabled,
            onDriveRelayMaxMbSelected = viewModel::setDriveRelayMaxMb,
            onDriveAuthResult = viewModel::onGoogleDriveAuthResult,
            onPurgeChange = viewModel::setDrivePurgeAfter72Hours,
            onPurgeNow = viewModel::purgeDriveRelayNow
        )
        SettingsPage.RemoteFileDeletion -> RemoteFileDeletionSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = { page = SettingsPage.Root },
            onAllowRemoteFileDeletionChange = viewModel::setAllowRemoteFileDeletion
        )
        SettingsPage.DesktopLayout -> DesktopLayoutSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = { page = SettingsPage.Root },
            onExpanded = { expanded ->
                viewModel.setDesktopLayoutMode(
                    if (expanded) DesktopLayoutMode.Expanded else DesktopLayoutMode.Compact
                )
            }
        )
        SettingsPage.WindowsDesign -> WindowsDesignSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = { page = SettingsPage.Root },
            onFluent = { enabled ->
                viewModel.setDesktopUiStyle(
                    if (enabled) DesktopUiStyle.WindowsFluent else DesktopUiStyle.Standard
                )
            }
        )
        SettingsPage.Language -> LanguageSettingsPage(
            layoutMode = layoutMode,
            onBack = { page = SettingsPage.Root }
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
    onOpenLanguage: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenThemes: () -> Unit,
    onOpenClipboard: () -> Unit,

    onOpenDeviceDetails: () -> Unit,
    onOpenGoogleAccount: () -> Unit,
    onOpenRemoteFileDeletion: () -> Unit,
    onOpenDesktopLayout: () -> Unit,
    onOpenWindowsDesign: () -> Unit,
    onToggleSystemPerformanceGroup: () -> Unit,
    onToggleAppearanceBehaviorGroup: () -> Unit,
    onToggleSecurityAccountGroup: () -> Unit,
    onVersionNumberEasterEgg: () -> Unit,
    backgroundPersistence: BackgroundPersistenceUiState,
    exactAlarmWarningActive: Boolean,
    onOpenTransferQueue: () -> Unit = {}
) {
    var versionTapCount by remember { mutableIntStateOf(0) }
    var lastVersionTapEpochMs by remember { mutableLongStateOf(0L) }
    val versionTapInteraction = remember { MutableInteractionSource() }

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
                SettingsCategoryGroup(
                    title = stringRes("system_app_performance"),
                    expanded = state.systemPerformanceExpanded,
                    onToggle = onToggleSystemPerformanceGroup
                ) {
                    SettingsNavItem(
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
                    SettingsNavItem(
                        title = stringRes("background_persistence"),
                        subtitle = backgroundPersistenceSubtitle(
                            watchdogEnabled = state.enableServiceWatchdog,
                            backgroundPersistence = backgroundPersistence,
                            exactAlarmWarningActive = exactAlarmWarningActive
                        ),
                        onClick = onOpenBackgroundPersistence
                    )
                    if (!usesDesktopFileSelection()) {
                        SettingsNavItem(
                            title = stringRes("auto_launch_on_reboot"),
                            subtitle = if (state.autoLaunchOnReboot) stringRes("on") else stringRes("off"),
                            onClick = onOpenAutoLaunchOnReboot
                        )
                    }
                    SettingsNavItem(
                        title = stringRes("language"),
                        subtitle = AppI18n.languageRowLabel(AppI18n.locale),
                        onClick = onOpenLanguage
                    )
                }

                SettingsCategoryGroup(
                    title = stringRes("appearance_behavior"),
                    expanded = state.appearanceBehaviorExpanded,
                    onToggle = onToggleAppearanceBehaviorGroup
                ) {
                    SettingsNavItem(
                        title = stringRes("themes"),
                        subtitle = localizedThemeName(state.appTheme),
                        onClick = onOpenThemes
                    )
                    SettingsNavItem(
                        title = stringRes("notifications"),
                        subtitle = if (
                            state.notesNotificationsEnabled ||
                            state.driveRelayNotificationsEnabled ||
                            state.fileTransferNotificationsEnabled ||
                            state.liveTransferCapsuleEnabled
                        ) stringRes("on") else stringRes("off"),
                        onClick = onOpenNotifications
                    )
                    SettingsNavItem(
                        title = stringRes("clipboard"),
                        subtitle = clipboardSettingsSubtitle(state),
                        onClick = onOpenClipboard
                    )
                    SettingsNavItem(
                        title = stringRes("device_details"),
                        subtitle = stringRes("peer_telemetry_fields"),
                        onClick = onOpenDeviceDetails
                    )
                    if (usesDesktopFileSelection()) {
                        SettingsNavItem(
                            title = stringRes("desktop_layout"),
                            subtitle = localizedDesktopLayout(state.desktopLayoutMode),
                            onClick = onOpenDesktopLayout
                        )
                    }
                    if (supportsWindowsFluentDesign()) {
                        SettingsNavItem(
                            title = stringRes("windows_design"),
                            subtitle = localizedDesktopUiStyle(state.desktopUiStyle),
                            onClick = onOpenWindowsDesign
                        )
                    }
                }

                SettingsCategoryGroup(
                    title = stringRes("security_account"),
                    expanded = state.securityAccountExpanded,
                    onToggle = onToggleSecurityAccountGroup
                ) {
                    SettingsNavItem(
                        title = stringRes("pin_required"),
                        subtitle = AppI18n.t(
                            "pin_subtitle",
                            if (state.pinRequiredEnabled) AppI18n.t("on") else AppI18n.t("off"),
                            localizedPinIdle(state.pinIdleTimeout)
                        ),
                        onClick = onOpenPinRequired
                    )
                    SettingsNavItem(
                        title = stringRes("google_account"),
                        subtitle = googleAccountSubtitle(state),
                        onClick = onOpenGoogleAccount
                    )
                    SettingsNavItem(
                        title = stringRes("allow_remote_file_deletion"),
                        subtitle = if (state.allowRemoteFileDeletion) stringRes("on") else stringRes("off"),
                        onClick = onOpenRemoteFileDeletion
                    )
                }
            }
            Text(
                text = "FileApex v$appVersionName",
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
        if (exactAlarmWarningActive) add(AppI18n.t("warn_alarms_off"))
    }
    return if (warnings.isEmpty()) {
        status
    } else {
        AppI18n.t("status_on_warnings", status, warnings.joinToString(", "))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackgroundPersistenceSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onEnableServiceWatchdog: (Boolean) -> Unit,
    backgroundPersistence: BackgroundPersistenceUiState,
    onRequestBatteryUnrestricted: () -> Unit,
    onOpenBackgroundPersistenceSettings: () -> Unit,
    onOpenUnusedAppRestrictionsSettings: () -> Unit,
    onOpenAppBatteryUsageSettings: () -> Unit,
    exactAlarmWarningActive: Boolean,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit
) {
    SettingsPageShell(
        title = stringRes("background_persistence"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text(stringRes("service_watchdog"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("watchdog_desc"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = state.enableServiceWatchdog,
                        onCheckedChange = onEnableServiceWatchdog
                    )
                }
            )
            if (backgroundPersistence.backgroundRestricted) {
                ListItem(
                    headlineContent = { Text(stringRes("background_restricted"), softWrap = true) },
                    supportingContent = {
                        Text(
                            backgroundPersistence.oemGuidance?.let { guidance ->
                                stringRes(
                                    "background_restricted_desc_steps",
                                    stringRes(guidance.batteryStepsKey)
                                )
                            } ?: stringRes("background_restricted_desc"),
                            softWrap = true
                        )
                    },
                    modifier = Modifier.clickable { onOpenAppBatteryUsageSettings() }
                )
            }
            if (backgroundPersistence.batteryOptimizationRestricted) {
                ListItem(
                    headlineContent = { Text(stringRes("battery_optimization_active"), softWrap = true) },
                    supportingContent = {
                        Text(stringRes("battery_optimization_desc"), softWrap = true)
                    },
                    modifier = Modifier.clickable { onRequestBatteryUnrestricted() }
                )
            }
            if (backgroundPersistence.unusedAppRestrictionsActive) {
                ListItem(
                    headlineContent = { Text(stringRes("pause_unused"), softWrap = true) },
                    supportingContent = {
                        Text(stringRes("pause_unused_desc"), softWrap = true)
                    },
                    modifier = Modifier.clickable { onOpenUnusedAppRestrictionsSettings() }
                )
            }
            backgroundPersistence.oemGuidance?.let { guidance ->
                if (backgroundPersistence.persistenceRestricted ||
                    backgroundPersistence.unusedAppRestrictionsActive
                ) {
                    ListItem(
                        headlineContent = { Text(stringRes("vendor_setup", guidance.vendorLabel), softWrap = true) },
                        supportingContent = {
                            Text(
                                buildString {
                                    append(stringRes(guidance.batteryStepsKey))
                                    guidance.autoStartHintKey?.let { hintKey ->
                                        append('\n')
                                        append(stringRes(hintKey))
                                    }
                                },
                                softWrap = true
                            )
                        },
                        modifier = Modifier.clickable { onOpenBackgroundPersistenceSettings() }
                    )
                }
            }
            if (exactAlarmWarningActive) {
                ListItem(
                    headlineContent = { Text(stringRes("exact_alarms_disabled"), softWrap = true) },
                    supportingContent = {
                        Text(stringRes("exact_alarms_desc"), softWrap = true)
                    },
                    modifier = Modifier.clickable { onOpenExactAlarmSettings() }
                )
            }
            ListItem(
                headlineContent = { Text(stringRes("system_app_settings"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("system_app_settings_desc"), softWrap = true)
                },
                modifier = Modifier.clickable { onOpenAppDetailsSettings() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoLaunchOnRebootSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    SettingsPageShell(
        title = stringRes("auto_launch_on_reboot"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text(stringRes("start_share_after_reboot"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("start_share_after_reboot_desc"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = state.autoLaunchOnReboot,
                        onCheckedChange = onToggle
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onToggleFileTransferNotifications: (Boolean) -> Unit,
    onToggleNotesNotifications: (Boolean) -> Unit,
    onToggleDriveRelayNotifications: (Boolean) -> Unit,
    onToggleLiveTransferCapsule: (Boolean) -> Unit
) {
    val driveRelayReady = state.googleDriveRelayEnabled
    SettingsPageShell(
        title = stringRes("notifications"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            FileApexPaneSectionHeader(title = stringRes("notifications"))

            ListItem(
                headlineContent = { Text(stringRes("bulletin_board"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("bulletin_board_notif_desc"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = state.notesNotificationsEnabled,
                        onCheckedChange = onToggleNotesNotifications
                    )
                }
            )

            HorizontalDivider()

            ListItem(
                headlineContent = { Text(stringRes("drive_relay"), softWrap = true) },
                supportingContent = {
                    Text(
                        if (driveRelayReady) {
                            stringRes("drive_relay_notif_desc")
                        } else {
                            stringRes("drive_relay_notif_off")
                        },
                        softWrap = true
                    )
                },
                trailingContent = {
                    Switch(
                        checked = driveRelayReady && state.driveRelayNotificationsEnabled,
                        onCheckedChange = onToggleDriveRelayNotifications,
                        enabled = driveRelayReady
                    )
                }
            )

            HorizontalDivider()

            ListItem(
                headlineContent = { Text(stringRes("file_transfer"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("file_transfer_notif_desc"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = state.fileTransferNotificationsEnabled,
                        onCheckedChange = onToggleFileTransferNotifications
                    )
                }
            )

            if (!usesDesktopFileSelection()) {
                ListItem(
                    modifier = Modifier.padding(start = 16.dp),
                    headlineContent = { Text(stringRes("live_activity"), softWrap = true) },
                    supportingContent = {
                        Text(stringRes("live_activity_desc"), softWrap = true)
                    },
                    trailingContent = {
                        Switch(
                            checked = state.fileTransferNotificationsEnabled &&
                                state.liveTransferCapsuleEnabled,
                            onCheckedChange = onToggleLiveTransferCapsule,
                            enabled = state.fileTransferNotificationsEnabled
                        )
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileTransferNotificationsSettingsPage(

    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    SettingsPageShell(
        title = stringRes("file_transfer_notifications"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text(stringRes("show_receive_notifications"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("show_receive_notif_desc"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = state.fileTransferNotificationsEnabled,
                        onCheckedChange = onToggle
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipboardSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onToggleViaCellular: (Boolean) -> Unit,
    onToggleAccessibility: (Boolean) -> Unit,
    onToggleSendNotification: (Boolean) -> Unit,
    onToggleShizuku: (Boolean) -> Unit,
    onToggleAutoSend: (Boolean) -> Unit,
    onDismissRestrictedHelp: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenShareTargets: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    val isAndroid = currentPlatformLabel() == "Android"
    SettingsPageShell(
        title = stringRes("clipboard"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text(stringRes("clipboard_sharing"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("clipboard_sharing_subtitle"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = state.clipboardSharingEnabled,
                        onCheckedChange = onToggle
                    )
                }
            )
            if (state.clipboardSharingEnabled) {
                if (isAndroid) {
                    ListItem(
                        modifier = Modifier.padding(start = 16.dp),
                        headlineContent = { Text(stringRes("send_clipboard"), softWrap = true) },
                        supportingContent = {
                            Text(stringRes("send_clipboard_notification_subtitle"), softWrap = true)
                        },
                        trailingContent = {
                            Switch(
                                checked = state.clipboardSendNotificationEnabled,
                                onCheckedChange = onToggleSendNotification
                            )
                        }
                    )
                    ListItem(
                        headlineContent = { Text(stringRes("accessibility"), softWrap = true) },
                        supportingContent = {
                            Text(stringRes("accessibility_subtitle"), softWrap = true)
                        },
                        trailingContent = {
                            Switch(
                                checked = state.clipboardAccessibilityEnabled,
                                onCheckedChange = onToggleAccessibility
                            )
                        }
                    )
                    ClipboardAccessibilityBanner(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    ClipboardShizukuToggle(
                        enabled = state.clipboardShizukuEnabled,
                        onToggle = onToggleShizuku
                    )
                    ListItem(
                        headlineContent = { Text(stringRes("via_cellular"), softWrap = true) },
                        supportingContent = {
                            Text(stringRes("via_cellular_subtitle"), softWrap = true)
                        },
                        trailingContent = {
                            Switch(
                                checked = state.clipboardViaCellularEnabled,
                                onCheckedChange = onToggleViaCellular
                            )
                        }
                    )
                } else {
                    ListItem(
                        headlineContent = { Text(stringRes("automatically_send"), softWrap = true) },
                        supportingContent = {
                            Text(stringRes("automatically_send_subtitle"), softWrap = true)
                        },
                        trailingContent = {
                            Switch(
                                checked = state.clipboardAutoSendEnabled,
                                onCheckedChange = onToggleAutoSend
                            )
                        }
                    )
                }
                SettingsNavItem(
                    title = stringRes("share_clipboard_with"),
                    subtitle = clipboardShareTargetsSubtitle(state),
                    onClick = onOpenShareTargets
                )
                if (isAndroid) {
                    ClipboardDiagnosticsEntry(
                        accessibilityEnabled = state.clipboardAccessibilityEnabled,
                        shizukuOptedIn = state.clipboardShizukuEnabled,
                        recipientsChosen = ClipboardDiagnosticsPolicy.recipientsChosen(
                            shareModeAll = state.clipboardShareMode == ClipboardShareMode.ALL,
                            shareModeSpecific = state.clipboardShareMode == ClipboardShareMode.SPECIFIC,
                            specificTargetCount = state.clipboardTargetDeviceIds.size
                        ),
                        onOpenDiagnostics = onOpenDiagnostics
                    )
                }
            }
        }
        if (state.showAccessibilityRestrictedHelp) {
            AlertDialog(
                onDismissRequest = onDismissRestrictedHelp,
                title = { Text(stringRes("allow_restricted_settings")) },
                text = {
                    Text(stringRes("restricted_settings_body"))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onOpenAppInfo()
                            onDismissRestrictedHelp()
                        }
                    ) { Text(stringRes("open_app_info")) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            onOpenAccessibilitySettings()
                            onDismissRestrictedHelp()
                        }
                    ) { Text(stringRes("open_accessibility")) }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipboardShareTargetsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onShareModeChange: (ClipboardShareMode) -> Unit,
    onTogglePeer: (String, Boolean) -> Unit
) {
    SettingsPageShell(
        title = stringRes("share_clipboard_with"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            if (state.clipboardShareMode == ClipboardShareMode.UNSET) {
                Text(
                    text = stringRes("choose_all_or_specific"),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(modifier = Modifier.selectableGroup()) {
                ClipboardShareModeRow(
                    title = stringRes("all_devices"),
                    subtitle = stringRes("broadcast_clipboard_wifi"),
                    selected = state.clipboardShareMode == ClipboardShareMode.ALL,
                    onClick = { onShareModeChange(ClipboardShareMode.ALL) }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                ClipboardShareModeRow(
                    title = stringRes("specific_devices"),
                    subtitle = stringRes("only_checked_devices"),
                    selected = state.clipboardShareMode == ClipboardShareMode.SPECIFIC,
                    onClick = { onShareModeChange(ClipboardShareMode.SPECIFIC) }
                )
            }
            if (state.clipboardShareMode == ClipboardShareMode.SPECIFIC) {
                if (state.clipboardPeers.isEmpty()) {
                    Text(
                        text = stringRes("no_paired_devices"),
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.clipboardPeers.forEach { peer ->
                        val checked = peer.deviceId in state.clipboardTargetDeviceIds
                        ListItem(
                            modifier = Modifier.padding(start = 16.dp),
                            headlineContent = { Text(peer.deviceName.ifBlank { stringRes("paired_device") }) },
                            supportingContent = {
                                val platform = peer.platform.ifBlank { peer.os }.ifBlank { null }
                                if (platform != null) {
                                    Text(platform)
                                }
                            },
                            trailingContent = {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { onTogglePeer(peer.deviceId, it) }
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipboardShizukuToggle(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    var snapshot by remember { mutableStateOf(ClipboardRuntimeDiagnostics.snapshot()) }
    LaunchedEffect(enabled) {
        while (true) {
            snapshot = ClipboardRuntimeDiagnostics.snapshot()
            delay(1_500)
        }
    }
    val supporting = when (
        ClipboardShizukuPolicy.toggleHint(
            optedIn = enabled,
            installed = snapshot.shizukuInstalled,
            running = snapshot.shizukuRunning,
            active = snapshot.shizukuActive
        )
    ) {
        ClipboardShizukuPolicy.ToggleHint.USING -> stringRes("shizuku_using")
        ClipboardShizukuPolicy.ToggleHint.CONNECTED_UNUSED -> stringRes("shizuku_connected_unused")
        ClipboardShizukuPolicy.ToggleHint.AUTHORIZE -> stringRes("shizuku_step_authorize")
        ClipboardShizukuPolicy.ToggleHint.START -> stringRes("shizuku_step_start")
        ClipboardShizukuPolicy.ToggleHint.SUBTITLE -> stringRes("shizuku_subtitle")
    }
    ListItem(
        headlineContent = { Text(stringRes("diag_shizuku_active"), softWrap = true) },
        supportingContent = { Text(supporting, softWrap = true) },
        trailingContent = {
            Switch(
                checked = enabled,
                onCheckedChange = onToggle
            )
        }
    )
}

@Composable
private fun ClipboardShareModeRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            ),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            RadioButton(
                selected = selected,
                onClick = onClick
            )
        }
    )
}

private fun clipboardShareTargetsSubtitle(state: SettingsUiState): String {
    return when (state.clipboardShareMode) {
        ClipboardShareMode.SPECIFIC -> AppI18n.t("specific_devices")
        ClipboardShareMode.ALL -> AppI18n.t("all_devices")
        ClipboardShareMode.UNSET -> AppI18n.t("choose_devices")
    }
}

private fun clipboardSettingsSubtitle(state: SettingsUiState): String {
    if (!state.clipboardSharingEnabled) return AppI18n.t("off")
    val mode = clipboardShareTargetsSubtitle(state)
    val extras = buildList {
        if (currentPlatformLabel() == "Android" && state.clipboardAccessibilityEnabled) add(AppI18n.t("accessibility"))
        if (currentPlatformLabel() == "Android" && state.clipboardShizukuEnabled) add(AppI18n.t("diag_shizuku_active"))
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
private fun localizedThemeName(theme: AppTheme): String = when (theme) {
    AppTheme.CLEAN -> stringRes("theme_clean")
    AppTheme.FLUX_GLASS -> stringRes("theme_flux")
    AppTheme.KINETIC_SPHERE -> stringRes("theme_kinetic")
}

@Composable
private fun localizedThemeDescription(theme: AppTheme): String = when (theme) {
    AppTheme.CLEAN -> stringRes("theme_clean_desc")
    AppTheme.FLUX_GLASS -> stringRes("theme_flux_desc")
    AppTheme.KINETIC_SPHERE -> stringRes("theme_kinetic_desc")
}

@Composable
private fun localizedUpdateUnit(unit: UpdateCheckUnit): String = when (unit) {
    UpdateCheckUnit.Hours -> stringRes("unit_hours")
    UpdateCheckUnit.Days -> stringRes("unit_days")
    UpdateCheckUnit.Weeks -> stringRes("unit_weeks")
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

private fun localizedPinIdle(timeout: PinIdleTimeout): String = when (timeout) {
    PinIdleTimeout.Immediate -> AppI18n.t("pin_idle_immediate")
    PinIdleTimeout.OneMinute -> AppI18n.t("pin_idle_1m")
    PinIdleTimeout.FiveMinutes -> AppI18n.t("pin_idle_5m")
    PinIdleTimeout.TenMinutes -> AppI18n.t("pin_idle_10m")
}

@Composable
private fun LanguageSettingsPage(
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit
) {
    val selected = AppI18n.locale
    SettingsPageShell(
        title = stringRes("language"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            AppLocale.entries.forEach { locale ->
                val label = AppI18n.languageRowLabel(locale)
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = locale == selected,
                            onClick = { persistAppLanguage(locale) },
                            role = Role.RadioButton
                        ),
                    headlineContent = {
                        Text(label, softWrap = true, modifier = Modifier.fillMaxWidth())
                    },
                    trailingContent = {
                        RadioButton(
                            selected = locale == selected,
                            onClick = { persistAppLanguage(locale) }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun RemoteFileDeletionSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onAllowRemoteFileDeletionChange: (Boolean) -> Unit
) {
    SettingsPageShell(
        title = stringRes("allow_remote_file_deletion"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text(stringRes("allow_remote_file_deletion"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("remote_delete_desc"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = state.allowRemoteFileDeletion,
                        onCheckedChange = onAllowRemoteFileDeletionChange
                    )
                }
            )
        }
    }
}

@Composable
private fun DriveRelaySettingsSection(
    state: SettingsUiState,
    onCellularChange: (Boolean) -> Unit,
    onDriveRelayChange: (Boolean) -> Unit,
    onDriveRelayMaxMbSelected: (DriveRelayMaxMb) -> Unit,
    onDriveAuthResult: (Boolean, String?) -> Unit,
    onPurgeChange: (Boolean) -> Unit,
    onPurgeNow: () -> Unit
) {
    val launchDriveAuth = rememberGoogleDriveAuthLauncher(onResult = onDriveAuthResult)
    var showDrivePermission by remember { mutableStateOf(false) }
    var relayLimitExpanded by remember { mutableStateOf(false) }
    val relayOn = state.googleDriveRelayEnabled && GoogleDriveAuth.hasGrant()

    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    FileApexPaneSectionHeader(title = stringRes("google_drive_relay"))
    ListItem(
        headlineContent = { Text(stringRes("google_drive_relay"), softWrap = true) },
        supportingContent = {
            Text(stringRes("drive_relay_desc"), softWrap = true)
        },
        trailingContent = {
            Switch(
                checked = relayOn,
                enabled = state.googleAccountLinkEnabled,
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        onDriveRelayChange(false)
                    } else if (GoogleDriveAuth.hasGrant()) {
                        onDriveRelayChange(true)
                    } else {
                        showDrivePermission = true
                    }
                }
            )
        }
    )
    if (relayOn) {
        ListItem(
            headlineContent = { Text(stringRes("cellular"), softWrap = true) },
            supportingContent = {
                Text(stringRes("drive_cellular_desc"), softWrap = true)
            },
            trailingContent = {
                Switch(
                    checked = state.cellularEnabled,
                    onCheckedChange = onCellularChange
                )
            }
        )
        ListItem(
            headlineContent = { Text(stringRes("relay_size_limit"), softWrap = true) },
            supportingContent = {
                Text(
                    stringRes("relay_size_limit_desc", stringRes("size_mb", DriveRelayMaxMb.DEFAULT.megabytes)),
                    softWrap = true
                )
            },
            trailingContent = {
                Box {
                    TextButton(onClick = { relayLimitExpanded = true }) {
                        Text(stringRes("size_mb", state.driveRelayMaxMb.megabytes))
                    }
                    DropdownMenu(
                        expanded = relayLimitExpanded,
                        onDismissRequest = { relayLimitExpanded = false }
                    ) {
                        DriveRelayMaxMb.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(stringRes("size_mb", option.megabytes)) },
                                onClick = {
                                    onDriveRelayMaxMbSelected(option)
                                    relayLimitExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        )
        ListItem(
            headlineContent = { Text(stringRes("purge_after_72h"), softWrap = true) },
            supportingContent = {
                Text(stringRes("purge_after_72h_desc"), softWrap = true)
            },
            trailingContent = {
                Switch(
                    checked = state.drivePurgeAfter72Hours,
                    onCheckedChange = onPurgeChange
                )
            }
        )
        ListItem(
            headlineContent = { Text(stringRes("delete_relay_now"), softWrap = true) },
            supportingContent = {
                Text(
                    state.drivePurgeNowMessage ?: stringRes("delete_relay_now_desc"),
                    softWrap = true
                )
            },
            trailingContent = {
                TextButton(
                    onClick = onPurgeNow,
                    enabled = GoogleDriveAuth.hasGrant() && !state.drivePurgeNowBusy
                ) {
                    Text(if (state.drivePurgeNowBusy) stringRes("deleting") else stringRes("delete"))
                }
            }
        )
    }
    state.googleDriveAuthError?.let { err ->
        Text(
            text = err,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    if (showDrivePermission) {
        GoogleDrivePermissionDialog(
            onGrant = {
                showDrivePermission = false
                launchDriveAuth()
            },
            onDismiss = { showDrivePermission = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceDetailsSettingsPage(
    preferences: DeviceDetailsDisplayPreferences,
    allowOverCellular: Boolean,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onBeforeAllowOverCellularEnabled: (onProceed: () -> Unit) -> Unit,
    onAllowOverCellularChange: (Boolean) -> Unit,
    onFieldVisibleChange: (DeviceDetailsFieldId, Boolean) -> Unit,
    onReorderFields: (fromIndex: Int, toIndex: Int) -> Unit,
    onReset: () -> Unit
) {
    val normalized = preferences.normalized()
    val fieldEntries = normalized.fields.mapNotNull { pref ->
        DeviceDetailsFieldId.entries.find { it.name == pref.id }?.let { id -> id to pref.visible }
    }
    val dragState = rememberDeviceOrderDragState()
    val density = LocalDensity.current
    val itemStridePx = with(density) { 72.dp.toPx() }
    val scrollState = rememberScrollState()
    val fieldIds = fieldEntries.map { it.first.name }

    DeviceOrderEdgeAutoScrollEffect(
        dragState = dragState,
        scrollState = scrollState,
        deviceIds = fieldIds,
        itemStridePx = itemStridePx,
        viewportHeightPx = 480,
        listOverflowsViewport = fieldEntries.size > 6
    )

    SettingsPageShell(
        title = stringRes("device_details"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            Text(
                text = stringRes("device_details_intro"),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ListItem(
                headlineContent = { Text(stringRes("allow_over_cellular"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("allow_over_cellular_desc"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = allowOverCellular,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                onBeforeAllowOverCellularEnabled {
                                    onAllowOverCellularChange(true)
                                }
                            } else {
                                onAllowOverCellularChange(false)
                            }
                        }
                    )
                }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            fieldEntries.forEachIndexed { index, (fieldId, visible) ->
                val visualOffsetPx = deviceOrderItemVisualOffsetPx(
                    index = index,
                    dragState = dragState,
                    itemCount = fieldEntries.size,
                    itemStridePx = itemStridePx
                )
                ListItem(
                    modifier = Modifier.offset { IntOffset(0, visualOffsetPx.roundToInt()) },
                    headlineContent = { Text(stringRes("field_${fieldId.name}"), softWrap = true) },
                    supportingContent = when {
                        fieldId.wifiOnly -> {
                            {
                                Text(stringRes("shown_when_wifi"), softWrap = true)
                            }
                        }
                        fieldId.cellularOnly -> {
                            {
                                Text(stringRes("shown_when_cellular"), softWrap = true)
                            }
                        }
                        else -> null
                    },
                    leadingContent = {
                        DeviceOrderDragHandle(
                            deviceId = fieldId.name,
                            startIndex = index,
                            itemCount = fieldEntries.size,
                            dragState = dragState,
                            itemStridePx = itemStridePx,
                            onReorder = onReorderFields
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = visible,
                            onCheckedChange = { enabled -> onFieldVisibleChange(fieldId, enabled) }
                        )
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = onReset,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Text(stringRes("reset_to_defaults"))
            }
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopLayoutSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onExpanded: (Boolean) -> Unit
) {
    SettingsPageShell(
        title = stringRes("desktop_layout"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text(stringRes("expanded_layout"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("expanded_layout_desc"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = state.desktopLayoutMode == DesktopLayoutMode.Expanded,
                        onCheckedChange = onExpanded
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WindowsDesignSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onFluent: (Boolean) -> Unit
) {
    SettingsPageShell(
        title = stringRes("windows_design"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text(stringRes("windows_11_modern"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("windows_fluent_desc"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = state.desktopUiStyle == DesktopUiStyle.WindowsFluent,
                        onCheckedChange = onFluent
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckForUpdatesSettingsPage(
    state: SettingsUiState,
    updateStatus: String?,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onUnitSelected: (UpdateCheckUnit) -> Unit,
    onAmountTextChange: (String) -> Unit,
    onWeekAmountSelected: (Int) -> Unit
) {
    val requestInstallUnknownAppsPermission = rememberRequestInstallUnknownAppsPermission()

    SettingsPageShell(
        title = stringRes("check_for_updates"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text(stringRes("enable_check_for_updates"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("check_updates_desc"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = state.checkForUpdatesEnabled,
                        onCheckedChange = { enabled ->
                            // BAL-safe: only open install-permission Settings from this user gesture.
                            if (enabled) {
                                requestInstallUnknownAppsPermission()
                            }
                            onToggle(enabled)
                        }
                    )
                }
            )
            if (state.checkForUpdatesEnabled) {
                UpdateFrequencyRow(
                    unit = state.checkForUpdatesIntervalUnit,
                    amountText = state.checkForUpdatesAmountText,
                    amount = state.checkForUpdatesIntervalAmount,
                    onUnitSelected = onUnitSelected,
                    onAmountTextChange = onAmountTextChange,
                    onWeekAmountSelected = onWeekAmountSelected
                )
                Text(
                    text = UpdateCheckFrequency.label(
                        state.checkForUpdatesIntervalUnit,
                        state.checkForUpdatesIntervalAmount
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                updateStatus?.let { status ->
                    Text(
                        text = status,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinRequiredSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onPinChange: (String) -> Unit,
    onIdleTimeoutSelected: (PinIdleTimeout) -> Unit
) {
    var timeoutExpanded by remember { mutableStateOf(false) }

    SettingsPageShell(
        title = stringRes("pin_required"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text(stringRes("require_pin"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("require_pin_desc"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = state.pinRequiredEnabled,
                        onCheckedChange = onToggle
                    )
                }
            )
            if (state.pinRequiredEnabled) {
                OutlinedTextField(
                    value = state.devicePin,
                    onValueChange = onPinChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    label = { Text(stringRes("device_pin")) },
                    supportingText = state.pinError?.let { err ->
                        {
                            Text(
                                text = err,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    isError = state.pinError != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
            }

            Text(
                text = stringRes("browse_unlock_idle"),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = stringRes("browse_unlock_idle_desc"),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                TextButton(onClick = { timeoutExpanded = true }) {
                    Text(localizedPinIdle(state.pinIdleTimeout))
                }
                DropdownMenu(
                    expanded = timeoutExpanded,
                    onDismissRequest = { timeoutExpanded = false }
                ) {
                    PinIdleTimeout.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(localizedPinIdle(option)) },
                            onClick = {
                                onIdleTimeoutSelected(option)
                                timeoutExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoogleAccountSettingsPage(
    state: SettingsUiState,
    linkStatus: String?,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onDisable: () -> Unit,
    onIdToken: (idToken: String?, email: String?, errorMessage: String?) -> Unit,
    onCellularChange: (Boolean) -> Unit,
    onDriveRelayChange: (Boolean) -> Unit,
    onDriveRelayMaxMbSelected: (DriveRelayMaxMb) -> Unit,
    onDriveAuthResult: (Boolean, String?) -> Unit,
    onPurgeChange: (Boolean) -> Unit,
    onPurgeNow: () -> Unit
) {
    val launchSignIn = rememberGoogleSignInLauncher(onResult = onIdToken)

    SettingsPageShell(
        title = stringRes("google_account"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text(stringRes("link_google_account"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("link_google_desc"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = state.googleAccountLinkEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                launchSignIn()
                            } else {
                                onDisable()
                            }
                        }
                    )
                }
            )
            if (state.googleAccountLinkEnabled && state.googleAccountEmail.isNotBlank()) {
                Text(
                    text = stringRes("linked_email", state.googleAccountEmail),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            linkStatus?.let { status ->
                Text(
                    text = status,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            state.googleAccountError?.let { err ->
                Text(
                    text = err,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (state.googleAccountLinkEnabled) {
                DriveRelaySettingsSection(
                    state = state,
                    onCellularChange = onCellularChange,
                    onDriveRelayChange = onDriveRelayChange,
                    onDriveRelayMaxMbSelected = onDriveRelayMaxMbSelected,
                    onDriveAuthResult = onDriveAuthResult,
                    onPurgeChange = onPurgeChange,
                    onPurgeNow = onPurgeNow
                )
            }
        }
    }
}

private fun googleAccountSubtitle(state: SettingsUiState): String {
    if (!state.googleAccountLinkEnabled) return AppI18n.t("google_unlinked")
    return if (state.googleDriveRelayEnabled) {
        "${AppI18n.t("google_linked")} · ${AppI18n.t("drive_relay")}"
    } else {
        AppI18n.t("google_linked")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPageShell(
    title: String,
    layoutMode: SettingsScreenLayoutMode,
    onBack: (() -> Unit)?,
    onOpenTransferQueue: (() -> Unit)? = null,
    content: @Composable (Modifier) -> Unit
) {
    val currentTheme = LocalAppTheme.current
    val isCustomGlass = currentTheme == AppTheme.FLUX_GLASS || currentTheme == AppTheme.KINETIC_SPHERE
    val containerColor = if (isCustomGlass) Color.Transparent else MaterialTheme.colorScheme.background

    when (layoutMode) {
        SettingsScreenLayoutMode.FullScreen -> {
            Scaffold(
                containerColor = containerColor,
                topBar = { SettingsTopBar(title = title, onBack = onBack) }
            ) { padding ->
                content(Modifier.fillMaxSize().padding(padding))
            }
        }

        SettingsScreenLayoutMode.ListPane -> {
            Column(modifier = Modifier.fillMaxSize()) {
                FileApexPaneSectionHeader(title = title, onBack = onBack)
                content(Modifier.weight(1f).fillMaxWidth())
            }
        }
        SettingsScreenLayoutMode.CompactShell -> {
            Column(modifier = Modifier.fillMaxSize()) {
                if (onBack != null) {
                    FileApexPaneSectionHeader(title = title, onBack = onBack)
                } else {
                    CompactHomeTitleBand(
                        primaryLine = "FileApex",
                        secondaryLine = title,
                        style = CompactHomeTitleStyle.Prominent,
                        onOpenTransferQueue = onOpenTransferQueue
                    )
                }
                content(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(title: String, onBack: (() -> Unit)?) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringRes("back")
                    )
                }
            }
        },
        colors = fileApexTopAppBarColors()
    )
}

@Composable
private fun ClipboardDiagnosticsEntry(
    accessibilityEnabled: Boolean,
    shizukuOptedIn: Boolean,
    recipientsChosen: Boolean,
    onOpenDiagnostics: () -> Unit
) {
    var snapshot by remember { mutableStateOf(ClipboardRuntimeDiagnostics.snapshot()) }
    LaunchedEffect(Unit) {
        while (true) {
            snapshot = ClipboardRuntimeDiagnostics.snapshot()
            delay(1_500)
        }
    }
    if (!ClipboardDiagnosticsPolicy.shouldShowEntry(sharingEnabled = true)) return
    val ready = ClipboardDiagnosticsPolicy.allRequiredGranted(
        ClipboardDiagnosticsPolicy.checks(
            sharingEnabled = true,
            recipientsChosen = recipientsChosen,
            accessibilitySettingEnabled = accessibilityEnabled,
            accessibilityListed = snapshot.accessibilityListed,
            accessibilityBound = snapshot.accessibilityBound,
            batteryWhitelisted = snapshot.batteryWhitelisted,
            notificationsEnabled = snapshot.notificationsEnabled,
            restrictedSettingsRelevant = snapshot.restrictedSettingsRelevant,
            restrictedSettingsBlocked = snapshot.restrictedSettingsBlocked,
            shizukuActive = snapshot.shizukuActive,
            shizukuOptedIn = shizukuOptedIn
        )
    )
    SettingsNavItem(
        title = stringRes("clipboard_diagnostics"),
        subtitle = if (ready) stringRes("diag_all_granted") else stringRes("diag_missing_required"),
        onClick = onOpenDiagnostics
    )
}

@Composable
private fun SettingsNavItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        headlineContent = { Text(title, softWrap = true) },
        supportingContent = { Text(subtitle, softWrap = true) },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@Composable
private fun UpdateFrequencyRow(
    unit: UpdateCheckUnit,
    amountText: String,
    amount: Int,
    onUnitSelected: (UpdateCheckUnit) -> Unit,
    onAmountTextChange: (String) -> Unit,
    onWeekAmountSelected: (Int) -> Unit
) {
    var unitExpanded by remember { mutableStateOf(false) }
    var weekExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringRes("check_every"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box {
                TextButton(onClick = { unitExpanded = true }) {
                    Text(localizedUpdateUnit(unit))
                }
                DropdownMenu(
                    expanded = unitExpanded,
                    onDismissRequest = { unitExpanded = false }
                ) {
                    UpdateCheckUnit.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(localizedUpdateUnit(option)) },
                            onClick = {
                                onUnitSelected(option)
                                unitExpanded = false
                            }
                        )
                    }
                }
            }
        }

        when (unit) {
            UpdateCheckUnit.Weeks -> {
                Column(modifier = Modifier.width(112.dp)) {
                    Text(
                        text = stringRes("unit_weeks"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box {
                        TextButton(onClick = { weekExpanded = true }) {
                            Text(amount.toString())
                        }
                        DropdownMenu(
                            expanded = weekExpanded,
                            onDismissRequest = { weekExpanded = false }
                        ) {
                            UpdateCheckFrequency.allowedWeekValues().forEach { weeks ->
                                DropdownMenuItem(
                                    text = { Text(weeks.toString()) },
                                    onClick = {
                                        onWeekAmountSelected(weeks)
                                        weekExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
            UpdateCheckUnit.Hours -> {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = onAmountTextChange,
                    modifier = Modifier.width(112.dp),
                    singleLine = true,
                    label = { Text(stringRes("range_1_24")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            UpdateCheckUnit.Days -> {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = onAmountTextChange,
                    modifier = Modifier.width(112.dp),
                    singleLine = true,
                    label = { Text(stringRes("range_1_30")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemesSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onSelectTheme: (AppTheme) -> Unit,
    onToggleConnectedLines: (Boolean) -> Unit,
    onToggleOrbitalRings: (Boolean) -> Unit
) {
    SettingsPageShell(
        title = stringRes("themes"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            FileApexPaneSectionHeader(title = stringRes("app_theme"))

            Text(
                text = stringRes("themes_intro"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            val isCustomTheme = isFileApexCustomGlassTheme()
            AppTheme.entries.forEach { theme ->
                val selected = state.appTheme == theme
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectTheme(theme) },
                    color = if (isCustomTheme) {
                        if (selected) Color(0x3300E676) else Color(0x221E2D34)
                    } else {
                        if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    },
                    border = BorderStroke(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (isCustomTheme) {
                            if (selected) Color(0xFF00E676) else Color.White.copy(alpha = 0.2f)
                        } else {
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        }
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = localizedThemeName(theme),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isCustomTheme) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                                if (theme == AppTheme.CLEAN) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = if (isCustomTheme) Color(0x44FFFFFF) else MaterialTheme.colorScheme.secondaryContainer,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = stringRes("default_badge"),
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            color = if (isCustomTheme) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = localizedThemeDescription(theme),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isCustomTheme) Color(0xFFCBD5E1) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        RadioButton(
                            selected = selected,
                            onClick = { onSelectTheme(theme) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = if (isCustomTheme) Color(0xFF00E676) else MaterialTheme.colorScheme.primary,
                                unselectedColor = if (isCustomTheme) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }

            if (state.appTheme == AppTheme.KINETIC_SPHERE) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                FileApexPaneSectionHeader(title = stringRes("kinetic_sphere_elements"))

                ListItem(
                    headlineContent = { Text(stringRes("connected_device_lines"), softWrap = true) },
                    supportingContent = {
                        Text(stringRes("theme_lines_desc"), softWrap = true)
                    },
                    trailingContent = {
                        Switch(
                            checked = state.kineticSphereConnectedLinesEnabled,
                            onCheckedChange = onToggleConnectedLines
                        )
                    }
                )

                ListItem(
                    headlineContent = { Text(stringRes("orbital_background_rings"), softWrap = true) },
                    supportingContent = {
                        Text(stringRes("theme_rings_desc"), softWrap = true)
                    },
                    trailingContent = {
                        Switch(
                            checked = state.kineticSphereOrbitalRingsEnabled,
                            onCheckedChange = onToggleOrbitalRings
                        )
                    }
                )
            }
        }
    }
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
    val isFluxGlass = LocalAppTheme.current == AppTheme.FLUX_GLASS
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

