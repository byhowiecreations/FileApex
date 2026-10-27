package com.fileapex.ui.adaptive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.data.bulletin.BatteryLowAlert
import com.fileapex.data.bulletin.HeaderAlert
import com.fileapex.data.bulletin.HeaderAlerts
import com.fileapex.i18n.stringRes
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.presentation.devices.PeerBatteryFetch
import com.fileapex.ui.notes.rememberBatteryLowAlerts
import com.fileapex.ui.notes.rememberBulletinUnreadCount
import com.fileapex.ui.theme.fileApexChromeContentColor
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.delay

private const val ROTATE_MS = 4_000L

@Composable
fun HeaderAlertTicker(onOpenNotes: () -> Unit, modifier: Modifier = Modifier) {
    if (!usesDesktopFileSelection()) return
    val unread by rememberBulletinUnreadCount()
    val low by rememberBatteryLowAlerts()
    val alerts = remember(unread, low.size) { HeaderAlerts.active(unread, low.size) }
    var step by remember { mutableIntStateOf(0) }
    var batteryDialog by remember { mutableStateOf(false) }
    LaunchedEffect(alerts.size) {
        while (alerts.size > 1) {
            delay(ROTATE_MS)
            step++
        }
    }
    when (val alert = HeaderAlerts.at(alerts, step)) {
        is HeaderAlert.NewMessages -> AlertLine(
            stringRes(if (alert.count == 1) "alert_new_message_one" else "alert_new_messages", alert.count), modifier, onOpenNotes
        )
        is HeaderAlert.BatteryLow -> AlertLine(
            stringRes(if (alert.count == 1) "alert_battery_low_one" else "alert_battery_low", alert.count), modifier
        ) { batteryDialog = true }
        null -> Unit
    }
    if (batteryDialog && low.isNotEmpty()) BatteryLowDialog(low) { batteryDialog = false }
    if (batteryDialog && low.isEmpty()) batteryDialog = false
}

@Composable
private fun AlertLine(text: String, modifier: Modifier, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = fileApexChromeContentColor(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clickable(onClick = onClick).padding(horizontal = 12.dp)
    )
}

/** Each low phone is asked once, when the dialog opens, and only that phone. */
@Composable
private fun BatteryLowDialog(alerts: List<BatteryLowAlert>, onDismiss: () -> Unit) {
    val now: SnapshotStateMap<String, String> = remember { mutableStateMapOf() }
    LaunchedEffect(alerts.map { it.deviceId }) {
        for (alert in alerts) {
            val battery = PeerBatteryFetch.fetch(alert.deviceId)
            now[alert.deviceId] = battery?.levelPercent?.let { "$it%" } ?: ""
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringRes("battery_low_title")) },
        text = {
            Column {
                for (alert in alerts) {
                    val stamp = TimeUtils.noteListStamp(alert.reportedAtEpochMs)
                    val level = alert.levelAtReport?.let { "$it%" } ?: stringRes("battery_low_unknown_level")
                    Text(alert.deviceName, style = MaterialTheme.typography.titleSmall)
                    Text(stringRes("battery_low_reported", level, stamp.timeLabel), style = MaterialTheme.typography.bodyMedium)
                    val current = now[alert.deviceId]
                    Text(
                        text = when {
                            current == null -> stringRes("battery_low_checking")
                            current.isEmpty() -> stringRes("battery_low_no_answer")
                            else -> stringRes("battery_low_now", current)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringRes("close")) } }
    )
}
