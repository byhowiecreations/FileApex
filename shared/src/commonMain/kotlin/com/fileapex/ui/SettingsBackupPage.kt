package com.fileapex.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.backup.BackupConfig
import com.fileapex.domain.backup.BackupEngine
import com.fileapex.domain.backup.BackupManifest
import com.fileapex.domain.backup.BackupStatus
import com.fileapex.domain.backup.BackupSummary
import com.fileapex.i18n.stringRes
import com.fileapex.platform.BackupScheduler
import com.fileapex.platform.defaultStorageRoot
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.adaptive.FileApexPaneSectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun backupSummary(): String {
    val config by FileApexServices.settings.backupConfig.collectAsState()
    val devices by FileApexServices.deviceRepository.observeDevices().collectAsState(initial = emptyList())
    if (!config.enabled) return stringRes("off")
    val destination = devices.firstOrNull { it.deviceId == config.destinationDeviceId }?.deviceName
        ?: return stringRes("backup_destination_none")
    return stringRes("backup_summary", config.sources.size, destination)
}

/** Hub for everything Bulletin Board: how it looks and whether remote devices may delete from it. */
@Composable
internal fun BulletinBoardHubPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onOpenStyles: () -> Unit,
    onOpenRemoteDeletion: () -> Unit,
) {
    SettingsPageShell(title = stringRes("bulletin_board"), layoutMode = layoutMode, onBack = onBack) { contentModifier ->
        CompositionLocalProvider(LocalContentColor provides themedContentColor()) {
        Column(contentModifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            HubRow(
                title = stringRes("bulletin_board_styles"),
                subtitle = localizedBulletinBoardStyleName(state.bulletinBoardStyle),
                onClick = onOpenStyles
            )
            HubRow(
                title = stringRes("bulletin_delete_remote_title"),
                subtitle = if (state.allowRemoteFileDeletion) stringRes("on") else stringRes("off"),
                onClick = onOpenRemoteDeletion
            )
        }
        }
    }
}

@Composable
private fun HubRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@Composable
internal fun BackupSyncSettingsPage(layoutMode: SettingsScreenLayoutMode, onBack: () -> Unit) {
    val settings = FileApexServices.settings
    val config by settings.backupConfig.collectAsState()
    val devices by FileApexServices.deviceRepository.observeDevices().collectAsState(initial = emptyList())
    val status by BackupEngine.status.collectAsState()
    var destinationOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    val liveSummary by BackupEngine.lastSummary.collectAsState()
    val savedSummary by produceState<BackupSummary?>(null, config.destinationDeviceId) {
        value = if (config.destinationDeviceId.isBlank()) null else withContext(Dispatchers.IO) {
            val manifest = BackupManifest.load(config.destinationDeviceId)
            manifest.lastRun?.let { BackupSummary(it.atMillis, it.uploaded, it.failed, manifest.lastError) }
        }
    }
    val lastRun = liveSummary ?: savedSummary
    val update = settings::updateBackupConfig

    SettingsPageShell(title = stringRes("backup_sync"), layoutMode = layoutMode, onBack = onBack) { contentModifier ->
        CompositionLocalProvider(LocalContentColor provides themedContentColor()) {
        Column(
            modifier = contentModifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)
        ) {
            ListItem(
                headlineContent = { Text(stringRes("backup_to_device"), softWrap = true) },
                supportingContent = { Text(stringRes("backup_intro"), softWrap = true) },
                trailingContent = {
                    Switch(checked = config.enabled, onCheckedChange = { on -> update { it.copy(enabled = on) } })
                }
            )
            if (!config.enabled) return@Column
            HorizontalDivider()

            DestinationCard(
                devices = devices,
                selectedId = config.destinationDeviceId,
                expanded = destinationOpen,
                onToggle = { destinationOpen = !destinationOpen },
                onSelect = { id ->
                    update { it.copy(destinationDeviceId = id) }
                    destinationOpen = false
                }
            )

            FileApexPaneSectionHeader(title = stringRes("backup_sources"))
            if (config.sources.isEmpty()) {
                Text(
                    stringRes("backup_no_sources"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            config.sources.forEach { path ->
                ListItem(
                    headlineContent = { Text(path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(path, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        IconButton(onClick = { update { it.withoutSource(path) } }) {
                            Icon(Icons.Filled.Close, contentDescription = stringRes("backup_remove"))
                        }
                    }
                )
            }
            TextButton(onClick = { addOpen = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringRes("backup_add_folder"))
            }
            HorizontalDivider()

            ListItem(
                headlineContent = { Text(stringRes("backup_wifi_only")) },
                supportingContent = { Text(stringRes("backup_wifi_only_desc")) },
                trailingContent = {
                    Switch(checked = config.wifiOnly, onCheckedChange = { on -> update { it.copy(wifiOnly = on) } })
                }
            )
            Text(
                stringRes("backup_frequency"),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BackupConfig.intervalChoices.forEach { hours ->
                    val label = stringRes(
                        when (hours) {
                            1 -> "backup_every_hour"
                            6 -> "backup_every_6_hours"
                            else -> "backup_daily"
                        }
                    )
                    if (hours == config.intervalHours) {
                        Button(onClick = {}) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { update { it.copy(intervalHours = hours) } }) { Text(label) }
                    }
                }
            }
            HorizontalDivider()

            StatusBlock(config = config, status = status, lastRun = lastRun)
        }
        }
    }

    if (addOpen) {
        FolderPickerDialog(
            startPath = defaultStorageRoot(),
            title = stringRes("backup_add_title"),
            confirmLabel = stringRes("backup_add_confirm"),
            onPick = { path ->
                addOpen = false
                update { it.withSource(path) }
            },
            onDismiss = { addOpen = false }
        )
    }
}

@Composable
private fun DestinationCard(
    devices: List<PairedDeviceEntity>,
    selectedId: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val selected = devices.firstOrNull { it.deviceId == selectedId }
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        headlineContent = { Text(stringRes("backup_destination")) },
        supportingContent = { Text(selected?.deviceName ?: stringRes("backup_destination_none")) },
        trailingContent = {
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
    )
    if (expanded) {
        if (devices.isEmpty()) {
            Text(
                stringRes("backup_no_devices"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        devices.forEach { device ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(device.deviceId) }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = device.deviceId == selectedId, onClick = { onSelect(device.deviceId) })
                Spacer(Modifier.width(8.dp))
                Text(device.deviceName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun StatusBlock(config: BackupConfig, status: BackupStatus, lastRun: BackupSummary?) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (status) {
            is BackupStatus.Running -> {
                Text(
                    stringRes("backup_running", status.done + 1, status.total),
                    fontWeight = FontWeight.SemiBold
                )
                Text(status.current, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { if (status.total == 0) 0f else status.done.toFloat() / status.total },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            BackupStatus.Idle -> Text(
                lastRun?.let { stringRes("backup_last_run", it.uploaded, it.failed) } ?: stringRes("backup_never"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        lastRun?.error?.let { reason ->
            Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Button(
            enabled = config.isRunnable && status == BackupStatus.Idle,
            onClick = { BackupScheduler.runNow(config) }
        ) { Text(stringRes("backup_now")) }
        Text(
            stringRes("backup_never_deletes"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
    }
}
