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
import androidx.compose.ui.graphics.vector.ImageVector
import com.fileapex.ui.settings.SettingsSearchIds
import com.fileapex.ui.settings.SettingsSearchResult
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.UpdateCheckFrequency
import com.fileapex.di.FileApexServices
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
    val onOpenPage: (SettingsPage) -> Unit
)

private data class ResolvedMenuRow(
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
    val isDestructive: Boolean = false,
    val leadingIcon: ImageVector? = null
)

@Composable
internal fun SettingsRootInputs.MenuRow(
    entry: SettingsMenuEntry,
    tailscale: com.fileapex.tailscale.TailscaleUiState,
    searchMatchHint: String? = null,
    isAdvancedMatch: Boolean = false,
    onReportIssue: () -> Unit
) {
    val openRowPage: () -> Unit = { settingsPageFor(entry.id)?.let(onOpenPage) }
    val row = when (entry.id) {
        SettingsMenuIds.DEVICE_NAME -> ResolvedMenuRow(
            title = stringRes("device_name"),
            subtitle = state.deviceNameBroadcasting.ifBlank { stringRes("paired_device") },
            onClick = openRowPage
        )
        SettingsMenuIds.LANGUAGE -> ResolvedMenuRow(
            title = stringRes("language"),
            subtitle = AppI18n.languageRowLabel(AppI18n.locale),
            onClick = openRowPage
        )
        SettingsMenuIds.CHECK_FOR_UPDATES -> ResolvedMenuRow(
            title = stringRes("check_for_updates"),
            subtitle = if (state.checkForUpdatesEnabled) {
                UpdateCheckFrequency.label(
                    state.checkForUpdatesIntervalUnit,
                    state.checkForUpdatesIntervalAmount
                )
            } else {
                stringRes("off")
            },
            onClick = openRowPage
        )
        SettingsMenuIds.REPORT_ISSUE -> ResolvedMenuRow(
            title = stringRes("report_issue_feedback"),
            subtitle = stringRes("report_issue_feedback_desc"),
            onClick = onReportIssue
        )
        SettingsMenuIds.BACKGROUND_PERSISTENCE -> ResolvedMenuRow(
            title = stringRes("background_persistence"),
            subtitle = backgroundPersistenceSubtitle(
                watchdogEnabled = state.enableServiceWatchdog,
                backgroundPersistence = backgroundPersistence,
                exactAlarmWarningActive = exactAlarmWarningActive
            ),
            onClick = openRowPage
        )
        SettingsMenuIds.AUTO_LAUNCH -> ResolvedMenuRow(
            title = stringRes(if (com.fileapex.platform.BootLaunchPreference.usesDesktopAutostart()) "auto_launch_on_signin" else "auto_launch_on_reboot"),
            subtitle = if (state.autoLaunchOnReboot) stringRes("on") else stringRes("off"),
            onClick = openRowPage
        )
        SettingsMenuIds.TAILSCALE -> ResolvedMenuRow(
            title = stringRes("tailscale_overlay"),
            subtitle = tailscaleRowSubtitle(tailscale),
            onClick = openRowPage
        )
        SettingsMenuIds.IROH -> ResolvedMenuRow(
            title = stringRes("iroh_title"),
            subtitle = irohRowSubtitle(),
            onClick = openRowPage
        )
        SettingsMenuIds.GROUPS -> ResolvedMenuRow(
            title = stringRes("groups_title"),
            subtitle = com.fileapex.friends.ui.groupsRowSubtitle(),
            onClick = openRowPage
        )
        SettingsMenuIds.SHARE_LINK -> ResolvedMenuRow(
            title = stringRes("share_link_page_title"),
            subtitle = com.fileapex.drop.ui.shareLinkRowSubtitle(),
            onClick = openRowPage
        )
        SettingsMenuIds.THEMES -> ResolvedMenuRow(
            title = stringRes("themes"),
            subtitle = localizedThemeName(state.appTheme),
            onClick = openRowPage
        )
        SettingsMenuIds.BULLETIN_BOARD -> ResolvedMenuRow(
            title = stringRes("bulletin_board"),
            subtitle = localizedBulletinBoardStyleName(state.bulletinBoardStyle),
            onClick = openRowPage
        )
        SettingsMenuIds.BACKUP_SYNC -> ResolvedMenuRow(
            title = stringRes("backup_sync"),
            subtitle = backupSummary(),
            onClick = openRowPage
        )
        SettingsMenuIds.NOTIFICATIONS -> ResolvedMenuRow(
            title = stringRes("notifications"),
            subtitle = when (val summary = notificationsRowSummary(state)) {
                NotificationsRowSummary.Off -> stringRes("off")
                NotificationsRowSummary.CriticalOnly -> stringRes("notifications_row_critical_only")
                is NotificationsRowSummary.Count -> stringRes("notifications_row_count", summary.on, summary.of)
            },
            onClick = openRowPage
        )
        SettingsMenuIds.CLIPBOARD -> ResolvedMenuRow(
            title = stringRes("clipboard"),
            subtitle = clipboardSettingsSubtitle(state),
            onClick = openRowPage
        )
        SettingsMenuIds.DEVICE_DETAILS -> ResolvedMenuRow(
            title = stringRes("device_details"),
            subtitle = stringRes("peer_telemetry_fields"),
            onClick = openRowPage
        )
        SettingsMenuIds.DESKTOP_LAYOUT -> {
            val navPos by FileApexServices.settings.desktopNavPosition.collectAsState()
            ResolvedMenuRow(
                title = stringRes("nav_position_title"),
                subtitle = stringRes(
                    when (navPos) {
                        com.fileapex.data.settings.DesktopNavPosition.Left -> "nav_position_left"
                        com.fileapex.data.settings.DesktopNavPosition.Bottom -> "nav_position_bottom"
                        com.fileapex.data.settings.DesktopNavPosition.Right -> "nav_position_right"
                    }
                ),
                onClick = openRowPage
            )
        }
        SettingsMenuIds.WINDOWS_DESIGN -> ResolvedMenuRow(
            title = stringRes("windows_design"),
            subtitle = localizedDesktopUiStyle(state.desktopUiStyle),
            onClick = openRowPage
        )
        SettingsMenuIds.PIN_REQUIRED -> ResolvedMenuRow(
            title = stringRes("pin_required"),
            subtitle = AppI18n.t(
                "pin_subtitle",
                if (state.pinRequiredEnabled) AppI18n.t("on") else AppI18n.t("off"),
                localizedPinIdle(state.pinIdleTimeout)
            ),
            onClick = openRowPage
        )
        SettingsMenuIds.DRIVE_RELAY -> ResolvedMenuRow(
            title = stringRes("drive_relay"),
            subtitle = when {
                !state.googleAccountLinkEnabled -> stringRes("google_unlinked")
                state.googleDriveRelayEnabled -> stringRes("on")
                else -> stringRes("off")
            },
            onClick = openRowPage
        )
        SettingsMenuIds.GOOGLE_ACCOUNT -> ResolvedMenuRow(
            title = stringRes("google_account"),
            subtitle = googleAccountSubtitle(state),
            onClick = openRowPage
        )
        SettingsMenuIds.FULL_DISK_ACCESS -> ResolvedMenuRow(
            title = stringRes("full_disk_access"),
            subtitle = stringRes(if (com.fileapex.platform.isFullDiskAccessGranted()) "on" else "off"),
            onClick = openRowPage
        )
        SettingsMenuIds.LEAVE_CLUSTER -> ResolvedMenuRow(
            title = stringRes("leave_cluster_wipe_title"),
            subtitle = stringRes("leave_cluster_wipe_desc"),
            isDestructive = true,
            leadingIcon = Icons.Filled.Warning,
            onClick = openRowPage
        )
        else -> return
    }
    SettingsNavItem(
        title = row.title,
        subtitle = formatSearchSubtitle(row.subtitle, searchMatchHint, isAdvancedMatch),
        onClick = row.onClick,
        isDestructive = row.isDestructive,
        leadingIcon = row.leadingIcon
    )
}

@Composable
internal fun SettingsRootInputs.SearchResultRow(
    result: SettingsSearchResult,
    tailscale: com.fileapex.tailscale.TailscaleUiState,
    onReportIssue: () -> Unit
) {
    val entry = result.entry
    if (entry != null) {
        MenuRow(
            entry = entry,
            tailscale = tailscale,
            searchMatchHint = result.matchedHint,
            isAdvancedMatch = result.isAdvanced,
            onReportIssue = onReportIssue
        )
    } else if (result.id == SettingsSearchIds.ABOUT) {
        val aboutSubtitle = formatSearchSubtitle(
            baseSubtitle = result.matchedHint ?: stringRes("about_title"),
            searchMatchHint = result.matchedHint,
            isAdvancedMatch = false
        )
        SettingsNavItem(
            title = stringRes("about_title"),
            subtitle = aboutSubtitle,
            onClick = { onOpenPage(SettingsPage.About) }
        )
    }
}

@Composable
private fun formatSearchSubtitle(
    baseSubtitle: String,
    searchMatchHint: String?,
    isAdvancedMatch: Boolean
): String {
    if (searchMatchHint == null && !isAdvancedMatch) return baseSubtitle
    val parts = buildList {
        if (searchMatchHint != null) add(stringRes("settings_search_match", searchMatchHint))
        if (isAdvancedMatch) add(stringRes("settings_search_advanced_tag"))
    }
    val summary = parts.joinToString(" · ")
    return if (baseSubtitle.isNotBlank() && baseSubtitle != searchMatchHint) "$summary · $baseSubtitle" else summary
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

internal sealed interface NotificationsRowSummary {
    data object Off : NotificationsRowSummary
    data object CriticalOnly : NotificationsRowSummary
    data class Count(val on: Int, val of: Int) : NotificationsRowSummary
}

/** The live capsule belongs to file transfer notifications, so it is not a kind of its own. */
internal fun notificationsRowSummary(state: SettingsUiState): NotificationsRowSummary {
    val kinds = listOf(
        state.noticesEnabled,
        state.notesNotificationsEnabled,
        state.driveRelayNotificationsEnabled,
        state.fileTransferNotificationsEnabled
    )
    val on = kinds.count { it }
    return when {
        on == 0 -> NotificationsRowSummary.Off
        on == 1 && state.noticesEnabled -> NotificationsRowSummary.CriticalOnly
        else -> NotificationsRowSummary.Count(on, kinds.size)
    }
}
