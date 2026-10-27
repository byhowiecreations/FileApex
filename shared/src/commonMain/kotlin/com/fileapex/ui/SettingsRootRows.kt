package com.fileapex.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.UpdateCheckFrequency
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes
import com.fileapex.platform.BackgroundPersistenceUiState
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.settings.SettingsNavItem
import com.fileapex.ui.settings.backupSummary
import com.fileapex.ui.settings.googleAccountSubtitle
import com.fileapex.ui.settings.irohRowSubtitle
import com.fileapex.ui.settings.localizedBulletinBoardStyleName
import com.fileapex.ui.settings.localizedPinIdle
import com.fileapex.ui.settings.localizedThemeName
import com.fileapex.ui.settings.tailscaleRowSubtitle

internal class SettingsRootInputs(
    val state: SettingsUiState,
    val backgroundPersistence: BackgroundPersistenceUiState,
    val exactAlarmWarningActive: Boolean,
    val onOpenCheckForUpdates: () -> Unit,
    val onOpenPinRequired: () -> Unit,
    val onOpenBackgroundPersistence: () -> Unit,
    val onOpenAutoLaunchOnReboot: () -> Unit,
    val onOpenDeviceName: () -> Unit,
    val onOpenLanguage: () -> Unit,
    val onOpenNotifications: () -> Unit,
    val onOpenThemes: () -> Unit,
    val onOpenBulletinBoard: () -> Unit,
    val onOpenBackupSync: () -> Unit,
    val onOpenClipboard: () -> Unit,
    val onOpenDeviceDetails: () -> Unit,
    val onOpenGoogleAccount: () -> Unit,
    val onOpenDesktopLayout: () -> Unit,
    val onOpenWindowsDesign: () -> Unit,
    val onOpenLeaveClusterWipe: () -> Unit,
    val onOpenTailscale: () -> Unit,
    val onOpenIroh: () -> Unit,
    val onOpenGroups: () -> Unit
)

@Composable
internal fun SettingsRootInputs.MenuRow(entry: SettingsMenuEntry, tailscale: com.fileapex.tailscale.TailscaleUiState, onReportIssue: () -> Unit) {
    with(this) {
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
                                    onClick = onReportIssue
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
                                SettingsMenuIds.GROUPS -> SettingsNavItem(
                                    title = stringRes("groups_title"),
                                    subtitle = com.fileapex.friends.ui.groupsRowSubtitle(),
                                    onClick = onOpenGroups
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

@Composable
internal fun OnboardingPermissionsCard(onOpenOnboardingPermissions: () -> Unit) {
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
