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
import com.fileapex.platform.FileApexBackHandler
import com.fileapex.platform.supportsWindowsFluentDesign
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.util.TimeUtils
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
    DeviceDetails,
    GoogleAccount,
    RemoteFileDeletion,
    DesktopLayout,
    WindowsDesign
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
        if (page == SettingsPage.Root) {
            if (showRootBackNavigation) onBack()
        } else {
            page = SettingsPage.Root
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
            onShareModeChange = viewModel::setClipboardShareMode,
            onTogglePeer = viewModel::setClipboardTargetDevice,
            onToggleViaCellular = viewModel::setClipboardViaCellular,
            onToggleAccessibility = viewModel::setClipboardAccessibility,
            onDismissRestrictedHelp = viewModel::dismissAccessibilityRestrictedHelp,
            onOpenAppInfo = viewModel::openAccessibilityAppInfo,
            onOpenAccessibilitySettings = viewModel::openAccessibilitySystemSettings
        )
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
        title = "Settings",
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
                    title = "System & App Performance",
                    expanded = state.systemPerformanceExpanded,
                    onToggle = onToggleSystemPerformanceGroup
                ) {
                    SettingsNavItem(
                        title = "Check for Updates",
                        subtitle = if (state.checkForUpdatesEnabled) {
                            UpdateCheckFrequency.label(
                                state.checkForUpdatesIntervalUnit,
                                state.checkForUpdatesIntervalAmount
                            )
                        } else {
                            "Off"
                        },
                        onClick = onOpenCheckForUpdates
                    )
                    SettingsNavItem(
                        title = "Background Persistence",
                        subtitle = backgroundPersistenceSubtitle(
                            watchdogEnabled = state.enableServiceWatchdog,
                            backgroundPersistence = backgroundPersistence,
                            exactAlarmWarningActive = exactAlarmWarningActive
                        ),
                        onClick = onOpenBackgroundPersistence
                    )
                    if (!usesDesktopFileSelection()) {
                        SettingsNavItem(
                            title = "Auto launch on reboot",
                            subtitle = if (state.autoLaunchOnReboot) "On" else "Off",
                            onClick = onOpenAutoLaunchOnReboot
                        )
                    }
                }

                SettingsCategoryGroup(
                    title = "Appearance & Behavior",
                    expanded = state.appearanceBehaviorExpanded,
                    onToggle = onToggleAppearanceBehaviorGroup
                ) {
                    SettingsNavItem(
                        title = "Themes",
                        subtitle = state.appTheme.displayName,
                        onClick = onOpenThemes
                    )
                    SettingsNavItem(
                        title = "Notifications",
                        subtitle = if (
                            state.notesNotificationsEnabled ||
                            state.driveRelayNotificationsEnabled ||
                            state.fileTransferNotificationsEnabled ||
                            state.liveTransferCapsuleEnabled
                        ) "On" else "Off",
                        onClick = onOpenNotifications
                    )
                    SettingsNavItem(
                        title = "Clipboard",
                        subtitle = clipboardSettingsSubtitle(state),
                        onClick = onOpenClipboard
                    )
                    SettingsNavItem(
                        title = "Device Details",
                        subtitle = "Peer telemetry fields",
                        onClick = onOpenDeviceDetails
                    )
                    if (usesDesktopFileSelection()) {
                        SettingsNavItem(
                            title = "Desktop Layout",
                            subtitle = state.desktopLayoutMode.label,
                            onClick = onOpenDesktopLayout
                        )
                    }
                    if (supportsWindowsFluentDesign()) {
                        SettingsNavItem(
                            title = "Windows Design",
                            subtitle = state.desktopUiStyle.label,
                            onClick = onOpenWindowsDesign
                        )
                    }
                }

                SettingsCategoryGroup(
                    title = "Security & Account",
                    expanded = state.securityAccountExpanded,
                    onToggle = onToggleSecurityAccountGroup
                ) {
                    SettingsNavItem(
                        title = "PIN required",
                        subtitle = buildString {
                            append(if (state.pinRequiredEnabled) "On" else "Off")
                            append(" · Browse unlock: ")
                            append(state.pinIdleTimeout.label)
                        },
                        onClick = onOpenPinRequired
                    )
                    SettingsNavItem(
                        title = "Google Account",
                        subtitle = googleAccountSubtitle(state),
                        onClick = onOpenGoogleAccount
                    )
                    SettingsNavItem(
                        title = "Allow remote file deletion",
                        subtitle = if (state.allowRemoteFileDeletion) "On" else "Off",
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
    val status = if (watchdogEnabled) "On" else "Off"
    val warnings = buildList {
        if (backgroundPersistence.backgroundRestricted) add("background restricted")
        if (backgroundPersistence.batteryOptimizationRestricted) add("battery optimized")
        if (backgroundPersistence.unusedAppRestrictionsActive) add("hibernation on")
        if (exactAlarmWarningActive) add("alarms off")
    }
    return if (warnings.isEmpty()) {
        status
    } else {
        "$status · ${warnings.joinToString(", ")}"
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
        title = "Background Persistence",
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text("Service watchdog") },
                supportingContent = {
                    Text(
                        "Enable background watchdog to automatically restart the FileApex " +
                            "file server daemon if aggressive OEM battery management " +
                            "terminates it in the background. Peer UDP wake only works while " +
                            "the share-server notification is active."
                    )
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
                    headlineContent = { Text("Background running restricted") },
                    supportingContent = {
                        Text(
                            backgroundPersistence.oemGuidance?.appBatteryUsageSteps?.let { steps ->
                                "Android is blocking FileApex from running in the background. " +
                                    "Tap to open system settings, then choose: $steps"
                            } ?: (
                                "Android is blocking FileApex from running in the background. " +
                                    "Tap to open App battery usage and set FileApex to " +
                                    "Unrestricted or Always allow."
                                )
                        )
                    },
                    modifier = Modifier.clickable { onOpenAppBatteryUsageSettings() }
                )
            }
            if (backgroundPersistence.batteryOptimizationRestricted) {
                ListItem(
                    headlineContent = { Text("Battery optimization active") },
                    supportingContent = {
                        Text(
                            "FileApex is not exempt from classic battery optimization. Tap to " +
                                "request unrestricted battery. On many phones you must also set " +
                                "App battery usage to Unrestricted or Always allow."
                        )
                    },
                    modifier = Modifier.clickable { onRequestBatteryUnrestricted() }
                )
            }
            if (backgroundPersistence.unusedAppRestrictionsActive) {
                ListItem(
                    headlineContent = { Text("Pause app activity if unused") },
                    supportingContent = {
                        Text(
                            "Android may hibernate FileApex when you have not opened it recently. " +
                                "Tap to turn off this restriction so the share server stays running " +
                                "overnight."
                        )
                    },
                    modifier = Modifier.clickable { onOpenUnusedAppRestrictionsSettings() }
                )
            }
            backgroundPersistence.oemGuidance?.let { guidance ->
                if (backgroundPersistence.persistenceRestricted ||
                    backgroundPersistence.unusedAppRestrictionsActive
                ) {
                    ListItem(
                        headlineContent = { Text("${guidance.vendorLabel} setup") },
                        supportingContent = {
                            Text(
                                buildString {
                                    append(guidance.appBatteryUsageSteps)
                                    append('.')
                                    guidance.autoStartHint?.let { hint ->
                                        append(' ')
                                        append(hint)
                                    }
                                }
                            )
                        },
                        modifier = Modifier.clickable { onOpenBackgroundPersistenceSettings() }
                    )
                }
            }
            if (exactAlarmWarningActive) {
                ListItem(
                    headlineContent = { Text("Exact alarms disabled") },
                    supportingContent = {
                        Text(
                            "Alarms & reminders permission is off. The service watchdog may " +
                                "fire late or miss restarts after OEM kills. Tap to open " +
                                "system alarm settings and allow FileApex."
                        )
                    },
                    modifier = Modifier.clickable { onOpenExactAlarmSettings() }
                )
            }
            ListItem(
                headlineContent = { Text("System app settings") },
                supportingContent = {
                    Text(
                        "Opens FileApex in Android app settings. After an app update, open " +
                            "FileApex once so the share server can restart."
                    )
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
        title = "Auto launch on reboot",
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text("Start share server after reboot") },
                supportingContent = {
                    Text(
                        "When on, FileApex starts its share server automatically after your " +
                            "device finishes rebooting. Off leaves the server stopped until you " +
                            "open the app."
                    )
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
        title = "Notifications",
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            FileApexPaneSectionHeader(title = "Notifications")

            ListItem(
                headlineContent = { Text("Bulletin Board") },
                supportingContent = {
                    Text(
                        "Show a notification when new shared messages, files, or alerts arrive from paired devices."
                    )
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
                headlineContent = { Text("Drive Relay") },
                supportingContent = {
                    Text(
                        if (driveRelayReady) {
                            "Alerts when FileApex posts or retrieves files through Google Drive Relay."
                        } else {
                            "Turns on after Google Drive Relay is enabled under Google Account."
                        }
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
                headlineContent = { Text("File Transfer") },
                supportingContent = {
                    Text("Show a notification after files are successfully received from paired devices.")
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
                    headlineContent = { Text("Live Activity") },
                    supportingContent = {
                        Text("Shows progress of active file transfers in a floating capsule. Queued items use the header clock icon.")
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
        title = "File Transfer Notifications",
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text("Show receive notifications") },
                supportingContent = {
                    Text(
                        "When on, this device shows a notification after files are received " +
                            "successfully (includes filenames). Off keeps transfers silent. " +
                            "Applies only when receiving, not when sending. Default is off."
                    )
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
    onShareModeChange: (ClipboardShareMode) -> Unit,
    onTogglePeer: (String, Boolean) -> Unit,
    onToggleViaCellular: (Boolean) -> Unit,
    onToggleAccessibility: (Boolean) -> Unit,
    onDismissRestrictedHelp: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    val isAndroid = currentPlatformLabel() == "Android"
    SettingsPageShell(
        title = "Clipboard",
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text("Clipboard Sharing") },
                supportingContent = {
                    Text("Enable or disable clipboard syncing. Payloads are encrypted before they leave this device.")
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
                        headlineContent = { Text("Accessibility") },
                        supportingContent = {
                            Text("Allows background clipboard detection for auto-sync.")
                        },
                        trailingContent = {
                            Switch(
                                checked = state.clipboardAccessibilityEnabled,
                                onCheckedChange = onToggleAccessibility
                            )
                        }
                    )
                    ListItem(
                        headlineContent = { Text("Via Cellular") },
                        supportingContent = {
                            Text("Allows syncing over cellular using secure cloud relay.")
                        },
                        trailingContent = {
                            Switch(
                                checked = state.clipboardViaCellularEnabled,
                                onCheckedChange = onToggleViaCellular
                            )
                        }
                    )
                }
                FileApexPaneSectionHeader(title = "Share clipboard with:")
                if (state.clipboardShareMode == ClipboardShareMode.UNSET) {
                    Text(
                        text = "Choose All devices or Specific devices. Clipboard is not sent until you pick one.",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(modifier = Modifier.selectableGroup()) {
                    ClipboardShareModeRow(
                        title = "All devices",
                        subtitle = "Broadcast clipboard to paired peers on the same Wi-Fi.",
                        selected = state.clipboardShareMode == ClipboardShareMode.ALL,
                        onClick = { onShareModeChange(ClipboardShareMode.ALL) }
                    )
                    ClipboardShareModeRow(
                        title = "Specific devices",
                        subtitle = "Only the devices you check below.",
                        selected = state.clipboardShareMode == ClipboardShareMode.SPECIFIC,
                        onClick = { onShareModeChange(ClipboardShareMode.SPECIFIC) }
                    )
                }
                if (state.clipboardShareMode == ClipboardShareMode.SPECIFIC) {
                    if (state.clipboardPeers.isEmpty()) {
                        Text(
                            text = "No paired devices yet.",
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        state.clipboardPeers.forEach { peer ->
                            val checked = peer.deviceId in state.clipboardTargetDeviceIds
                            ListItem(
                                modifier = Modifier.padding(start = 16.dp),
                                headlineContent = { Text(peer.deviceName.ifBlank { "Paired device" }) },
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
        if (state.showAccessibilityRestrictedHelp) {
            AlertDialog(
                onDismissRequest = onDismissRestrictedHelp,
                title = { Text("Allow restricted settings") },
                text = {
                    Text(
                        "Android is blocking Accessibility for this sideloaded build. " +
                            "Open App Info, tap the ⋮ menu, then Allow restricted settings. " +
                            "After that, turn on FileApex clipboard in Accessibility."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onOpenAppInfo()
                            onDismissRestrictedHelp()
                        }
                    ) { Text("Open App Info") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            onOpenAccessibilitySettings()
                            onDismissRestrictedHelp()
                        }
                    ) { Text("Open Accessibility") }
                }
            )
        }
    }
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

private fun clipboardSettingsSubtitle(state: SettingsUiState): String {
    if (!state.clipboardSharingEnabled) return "Off"
    val mode = when (state.clipboardShareMode) {
        ClipboardShareMode.SPECIFIC -> "Specific devices"
        ClipboardShareMode.ALL -> "All devices"
        ClipboardShareMode.UNSET -> "Choose devices"
    }
    val extras = buildList {
        if (currentPlatformLabel() == "Android" && state.clipboardAccessibilityEnabled) add("Accessibility")
        if (state.clipboardViaCellularEnabled && currentPlatformLabel() == "Android") add("Cellular")
    }
    return if (extras.isEmpty()) "On · $mode" else "On · $mode · ${extras.joinToString(" · ")}"
}

@Composable
private fun RemoteFileDeletionSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onAllowRemoteFileDeletionChange: (Boolean) -> Unit
) {
    SettingsPageShell(
        title = "Allow remote file deletion",
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text("Allow remote file deletion") },
                supportingContent = {
                    Text(
                        "This allows the Bulletin Board to \"delete all\" for files in local storage remotely."
                    )
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
    FileApexPaneSectionHeader(title = "Google Drive Relay")
    ListItem(
        headlineContent = { Text("Google Drive Relay") },
        supportingContent = {
            Text(
                "Store relayed files in a FileApex Relay folder on your Google Drive. " +
                    "Works on Wi‑Fi. FileApex cannot see your other Drive files. Desktop has no FCM, so it " +
                    "checks that folder on launch and every 15 minutes when off Wi‑Fi."
            )
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
            headlineContent = { Text("Cellular") },
            supportingContent = {
                Text(
                    "Also use Google Drive Relay when this device is off local Wi‑Fi. " +
                        "Separate from clipboard Via Cellular."
                )
            },
            trailingContent = {
                Switch(
                    checked = state.cellularEnabled,
                    onCheckedChange = onCellularChange
                )
            }
        )
        ListItem(
            headlineContent = { Text("Relay size limit") },
            supportingContent = {
                Text(
                    "Max size for one Google Drive Relay send — a single file, or a selected " +
                        "group at once. Default is ${DriveRelayMaxMb.DEFAULT.label}."
                )
            },
            trailingContent = {
                Box {
                    TextButton(onClick = { relayLimitExpanded = true }) {
                        Text(state.driveRelayMaxMb.label)
                    }
                    DropdownMenu(
                        expanded = relayLimitExpanded,
                        onDismissRequest = { relayLimitExpanded = false }
                    ) {
                        DriveRelayMaxMb.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
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
            headlineContent = { Text("Purge File(s) after 72 hours") },
            supportingContent = {
                Text(
                    "Delete unpinned Drive relay files 72 hours after upload. Direct " +
                        "transfers are also removed as soon as the destination device retrieves them."
                )
            },
            trailingContent = {
                Switch(
                    checked = state.drivePurgeAfter72Hours,
                    onCheckedChange = onPurgeChange
                )
            }
        )
        ListItem(
            headlineContent = { Text("Delete relay files now") },
            supportingContent = {
                Text(
                    state.drivePurgeNowMessage
                        ?: "Remove every file in FileApex Relay immediately, including " +
                        "uploads that never downloaded. Does not wait 72 hours."
                )
            },
            trailingContent = {
                TextButton(
                    onClick = onPurgeNow,
                    enabled = GoogleDriveAuth.hasGrant() && !state.drivePurgeNowBusy
                ) {
                    Text(if (state.drivePurgeNowBusy) "Deleting…" else "Delete")
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
        title = "Device Details",
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            Text(
                text = "Configure which telemetry fields appear when you open Device Details " +
                    "for a paired device. Wi-Fi and cellular rows are shown only when the peer " +
                    "is on that network type.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ListItem(
                headlineContent = { Text("Allow over cellular") },
                supportingContent = {
                    Text(
                        "When off-LAN, fetch or share encrypted Device Details via Firebase. " +
                            "Both devices must enable this and link a Google Account. " +
                            "Same Wi-Fi still uses local network (not encrypted)."
                    )
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
                    headlineContent = { Text(fieldId.label) },
                    supportingContent = when {
                        fieldId.wifiOnly -> {
                            {
                                Text("Shown when peer is on Wi-Fi")
                            }
                        }
                        fieldId.cellularOnly -> {
                            {
                                Text("Shown when peer is on cellular")
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
                Text("Reset to defaults")
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
        title = "Desktop Layout",
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text("Expanded layout") },
                supportingContent = {
                    Text(
                        "When on, always uses the adaptive multi-pane layout with navigation " +
                            "rail and list-detail, regardless of window size. When off, uses " +
                            "the compact single-column layout. Default is Compact."
                    )
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
        title = "Windows Design",
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text("Windows 11 Modern") },
                supportingContent = {
                    Text(
                        "Standard keeps the cross-platform look (same as Android). " +
                            "Modern uses native Windows styling and a Mica title bar. " +
                            "Saved on this PC; applies immediately."
                    )
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
        title = "Check for Updates",
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text("Enable Check for Updates") },
                supportingContent = {
                    Text(
                        "When on, FileApex checks GitHub Releases on your schedule and " +
                            "installs newer builds for this platform. Default is off."
                    )
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
        title = "PIN required",
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text("Require PIN") },
                supportingContent = {
                    Text(
                        "When on, other devices must enter this device's PIN to pair and to " +
                            "browse files. Sending files to this device does not require PIN. " +
                            "Default is off. Only this device stores the PIN."
                    )
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
                    label = { Text("Device PIN (4–8 digits)") },
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
                text = "Browse unlock idle timeout",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "How long this device stays unlocked when browsing a PIN-protected peer. " +
                    "Returning to the device list always re-locks. Default is 5 Minutes.",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                TextButton(onClick = { timeoutExpanded = true }) {
                    Text(state.pinIdleTimeout.label)
                }
                DropdownMenu(
                    expanded = timeoutExpanded,
                    onDismissRequest = { timeoutExpanded = false }
                ) {
                    PinIdleTimeout.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
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
        title = "Google Account",
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text("Link Google Account") },
                supportingContent = {
                    Text(
                        "Opt-in only. Signs in with Google and registers this device’s public ID " +
                            "and LAN address in your private Firebase registry so other FileApex " +
                            "apps on the same account can discover you. Files are uploaded only " +
                            "when Google Drive Relay is also enabled."
                    )
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
                    text = "Linked: ${state.googleAccountEmail}",
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
    if (!state.googleAccountLinkEnabled) return "Not Connected"
    return if (state.googleDriveRelayEnabled) {
        "Connected · Drive Relay"
    } else {
        "Connected"
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
                        contentDescription = "Back"
                    )
                }
            }
        },
        colors = fileApexTopAppBarColors()
    )
}

@Composable
private fun SettingsNavItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
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
                text = "Check every",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box {
                TextButton(onClick = { unitExpanded = true }) {
                    Text(unit.name)
                }
                DropdownMenu(
                    expanded = unitExpanded,
                    onDismissRequest = { unitExpanded = false }
                ) {
                    UpdateCheckUnit.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.name) },
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
                        text = "Weeks",
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
                    label = { Text("1–24") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            UpdateCheckUnit.Days -> {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = onAmountTextChange,
                    modifier = Modifier.width(112.dp),
                    singleLine = true,
                    label = { Text("1–30") },
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
        title = "Themes",
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            FileApexPaneSectionHeader(title = "App Theme")

            Text(
                text = "Select your preferred visual style for FileApex. Changes apply immediately across all screens and windows.",
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
                                    text = theme.displayName,
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
                                            text = "DEFAULT",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            color = if (isCustomTheme) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = theme.description,
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
                FileApexPaneSectionHeader(title = "Kinetic Sphere Elements")

                ListItem(
                    headlineContent = { Text("Connected Device Lines") },
                    supportingContent = {
                        Text(
                            "Draw dashed spoke connector lines from the central hub to each device node."
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = state.kineticSphereConnectedLinesEnabled,
                            onCheckedChange = onToggleConnectedLines
                        )
                    }
                )

                ListItem(
                    headlineContent = { Text("Orbital Background Rings") },
                    supportingContent = {
                        Text(
                            "Draw 3D elliptical orbital rings in deep space around the central hub."
                        )
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
            contentDescription = if (expanded) "Collapse $title" else "Expand $title",
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

