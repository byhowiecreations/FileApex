package com.fileapex.ui.adaptive.simple

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Modifier
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
    val ready = status?.let { it.supported && it.enabled && it.accessGranted } == true
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

    Text(stringRes("notifications"), style = MaterialTheme.typography.titleMedium)
    when {
        !NotificationSyncFeature.ENABLED -> {
            SimpleNotificationCounts(null)
            Text(
                stringRes("simple_notifications_coming_soon"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        !device.online || unreachable -> Text(
            stringRes("simple_nme_unreachable"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        !checked -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        needsUpdate || status?.supported != true -> Text(
            stringRes("simple_nme_update_phone"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ready -> SimpleNotificationCounts(counts) { kind ->
            snapshot = NotificationThreads.threads(NotificationInbox.from(items, device.deviceId), kind)
            listKind = kind
        }
        else -> {
            Text(stringRes("simple_nme_explainer"), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                scope.launch {
                    val entity = FileApexServices.deviceRepository.getDevice(device.deviceId)
                    val sent = entity != null &&
                        NotificationBroadcaster.requestPhonePrompt(entity, PhoneSettingsPage.BROADCAST_NOTIFICATIONS)
                    promptSent = sent
                    promptFailed = !sent
                }
            }) { Text(stringRes("simple_nme_enable")) }
            if (promptSent) {
                Text(
                    stringRes("simple_nme_prompt_sent"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringRes("simple_nme_waiting"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (promptFailed) {
                Text(
                    stringRes("simple_prompt_failed"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun SimpleNotificationCounts(counts: NotificationCounts?, onOpen: ((NotificationKind) -> Unit)? = null) {
    fun line(label: String, count: Int?, kind: NotificationKind) = Triple(label, count, kind)
    listOf(
        line(stringRes("simple_messages"), counts?.messages, NotificationKind.MESSAGE),
        line(stringRes("simple_emails"), counts?.emails, NotificationKind.EMAIL),
        line(stringRes("simple_other_notifications"), counts?.other, NotificationKind.OTHER)
    ).forEach { (label, count, kind) ->
        SimpleInfoLine(
            label,
            count?.toString() ?: "-",
            onClick = if (onOpen != null && (count ?: 0) > 0) ({ onOpen(kind) }) else null
        )
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
