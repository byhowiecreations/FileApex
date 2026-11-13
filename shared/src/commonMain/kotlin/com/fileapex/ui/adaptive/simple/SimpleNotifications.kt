package com.fileapex.ui.adaptive.simple

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.domain.diagnostics.DeviceDiagnosticsFormatter
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.domain.notifications.NotificationBroadcaster
import com.fileapex.domain.notifications.NotificationCounts
import com.fileapex.domain.notifications.NotificationInbox
import com.fileapex.domain.notifications.NotificationKind
import com.fileapex.domain.notifications.NotificationSyncFeature
import com.fileapex.domain.notifications.NotificationSyncStatus
import com.fileapex.domain.notifications.NotificationThread
import com.fileapex.domain.notifications.NotificationThreads
import com.fileapex.domain.notifications.PhoneNotificationState
import com.fileapex.domain.notifications.PhoneSettingsPage
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.DeviceListRow
import com.fileapex.ui.adaptive.NotificationListDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val NOTIFICATION_CHECK_RETRY_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

private const val NOTIFICATION_PROMPT_POLL_MAX = 40

@Composable
internal fun SimpleNotificationSection(device: DeviceListRow) {
    val scope = rememberCoroutineScope()
    val items by NotificationInbox.items.collectAsState()
    val counts = remember(items, device.deviceId) { NotificationInbox.counts(items, device.deviceId) }
    var status by remember(device.deviceId) { mutableStateOf<NotificationSyncStatus?>(null) }
    var checked by remember(device.deviceId) { mutableStateOf(false) }
    var promptSent by remember(device.deviceId) { mutableStateOf(false) }
    var promptFailed by remember(device.deviceId) { mutableStateOf(false) }
    val isDemoDevice = com.fileapex.domain.demo.DemoModeState.isDemoDeviceId(device.deviceId)
    val demoCounts = remember(isDemoDevice) { NotificationCounts(messages = 2, emails = 0, other = 4) }
    val ready = isDemoDevice || status?.let { it.supported && it.enabled && it.accessGranted } == true
    var listKind by remember { mutableStateOf<NotificationKind?>(null) }
    var snapshot by remember { mutableStateOf(emptyList<NotificationThread>()) }
    listKind?.let { kind ->
        NotificationListDialog(
            kind = kind,
            title = stringRes(
                when (kind) {
                    NotificationKind.MESSAGE -> "simple_messages"
                    NotificationKind.EMAIL -> "simple_emails"
                    NotificationKind.OTHER -> "simple_other_notifications"
                }
            ),
            threads = snapshot,
            onClear = { keys -> NotificationBroadcaster.dismissOnDevice(device.deviceId, keys) },
            onReply = { key, text -> NotificationBroadcaster.replyOnDevice(device.deviceId, key, text) },
            onDismiss = { listKind = null }
        )
    }

    // Changes whenever this phone is verified reachable again, e.g. after the Mac wakes from sleep.
    val reachedAt = FileApexServices.presenceMonitor.reachabilityEpochMs.collectAsState().value[device.deviceId]
    val foregroundedAt by com.fileapex.domain.presence.PresenceForegroundRefresh.foregroundedAtMs.collectAsState()
    var unreachable by remember(device.deviceId) { mutableStateOf(false) }
    var needsUpdate by remember(device.deviceId) { mutableStateOf(false) }

    val appInForeground by com.fileapex.domain.presence.PresenceForegroundRefresh.isForeground.collectAsState()

    LaunchedEffect(device.deviceId, device.online, promptSent, reachedAt, foregroundedAt, appInForeground) {
        // A stopped Activity keeps its composition alive: without this the check keeps running while hidden.
        if (isDemoDevice) {
            checked = true
            return@LaunchedEffect
        }
        if (!NotificationSyncFeature.ENABLED || !device.online || !appInForeground) return@LaunchedEffect
        val entity = FileApexServices.deviceRepository.getDevice(device.deviceId) ?: return@LaunchedEffect
        var failures = 0
        var promptPolls = 0
        while (true) {
            when (val result = NotificationBroadcaster.checkPhone(entity)) {
                PhoneNotificationState.NeedsUpdate -> {
                    needsUpdate = true
                    unreachable = false
                    checked = true
                    break
                }
                PhoneNotificationState.Unreachable -> {
                    // The first calls after a wake often fail before the network is back: retry, never report "update".
                    com.fileapex.security.tls.TlsPinAnnouncer.nudge(device.deviceId)
                    if (failures >= NOTIFICATION_CHECK_RETRY_MS.size) {
                        // Restarts when the phone is verified reachable again or the app returns to the front.
                        unreachable = true
                        checked = true
                        break
                    } else {
                        delay(NOTIFICATION_CHECK_RETRY_MS[failures++])
                    }
                }
                is PhoneNotificationState.Known -> {
                    status = result.status
                    needsUpdate = false
                    unreachable = false
                    checked = true
                    val done = result.status.supported && result.status.enabled && result.status.accessGranted
                    if (done || !promptSent || ++promptPolls > NOTIFICATION_PROMPT_POLL_MAX) break
                    delay(3_000)
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = SimpleModernPalette.CardSurface,
        border = BorderStroke(1.dp, SimpleModernPalette.CardBorder)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                !NotificationSyncFeature.ENABLED -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Notifications,
                            contentDescription = null,
                            tint = SimpleModernPalette.AccentMintSoft,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringRes("notifications"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = SimpleModernPalette.TextPrimary
                        )
                    }
                    SimpleNotificationCountsRow(counts = null)
                    Text(
                        stringRes("simple_notifications_coming_soon"),
                        style = MaterialTheme.typography.bodySmall,
                        color = SimpleModernPalette.TextSecondary
                    )
                }
                !device.online || unreachable -> {
                    Text(
                        stringRes("notifications"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SimpleModernPalette.TextPrimary
                    )
                    Text(
                        stringRes("simple_nme_unreachable"),
                        style = MaterialTheme.typography.bodySmall,
                        color = SimpleModernPalette.TextSecondary
                    )
                }
                !checked -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            stringRes("notifications"),
                            style = MaterialTheme.typography.titleSmall,
                            color = SimpleModernPalette.TextPrimary
                        )
                    }
                }
                ready -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Notifications,
                            contentDescription = null,
                            tint = SimpleModernPalette.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            stringRes("notifications"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = SimpleModernPalette.TextPrimary
                        )
                    }
                    SimpleNotificationCountsRow(counts = if (isDemoDevice) demoCounts else counts) { kind ->
                        snapshot = NotificationThreads.threads(NotificationInbox.from(items, device.deviceId), kind)
                        listKind = kind
                    }
                }
                needsUpdate || status?.supported != true -> {
                    Text(
                        stringRes("notifications"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SimpleModernPalette.TextPrimary
                    )
                    Text(
                        stringRes("simple_nme_update_phone"),
                        style = MaterialTheme.typography.bodySmall,
                        color = SimpleModernPalette.TextSecondary
                    )
                }
                else -> {
                    SimpleNotificationPromptRow(
                        deviceId = device.deviceId,
                        promptSent = promptSent,
                        promptFailed = promptFailed,
                        onPromptResult = { sent ->
                            promptSent = sent
                            promptFailed = !sent
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SimpleNotificationPromptRow(
    deviceId: String,
    promptSent: Boolean,
    promptFailed: Boolean,
    onPromptResult: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(SimpleModernPalette.PillTint),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = SimpleModernPalette.AccentMintSoft,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringRes("notifications"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = SimpleModernPalette.TextPrimary
                )
                Text(
                    text = stringRes("phone_notifications_off"),
                    style = MaterialTheme.typography.bodySmall,
                    color = SimpleModernPalette.TextSecondary
                )
            }
        }
        Button(
            onClick = {
                scope.launch {
                    val entity = FileApexServices.deviceRepository.getDevice(deviceId)
                    val sent = entity != null &&
                        NotificationBroadcaster.requestPhonePrompt(
                            entity,
                            PhoneSettingsPage.BROADCAST_NOTIFICATIONS
                        )
                    onPromptResult(sent)
                }
            },
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SimpleModernPalette.AccentMint,
                contentColor = SimpleModernPalette.AccentOnMint
            )
        ) {
            Text(
                text = stringRes("turn_on_from_phone"),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
    if (promptSent) {
        Text(
            stringRes("simple_nme_waiting"),
            style = MaterialTheme.typography.labelSmall,
            color = SimpleModernPalette.TextSecondary
        )
    }
    if (promptFailed) {
        Text(
            stringRes("simple_prompt_failed"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun SimpleNotificationCountsRow(
    counts: NotificationCounts?,
    onOpen: ((NotificationKind) -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        NotificationCounterPill(
            label = stringRes("simple_messages"),
            count = counts?.messages,
            onClick = if (onOpen != null && (counts?.messages ?: 0) > 0) ({ onOpen(NotificationKind.MESSAGE) }) else null,
            modifier = Modifier.weight(1f)
        )
        NotificationCounterPill(
            label = stringRes("simple_emails"),
            count = counts?.emails,
            onClick = if (onOpen != null && (counts?.emails ?: 0) > 0) ({ onOpen(NotificationKind.EMAIL) }) else null,
            modifier = Modifier.weight(1f)
        )
        NotificationCounterPill(
            label = stringRes("notif_other_short"),
            count = counts?.other,
            onClick = if (onOpen != null && (counts?.other ?: 0) > 0) ({ onOpen(NotificationKind.OTHER) }) else null,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun NotificationCounterPill(
    label: String,
    count: Int?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(12.dp),
        color = SimpleModernPalette.CardSurface,
        border = BorderStroke(1.dp, SimpleModernPalette.CardBorder)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = SimpleModernPalette.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Surface(shape = RoundedCornerShape(6.dp), color = SimpleModernPalette.CounterPill) {
                Text(
                    text = count?.toString() ?: "0",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = SimpleModernPalette.TextPrimary,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
internal fun SimpleSnapshotLines(snapshot: PeerDeviceDiagnostics) {
    val level = DeviceDiagnosticsFormatter.formatPercent(snapshot.battery.levelPercent)
    val charging = snapshot.battery.chargingState.takeIf { it.isNotBlank() }
    SimpleInfoLine(stringRes("simple_battery"), if (charging != null) "$level · $charging" else level)
    SimpleInfoLine(stringRes("simple_storage"), DeviceDiagnosticsFormatter.storageSummary(snapshot.storage))
    val memory = snapshot.memory
    val memoryTotal = memory.totalBytes
    val memoryFree = memory.availableBytes
    if (memoryTotal != null && memoryFree != null && memoryTotal > 0L) {
        val memoryUsed = memory.usedBytes ?: (memoryTotal - memoryFree).coerceAtLeast(0L)
        SimpleInfoLine(
            stringRes("simple_memory"),
            stringRes(
                "simple_memory_free_of",
                DeviceDiagnosticsFormatter.formatBytes(memoryFree),
                DeviceDiagnosticsFormatter.formatBytes(memoryTotal)
            ),
            detail = stringRes("simple_memory_used", DeviceDiagnosticsFormatter.formatBytes(memoryUsed))
        )
    } else {
        SimpleInfoLine(stringRes("simple_memory"), "-")
    }
    SimpleInfoLine(stringRes("simple_uptime"), DeviceDiagnosticsFormatter.formatUptime(snapshot.uptime.uptimeMs))
}
