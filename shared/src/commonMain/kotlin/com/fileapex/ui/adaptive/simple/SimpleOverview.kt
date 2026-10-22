package com.fileapex.ui.adaptive.simple

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.di.FileApexServices
import com.fileapex.domain.diagnostics.DeviceDiagnosticsFormatter
import com.fileapex.domain.notifications.NotificationBroadcaster
import com.fileapex.domain.notifications.PhoneSettingsPage
import com.fileapex.domain.peer.PeerPlatform
import com.fileapex.i18n.formatLocalizedDateTime
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.DeviceListRow
import com.fileapex.presentation.DevicesViewModel
import com.fileapex.ui.dnd.deviceFileDropTarget
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun titleCase(text: String): String =
    text.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

/** Many phones report the maker inside the model name already ("motorola razr fold"). */
private fun makeAndModel(make: String, model: String): String {
    val cleanMake = make.trim()
    val cleanModel = model.trim()
    val joined = when {
        cleanModel.isEmpty() -> cleanMake
        cleanMake.isEmpty() || cleanModel.startsWith(cleanMake, ignoreCase = true) -> cleanModel
        else -> "$cleanMake $cleanModel"
    }
    return titleCase(joined)
}

@Composable
internal fun SimpleOverview(
    devicesViewModel: DevicesViewModel,
    rows: List<DeviceListRow>,
    active: DeviceListRow?,
    onAddDevice: () -> Unit, onJoinDevice: () -> Unit,
    onOpenPairedDevice: (String) -> Unit,
    onFilesDropped: (List<String>) -> Unit
) {
    val state by devicesViewModel.uiState.collectAsState()
    var dropHover by remember { mutableStateOf(false) }
    val connected = active?.takeIf { it.online }
    LaunchedEffect(connected?.deviceId) {
        if (connected != null) devicesViewModel.requestDeviceDetails(connected.deviceId, summaryOnly = true)
    }
    val details = state.deviceDetails?.takeIf { it.deviceId == connected?.deviceId }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .deviceFileDropTarget(onHoverChange = { dropHover = it }, onFilesDropped = onFilesDropped)
            .then(
                if (dropHover) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                } else {
                    Modifier
                }
            )
    ) {
    val showDropHint = currentPlatformLabel() != "Android"
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = if (showDropHint) 48.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringRes("simple_overview"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (connected == null) {
            Text(
                stringRes("simple_paired_devices_count", rows.size.toString()),
                style = MaterialTheme.typography.titleMedium
            )
            rows.forEach { row ->
                ListItem(
                    modifier = Modifier.clickable { onOpenPairedDevice(row.deviceId) },
                    headlineContent = { Text(row.deviceName) },
                    supportingContent = { Text(row.subtitle) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
            if (rows.isEmpty()) {
                SimpleSetupButton(onGenerate = onAddDevice, onJoin = onJoinDevice)
            }
        } else {
            Text(
                stringRes("simple_connected_with", connected.deviceName),
                style = MaterialTheme.typography.titleLarge
            )
            val snapshot = details?.snapshot
            SimpleInfoLine(
                stringRes("simple_make_model"),
                makeAndModel(
                    make = snapshot?.device?.make?.takeIf { it.isNotBlank() } ?: connected.deviceMake,
                    model = snapshot?.device?.model?.takeIf { it.isNotBlank() } ?: connected.deviceModel
                ).ifBlank { "-" }
            )
            if (details?.loading == true && snapshot == null) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            if (snapshot != null) SimpleSnapshotLines(snapshot)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (snapshot != null) {
                    Text(
                        stringRes("simple_last_checked", formatLocalizedDateTime(snapshot.collectedAtEpochMs, TimeUtils.DEFAULT_ZONE_ID)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { devicesViewModel.requestDeviceDetails(connected.deviceId, summaryOnly = true) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringRes("refresh"))
                }
            }
            HorizontalDivider()
            SimpleClipboardRow(connected)
            if (!PeerPlatform.isDesktop(connected.os, connected.platform)) {
                HorizontalDivider()
                SimpleNotificationSection(connected)
            }
        }
        com.fileapex.friends.ui.FriendsEntryCard()
    }
    if (showDropHint) {
        Text(
            stringRes(if (maxWidth >= 480.dp) "simple_drop_page_full" else "simple_drop_page_short"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
        )
    }
    }
}

// Android blocks starting an activity from the background, so the other device shows a notification to tap.
@Composable
private fun SimpleClipboardRow(device: DeviceListRow) {
    val scope = rememberCoroutineScope()
    var sharingEnabled by remember(device.deviceId) { mutableStateOf<Boolean?>(null) }
    var promptSent by remember(device.deviceId) { mutableStateOf(false) }
    var note by remember(device.deviceId) { mutableStateOf<String?>(null) }
    val sentText = stringRes("simple_clipboard_prompt_sent")
    val failedText = stringRes("simple_prompt_failed")

    LaunchedEffect(device.deviceId, device.online, promptSent) {
        if (!device.online) return@LaunchedEffect
        val entity = FileApexServices.deviceRepository.getDevice(device.deviceId) ?: return@LaunchedEffect
        val endpoint = FileApexServices.presenceMonitor.endpointIfOnline(entity) ?: return@LaunchedEffect
        do {
            sharingEnabled = cancellableCatching {
                FileApexServices.client.getClipboardStatus(endpoint.host, endpoint.port).sharingEnabled
            }.getOrNull()
            if (sharingEnabled == true || !promptSent) break
            delay(3_000)
        } while (true)
    }

    val off = sharingEnabled == false
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = off) {
                scope.launch {
                    val entity = FileApexServices.deviceRepository.getDevice(device.deviceId)
                    val sent = entity != null &&
                        NotificationBroadcaster.requestPhonePrompt(entity, PhoneSettingsPage.CLIPBOARD)
                    promptSent = sent
                    note = if (sent) sentText else failedText
                }
            },
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringRes("clipboard"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                when (sharingEnabled) {
                    true -> stringRes("simple_clipboard_on")
                    false -> stringRes("simple_clipboard_off")
                    null -> "-"
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }
        val hint = note ?: if (off) stringRes("simple_clipboard_tap_enable") else null
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SimpleInfoLine(label: String, value: String, detail: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick).padding(vertical = 6.dp) else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(16.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun SimpleManagementItem(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(icon, contentDescription = null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        },
        headlineContent = {
            Text(label, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
internal fun SimpleInfo(devicesViewModel: DevicesViewModel, active: DeviceListRow?) {
    val state by devicesViewModel.uiState.collectAsState()
    val preferences by FileApexServices.settings.deviceDetailsDisplayPreferences.collectAsState()
    LaunchedEffect(active?.deviceId) {
        active?.let { devicesViewModel.requestDeviceDetails(it.deviceId) }
    }
    val details = state.deviceDetails?.takeIf { it.deviceId == active?.deviceId }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(stringRes("info"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        when {
            active == null -> Text(stringRes("simple_no_device_title"))
            details == null || (details.loading && details.snapshot == null) -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
            details.snapshot == null && details.errorMessage != null -> Text(details.errorMessage, color = MaterialTheme.colorScheme.error)
            details.snapshot != null -> DeviceDiagnosticsFormatter.detailRows(
                snapshot = details.snapshot,
                preferences = preferences
            ).forEach { (label, value) ->
                Column {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
