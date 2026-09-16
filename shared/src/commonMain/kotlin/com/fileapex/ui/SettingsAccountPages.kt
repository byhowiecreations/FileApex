package com.fileapex.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fileapex.cloud.drive.GoogleDriveAuth
import com.fileapex.data.settings.DriveRelayMaxMb
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes
import com.fileapex.platform.rememberGoogleDriveAuthLauncher
import com.fileapex.platform.rememberGoogleSignInLauncher
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.adaptive.FileApexPaneSectionHeader
import com.fileapex.ui.dialogs.GoogleDrivePermissionDialog

internal fun googleAccountSubtitle(state: SettingsUiState): String {
    if (!state.googleAccountLinkEnabled) return AppI18n.t("google_unlinked")
    return if (state.googleDriveRelayEnabled) {
        "${AppI18n.t("google_linked")} · ${AppI18n.t("drive_relay")}"
    } else {
        AppI18n.t("google_linked")
    }
}

@Composable
internal fun DriveRelaySettingsSection(
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
internal fun GoogleAccountSettingsPage(
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
