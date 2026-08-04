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
import com.fileapex.domain.diagnostics.DeviceDetailsDisplayPreferences
import com.fileapex.domain.diagnostics.DeviceDetailsFieldId
import kotlin.math.roundToInt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.fileapex.data.settings.UpdateCheckFrequency
import com.fileapex.data.settings.UpdateCheckUnit
import com.fileapex.platform.BackgroundPersistenceUiState
import com.fileapex.platform.FileApexBackHandler
import com.fileapex.platform.supportsWindowsFluentDesign
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.util.TimeUtils
import com.fileapex.platform.rememberGoogleSignInLauncher
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
    FileTransferNotifications,
    DeviceDetails,
    GoogleAccount,
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
            onOpenFileTransferNotifications = { page = SettingsPage.FileTransferNotifications },
            onOpenDeviceDetails = { page = SettingsPage.DeviceDetails },
            onOpenGoogleAccount = { page = SettingsPage.GoogleAccount },
            onOpenDesktopLayout = { page = SettingsPage.DesktopLayout },
            onOpenWindowsDesign = { page = SettingsPage.WindowsDesign },
            onVersionNumberEasterEgg = viewModel::onVersionNumberEasterEgg,
            backgroundPersistence = backgroundPersistence,
            exactAlarmWarningActive = exactAlarmWarningActive
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
            onBack = { page = SettingsPage.Root },
            onToggle = viewModel::setFileTransferNotifications
        )
        SettingsPage.DeviceDetails -> DeviceDetailsSettingsPage(
            preferences = state.deviceDetailsDisplayPreferences,
            allowOverCellular = state.deviceDetailsAllowOverCellular,
            layoutMode = layoutMode,
            onBack = { page = SettingsPage.Root },
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
            onIdToken = viewModel::onGoogleIdToken
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
    onOpenFileTransferNotifications: () -> Unit,
    onOpenDeviceDetails: () -> Unit,
    onOpenGoogleAccount: () -> Unit,
    onOpenDesktopLayout: () -> Unit,
    onOpenWindowsDesign: () -> Unit,
    onVersionNumberEasterEgg: () -> Unit,
    backgroundPersistence: BackgroundPersistenceUiState,
    exactAlarmWarningActive: Boolean
) {
    var versionTapCount by remember { mutableIntStateOf(0) }
    var lastVersionTapEpochMs by remember { mutableLongStateOf(0L) }
    val versionTapInteraction = remember { MutableInteractionSource() }

    SettingsPageShell(
        title = "Settings",
        layoutMode = layoutMode,
        onBack = onBack.takeIf { showBackNavigation }
    ) { contentModifier ->
        Box(modifier = contentModifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 48.dp)
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
                    title = "PIN required",
                    subtitle = buildString {
                        append(if (state.pinRequiredEnabled) "On" else "Off")
                        append(" · Browse unlock: ")
                        append(state.pinIdleTimeout.label)
                    },
                    onClick = onOpenPinRequired
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
                SettingsNavItem(
                    title = "File Transfer Notifications",
                    subtitle = if (state.fileTransferNotificationsEnabled) "On" else "Off",
                    onClick = onOpenFileTransferNotifications
                )
                SettingsNavItem(
                    title = "Device Details",
                    subtitle = "Peer telemetry fields",
                    onClick = onOpenDeviceDetails
                )
                SettingsNavItem(
                    title = "Google Account",
                    subtitle = when {
                        !state.googleAccountLinkEnabled -> "Off"
                        state.googleAccountEmail.isNotBlank() -> state.googleAccountEmail
                        else -> "On"
                    },
                    onClick = onOpenGoogleAccount
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
private fun DeviceDetailsSettingsPage(
    preferences: DeviceDetailsDisplayPreferences,
    allowOverCellular: Boolean,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
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
                        onCheckedChange = onAllowOverCellularChange
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
    onIdToken: (idToken: String?, email: String?, errorMessage: String?) -> Unit
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
                            "apps on the same account can discover you. No files or folder " +
                            "contents are ever uploaded."
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
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPageShell(
    title: String,
    layoutMode: SettingsScreenLayoutMode,
    onBack: (() -> Unit)?,
    content: @Composable (Modifier) -> Unit
) {
    when (layoutMode) {
        SettingsScreenLayoutMode.FullScreen -> {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
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
                        style = CompactHomeTitleStyle.Prominent
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

private const val VERSION_EASTER_EGG_TAP_COUNT = 5
private const val VERSION_EASTER_EGG_TAP_WINDOW_MS = 2_000L
