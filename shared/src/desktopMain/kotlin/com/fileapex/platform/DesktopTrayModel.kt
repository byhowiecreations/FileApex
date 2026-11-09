package com.fileapex.platform

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.diagnostics.BatteryDiagnostics
import com.fileapex.presentation.DeviceHardwareProfile
import com.fileapex.presentation.DeviceIconProfile
import com.fileapex.presentation.resolveDeviceIconKind
import com.fileapex.ui.deviceIconDescriptionKey
import java.time.LocalTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

internal const val TRAY_MAX_COLUMNS = 3
private const val BATTERY_GOOD_FROM = 40
private const val BATTERY_LOW_FROM = 20
private const val BATTERY_ANSWER_TIMEOUT_MS = 8_000L

enum class TrayBatteryTone { Good, Low, Critical }

/** One tray row for a paired device, with the same device type the Devices screen shows. */
fun trayDeviceSnapshot(device: PairedDeviceEntity, online: Boolean): DesktopTrayDeviceSnapshot {
    val kind = resolveDeviceIconKind(
        DeviceIconProfile(
            deviceId = device.deviceId,
            deviceName = device.deviceName,
            hardware = DeviceHardwareProfile.from(device),
            clientVersion = device.clientVersion
        )
    )
    return DesktopTrayDeviceSnapshot(
        id = device.deviceId,
        name = device.deviceName,
        isOnline = online,
        iconKind = kind.name,
        iconKey = deviceIconDescriptionKey(kind)
    )
}

/** Every text the tray menus and the drop box show, in both the Mac and Windows views; a test checks each exists in all three languages. */
val TRAY_TEXT_KEYS: List<String> = listOf(
    "tray_quit", "tray_open", "tray_post", "tray_post_tip", "tray_post_hint", "tray_post_unavailable", "tray_app_tip",
    "tray_row_tip", "tray_online_tip", "tray_offline_tip", "tray_battery_tip", "tray_send_clipboard_tip",
    "tray_send_clipboard_offline_tip", "tray_browse_tip", "tray_browse_offline_tip", "tray_check_batteries_tip",
    "tray_send_multi_tip", "tray_multi_select_tip", "tray_icon_friend",
    "dropbox_zone_tip", "dropbox_destination_tip", "dropbox_remove_tip", "dropbox_cancel_tip", "dropbox_send_tip"
)

/** Online devices first, then offline ones, then friends; the order inside each group is kept. */
fun orderTrayDevices(devices: List<DesktopTrayDeviceSnapshot>): List<DesktopTrayDeviceSnapshot> {
    val (friends, own) = devices.partition { isFriendTrayId(it.id) }
    return own.filter { it.isOnline } + own.filterNot { it.isOnline } + friends
}

/** Columns needed so every row is visible without scrolling, between 1 and 3. */
fun trayColumnCount(rowCount: Int, availableHeightDp: Int, rowHeightDp: Int, chromeHeightDp: Int): Int {
    val usable = availableHeightDp - chromeHeightDp
    val rowsPerColumn = if (usable >= rowHeightDp) usable / rowHeightDp else 1
    val columns = (rowCount + rowsPerColumn - 1) / rowsPerColumn
    return columns.coerceIn(1, TRAY_MAX_COLUMNS)
}

fun trayBatteryTone(percent: Int): TrayBatteryTone = when {
    percent >= BATTERY_GOOD_FROM -> TrayBatteryTone.Good
    percent >= BATTERY_LOW_FROM -> TrayBatteryTone.Low
    else -> TrayBatteryTone.Critical
}

fun trayBatteryText(percent: Int, hour: Int, minute: Int): String =
    "$percent% ${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

data class TrayBatteryReading(val text: String, val tone: TrayBatteryTone)

/** Asks every online device (friends are not devices) for its battery at the same time; a device that does not answer in time or reports no level gets no reading. */
suspend fun readTrayBatteries(
    devices: List<DesktopTrayDeviceSnapshot>,
    fetch: suspend (String) -> BatteryDiagnostics?,
    now: () -> LocalTime
): Map<String, TrayBatteryReading> = coroutineScope {
    devices.filter { it.isOnline && !isFriendTrayId(it.id) }
        .map { device ->
            async {
                val level = withTimeoutOrNull(BATTERY_ANSWER_TIMEOUT_MS) { fetch(device.id) }?.levelPercent
                if (level == null) {
                    null
                } else {
                    val time = now()
                    device.id to TrayBatteryReading(trayBatteryText(level, time.hour, time.minute), trayBatteryTone(level))
                }
            }
        }
        .awaitAll()
        .filterNotNull()
        .toMap()
}

fun withTrayBatteries(
    devices: List<DesktopTrayDeviceSnapshot>,
    readings: Map<String, TrayBatteryReading>
): List<DesktopTrayDeviceSnapshot> = devices.map { device ->
    readings[device.id]?.let { device.copy(batteryText = it.text, batteryTone = it.tone.name) } ?: device
}
