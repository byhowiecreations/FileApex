package com.fileapex.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import com.fileapex.platform.supportsDevicePopups
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.SettingsScreenLayoutMode
import com.fileapex.ui.adaptive.FileApexPaneSectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationsSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onToggleFileTransferNotifications: (Boolean) -> Unit,
    onToggleNotesNotifications: (Boolean) -> Unit,
    onToggleDriveRelayNotifications: (Boolean) -> Unit,
    onToggleLiveTransferCapsule: (Boolean) -> Unit,
    onOpenBroadcastNotifications: () -> Unit
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

            if (!com.fileapex.di.FileApexServices.isFdroidBuild) {
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
            }

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

            if (!usesDesktopFileSelection() && state.fileTransferNotificationsEnabled) {
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

            if (supportsDevicePopups() && com.fileapex.domain.notifications.NotificationSyncFeature.ENABLED) {
                val popups by com.fileapex.di.FileApexServices.settings.deviceNotificationPopups.collectAsState()
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringRes("device_popups"), softWrap = true) },
                    supportingContent = { Text(stringRes("device_popups_desc"), softWrap = true) },
                    trailingContent = {
                        Switch(
                            checked = popups,
                            onCheckedChange = com.fileapex.di.FileApexServices.settings::setDeviceNotificationPopups
                        )
                    }
                )
            }

            if (!usesDesktopFileSelection() && com.fileapex.domain.notifications.NotificationSyncFeature.ENABLED) {
                HorizontalDivider()
                SettingsNavItem(
                    title = stringRes("broadcast_notifications"),
                    subtitle = stringRes("broadcast_notifications_desc"),
                    onClick = onOpenBroadcastNotifications
                )
            }
        }
    }
}
