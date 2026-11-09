package com.fileapex.platform

import kotlinx.serialization.Serializable

/** Device row pushed to desktop tray UIs (macOS popover + Windows left-click menu). [iconKind] is a `DeviceIconKind` name, or [FRIEND_ICON_KIND]. */
@Serializable
data class DesktopTrayDeviceSnapshot(
    val id: String,
    val name: String,
    val isOnline: Boolean,
    val iconKind: String = "AndroidPhone",
    /** Text key for the device type, shown as the tooltip of the type icon. */
    val iconKey: String = "icon_android_phone",
    /** Result of the last Check Batteries tap, such as "82% 14:02"; empty when there is none. */
    val batteryText: String = "",
    /** [TrayBatteryTone] name for [batteryText]; empty when there is none. */
    val batteryTone: String = ""
)

const val FRIEND_ICON_KIND = "Friend"

/** Ids the Devices tab is already showing as Ready. */
fun shareReadyDeviceIds(snapshots: List<DesktopTrayDeviceSnapshot>): List<String> =
    snapshots.filter { it.isOnline && !isFriendTrayId(it.id) }.map { it.id }
