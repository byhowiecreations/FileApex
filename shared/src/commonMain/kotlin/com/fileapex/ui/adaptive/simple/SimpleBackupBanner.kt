package com.fileapex.ui.adaptive.simple

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.data.device.DeviceDisplayNames
import com.fileapex.di.FileApexServices
import com.fileapex.domain.backup.BackupEngine
import com.fileapex.domain.backup.BackupFolderPresets
import com.fileapex.domain.backup.BackupManifest
import com.fileapex.domain.backup.BackupStatus
import com.fileapex.domain.backup.BackupStatusKind
import com.fileapex.domain.backup.BackupStatusLabelPolicy
import com.fileapex.domain.backup.BackupSuggestionPolicy
import com.fileapex.domain.backup.BackupSummary
import com.fileapex.domain.clipboard.ClipboardImageInbox
import com.fileapex.domain.peer.PeerPlatform
import com.fileapex.i18n.stringRes
import com.fileapex.platform.BackupScheduler
import com.fileapex.platform.DownloadsPaths
import com.fileapex.platform.defaultStorageRoot
import com.fileapex.presentation.DeviceListRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PostPairBackupSuggestionSection(
    rows: List<DeviceListRow>,
    onOpenBackupSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = FileApexServices.settings
    val config by settings.backupConfig.collectAsState()
    val isAndroid = currentPlatformLabel() == "Android"
    val desktopPeers = rows.filter { PeerPlatform.isDesktop(it.os, it.platform) }
        .map { BackupSuggestionPolicy.DesktopPeer(it.deviceId, it.deviceName) }
    val target = BackupSuggestionPolicy.targetDesktop(isAndroid, desktopPeers, config) ?: return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = SimpleModernPalette.CardSurface,
        border = BorderStroke(1.dp, SimpleModernPalette.CardBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    tint = SimpleModernPalette.AccentMintSoft,
                    modifier = Modifier.size(20.dp).padding(top = 2.dp)
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = stringRes("backup_suggest_title", target.name),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SimpleModernPalette.TextPrimary
                    )
                    Text(
                        text = stringRes("backup_suggest_subtitle"),
                        style = MaterialTheme.typography.bodySmall,
                        color = SimpleModernPalette.TextSecondary
                    )
                }
                IconButton(
                    onClick = { settings.updateBackupConfig { it.copy(promptDismissed = true) } },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringRes("close"),
                        tint = SimpleModernPalette.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        val presets = BackupFolderPresets
                            .detectAndroidMediaPresets(defaultStorageRoot())
                            .map { it.path }
                        settings.updateBackupConfig { current ->
                            BackupSuggestionPolicy.enableSuggestedConfig(current, target.deviceId, presets)
                        }
                        val next = FileApexServices.settings.backupConfig.value
                        BackupScheduler.apply(next)
                        if (next.isRunnable) {
                            BackupScheduler.runNow(next)
                        } else {
                            onOpenBackupSettings()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SimpleModernPalette.AccentMint,
                        contentColor = SimpleModernPalette.AccentOnMint
                    )
                ) {
                    Text(stringRes("backup_suggest_turn_on"))
                }
                TextButton(onClick = onOpenBackupSettings) {
                    Text(stringRes("backup_suggest_customize"), color = SimpleModernPalette.AccentMintSoft)
                }
            }
        }
    }
}

@Composable
internal fun SimpleBackupStatusRow(
    onOpenBackupSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val config by FileApexServices.settings.backupConfig.collectAsState()
    val status by BackupEngine.status.collectAsState()
    val liveSummary by BackupEngine.lastSummary.collectAsState()
    val savedSummary by produceState<BackupSummary?>(null, config.destinationDeviceId) {
        value = if (config.destinationDeviceId.isBlank()) null else withContext(Dispatchers.IO) {
            val manifest = BackupManifest.load(config.destinationDeviceId)
            manifest.lastRun?.let { BackupSummary(it.atMillis, it.uploaded, it.failed, manifest.lastError) }
        }
    }
    val kind = BackupStatusLabelPolicy.classify(config, status, liveSummary ?: savedSummary) ?: return
    val statusText = when (kind) {
        is BackupStatusKind.Running -> stringRes("backup_status_running_short", kind.done, kind.total)
        is BackupStatusKind.Synced -> stringRes("backup_status_synced_short", kind.uploaded)
        is BackupStatusKind.NeedsAttention -> stringRes("backup_status_issue_short", kind.failed)
        BackupStatusKind.Ready -> stringRes("backup_status_ready_short")
    }

    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpenBackupSettings),
        shape = RoundedCornerShape(12.dp),
        color = SimpleModernPalette.CardSurface,
        border = BorderStroke(1.dp, SimpleModernPalette.CardBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                tint = SimpleModernPalette.AccentMintSoft,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringRes("backup_status_line", statusText),
                style = MaterialTheme.typography.bodySmall,
                color = SimpleModernPalette.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (status == BackupStatus.Idle) {
                TextButton(onClick = { BackupScheduler.runNow(config) }) {
                    Text(stringRes("backup_now"), color = SimpleModernPalette.AccentMintSoft)
                }
            }
        }
    }
}

@Composable
internal fun ClipboardImageReceivedBanner(modifier: Modifier = Modifier) {
    val latest by ClipboardImageInbox.latest.collectAsState()
    val image = latest ?: return
    val sender = DeviceDisplayNames.resolve(image.sourceDeviceName, null)
        .ifBlank { stringRes("paired_device") }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = SimpleModernPalette.CardSurface,
        border = BorderStroke(1.dp, SimpleModernPalette.CardBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Download,
                contentDescription = null,
                tint = SimpleModernPalette.AccentMintSoft,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringRes("clipboard_image_from_device", sender),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SimpleModernPalette.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (image.savedPath != null) {
                    Text(
                        text = stringRes("clipboard_image_saved", DownloadsPaths.displayLabel()),
                        style = MaterialTheme.typography.bodySmall,
                        color = SimpleModernPalette.AccentMintSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (image.savedPath == null) {
                TextButton(onClick = { ClipboardImageInbox.saveLatest() }) {
                    Text(stringRes("clipboard_save_image"), color = SimpleModernPalette.AccentMintSoft)
                }
            }
            IconButton(onClick = { ClipboardImageInbox.dismiss() }, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringRes("close"),
                    tint = SimpleModernPalette.TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
internal fun SimpleOverviewFooterExtras(
    isDemoActive: Boolean,
    bodyState: SimpleOverviewBodyState,
    isInitialLoadComplete: Boolean,
    rows: List<DeviceListRow>,
    onOpenBackupSettings: () -> Unit,
    onOpenStorageTools: () -> Unit
) {
    if (!isDemoActive) {
        ClipboardImageReceivedBanner()
        if (bodyState == SimpleOverviewBodyState.Connected) {
            PostPairBackupSuggestionSection(rows = rows, onOpenBackupSettings = onOpenBackupSettings)
            SimpleBackupStatusRow(onOpenBackupSettings = onOpenBackupSettings)
        }
    }
    if (isInitialLoadComplete && !isDemoActive && com.fileapex.platform.storageToolsSupported()) {
        SimpleStorageToolsStrip(onClick = onOpenStorageTools)
    }
}
