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
import com.omninode.data.settings.UpdateCheckFrequency
import com.omninode.data.settings.UpdateCheckUnit
import com.omninode.platform.OmniBackHandler
import com.omninode.platform.currentTimeMillis
import com.omninode.platform.rememberGoogleSignInLauncher
import com.omninode.presentation.SettingsUiState
import com.omninode.presentation.SettingsViewModel
import com.omninode.ui.theme.OmniTeal
import com.omninode.update.rememberRequestInstallUnknownAppsPermission

private enum class SettingsPage {
    Root,
    CheckForUpdates,
    PinRequired,
    GoogleAccount
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appVersionName: String,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel() }
) {
    val state by viewModel.uiState.collectAsState()
    val updateStatus by viewModel.updateStatusMessage.collectAsState()
    val googleLinkStatus by viewModel.googleLinkStatus.collectAsState()
    var page by remember { mutableStateOf(SettingsPage.Root) }

    val leavePage: () -> Unit = {
        if (page == SettingsPage.Root) {
            onBack()
        } else {
            page = SettingsPage.Root
        }
    }

    OmniBackHandler(onBack = leavePage)

    when (page) {
        SettingsPage.Root -> SettingsRootPage(
            appVersionName = appVersionName,
            state = state,
            onBack = onBack,
            onOpenCheckForUpdates = { page = SettingsPage.CheckForUpdates },
            onOpenPinRequired = { page = SettingsPage.PinRequired },
            onOpenGoogleAccount = { page = SettingsPage.GoogleAccount },
            onFileTransferNotifications = viewModel::setFileTransferNotifications,
            onVersionNumberEasterEgg = viewModel::onVersionNumberEasterEgg
        )
        SettingsPage.CheckForUpdates -> CheckForUpdatesSettingsPage(
            state = state,
            updateStatus = updateStatus,
            onBack = { page = SettingsPage.Root },
            onToggle = viewModel::setCheckForUpdates,
            onUnitSelected = viewModel::setCheckForUpdatesUnit,
            onAmountTextChange = viewModel::setCheckForUpdatesAmountText,
            onWeekAmountSelected = viewModel::setCheckForUpdatesWeekAmount
        )
        SettingsPage.PinRequired -> PinRequiredSettingsPage(
            state = state,
            onBack = { page = SettingsPage.Root },
            onToggle = viewModel::setPinRequired,
            onPinChange = viewModel::setDevicePin,
            onIdleTimeoutSelected = viewModel::setPinIdleTimeout
        )
        SettingsPage.GoogleAccount -> GoogleAccountSettingsPage(
            state = state,
            linkStatus = googleLinkStatus,
            onBack = { page = SettingsPage.Root },
            onDisable = viewModel::disableGoogleAccountLink,
            onIdToken = viewModel::onGoogleIdToken
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRootPage(
    appVersionName: String,
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenCheckForUpdates: () -> Unit,
    onOpenPinRequired: () -> Unit,
    onOpenGoogleAccount: () -> Unit,
    onFileTransferNotifications: (Boolean) -> Unit,
    onVersionNumberEasterEgg: () -> Unit
) {
    var versionTapCount by remember { mutableIntStateOf(0) }
    var lastVersionTapEpochMs by remember { mutableLongStateOf(0L) }
    val versionTapInteraction = remember { MutableInteractionSource() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SettingsTopBar(title = "Settings", onBack = onBack)
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
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
                ListItem(
                    headlineContent = { Text("File Transfer notifications") },
                    supportingContent = {
                        Text(
                            "When on, this device shows a notification after files are received " +
                                "successfully (includes filenames). Off keeps transfers silent. " +
                                "Applies only when receiving, not when sending."
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = state.fileTransferNotificationsEnabled,
                            onCheckedChange = onFileTransferNotifications
                        )
                    }
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
                            val now = currentTimeMillis()
                            if (now - lastVersionTapEpochMs > VERSION_EASTER_EGG_TAP_WINDOW_MS) {
                                versionTapCount = 0
                            }
                            lastVersionTapEpochMs = now
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckForUpdatesSettingsPage(
    state: SettingsUiState,
    updateStatus: String?,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onUnitSelected: (UpdateCheckUnit) -> Unit,
    onAmountTextChange: (String) -> Unit,
    onWeekAmountSelected: (Int) -> Unit
) {
    val requestInstallUnknownAppsPermission = rememberRequestInstallUnknownAppsPermission()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SettingsTopBar(title = "Check for Updates", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onPinChange: (String) -> Unit,
    onIdleTimeoutSelected: (PinIdleTimeout) -> Unit
) {
    var timeoutExpanded by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SettingsTopBar(title = "PIN required", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
    onBack: () -> Unit,
    onDisable: () -> Unit,
    onIdToken: (idToken: String?, email: String?, errorMessage: String?) -> Unit
) {
    val launchSignIn = rememberGoogleSignInLauncher(onResult = onIdToken)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SettingsTopBar(title = "Google Account", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
private fun SettingsTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
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
