package com.omninode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.omninode.data.settings.PinIdleTimeout
import com.omninode.data.settings.DesktopLayoutMode
import com.omninode.data.settings.UpdateCheckFrequency
import com.omninode.data.settings.UpdateCheckUnit
import com.omninode.platform.OmniBackHandler
import com.omninode.platform.usesDesktopFileSelection
import com.omninode.util.TimeUtils
import com.omninode.platform.rememberGoogleSignInLauncher
import com.omninode.presentation.SettingsUiState
import com.omninode.presentation.SettingsViewModel
import com.omninode.ui.adaptive.OmniPaneSectionHeader
import com.omninode.ui.theme.OmniTeal
import com.omninode.update.rememberRequestInstallUnknownAppsPermission

private enum class SettingsPage {
    Root,
    CheckForUpdates,
    PinRequired,
    BackgroundPersistence,
    FileTransferNotifications,
    GoogleAccount,
    DesktopLayout
}

enum class SettingsScreenLayoutMode {
    /** Phone / compact: teal top bar scaffold. */
    FullScreen,
    /** Wide navigation rail: white pane header matching Devices list pane. */
    ListPane
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
    batteryOptimizationRestricted: Boolean = false,
    onRequestBatteryUnrestricted: () -> Unit = {},
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

    OmniBackHandler(
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
            onOpenFileTransferNotifications = { page = SettingsPage.FileTransferNotifications },
            onOpenGoogleAccount = { page = SettingsPage.GoogleAccount },
            onOpenDesktopLayout = { page = SettingsPage.DesktopLayout },
            onVersionNumberEasterEgg = viewModel::onVersionNumberEasterEgg,
            batteryOptimizationRestricted = batteryOptimizationRestricted,
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
            batteryOptimizationRestricted = batteryOptimizationRestricted,
            onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
            exactAlarmWarningActive = exactAlarmWarningActive,
            onOpenExactAlarmSettings = onOpenExactAlarmSettings,
            onOpenAppDetailsSettings = onOpenAppDetailsSettings
        )
        SettingsPage.FileTransferNotifications -> FileTransferNotificationsSettingsPage(
            state = state,
            layoutMode = layoutMode,
            onBack = { page = SettingsPage.Root },
            onToggle = viewModel::setFileTransferNotifications
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
    onOpenFileTransferNotifications: () -> Unit,
    onOpenGoogleAccount: () -> Unit,
    onOpenDesktopLayout: () -> Unit,
    onVersionNumberEasterEgg: () -> Unit,
    batteryOptimizationRestricted: Boolean,
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
                        batteryOptimizationRestricted = batteryOptimizationRestricted,
                        exactAlarmWarningActive = exactAlarmWarningActive
                    ),
                    onClick = onOpenBackgroundPersistence
                )
                SettingsNavItem(
                    title = "File Transfer Notifications",
                    subtitle = if (state.fileTransferNotificationsEnabled) "On" else "Off",
                    onClick = onOpenFileTransferNotifications
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
            }
            Text(
                text = "OmniNode v$appVersionName",
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
    batteryOptimizationRestricted: Boolean,
    exactAlarmWarningActive: Boolean
): String {
    val status = if (watchdogEnabled) "On" else "Off"
    val warnings = buildList {
        if (batteryOptimizationRestricted) add("battery restricted")
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
    batteryOptimizationRestricted: Boolean,
    onRequestBatteryUnrestricted: () -> Unit,
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
                        "Enable background watchdog to automatically restart the OmniNode " +
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
            if (batteryOptimizationRestricted) {
                ListItem(
                    headlineContent = { Text("Battery optimization active") },
                    supportingContent = {
                        Text(
                            "OmniNode is not exempt from battery restrictions. Background " +
                                "file sharing may stop until you open the app again. Tap to " +
                                "request unrestricted battery for best reliability."
                        )
                    },
                    modifier = Modifier.clickable { onRequestBatteryUnrestricted() }
                )
            }
            if (exactAlarmWarningActive) {
                ListItem(
                    headlineContent = { Text("Exact alarms disabled") },
                    supportingContent = {
                        Text(
                            "Alarms & reminders permission is off. The service watchdog may " +
                                "fire late or miss restarts after OEM kills. Tap to open " +
                                "system alarm settings and allow OmniNode."
                        )
                    },
                    modifier = Modifier.clickable { onOpenExactAlarmSettings() }
                )
            }
            ListItem(
                headlineContent = { Text("OEM auto-start & updates") },
                supportingContent = {
                    Text(
                        "On Motorola, Oppo, Xiaomi, and similar phones, also enable " +
                            "auto-launch / background activity for OmniNode in the system " +
                            "battery or app-management screens. After an app update, open " +
                            "OmniNode once so the share server can restart. Tap to open " +
                            "OmniNode’s system app settings."
                    )
                },
                modifier = Modifier.clickable { onOpenAppDetailsSettings() }
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
                        "When on, OmniNode checks GitHub Releases on your schedule and " +
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
                            "and LAN address in your private Firebase registry so other OmniNode " +
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
                OmniPaneSectionHeader(title = title, onBack = onBack)
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
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = OmniTeal,
            titleContentColor = MaterialTheme.colorScheme.onPrimary,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
        )
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
