package com.fileapex.network

import com.fileapex.i18n.AppI18n

/**
 * User-facing copy when direct LAN is unavailable — shared across transfer, navigation,
 * and Device Details entry points.
 */
object PeerReachabilityMessages {
    fun fileNavigationOffWifi(): String = AppI18n.t("file_nav_off_wifi")

    fun fileTransferOffWifiQueued(deviceName: String): String =
        AppI18n.t("file_transfer_off_wifi_queued", deviceName)

    fun fileTransferOffWifiQueuedMultiple(deviceNames: List<String>): String {
        if (deviceNames.isEmpty()) return ""
        val targets = if (deviceNames.size == 1) {
            deviceNames.first()
        } else {
            deviceNames.joinToString(", ")
        }
        return AppI18n.t("file_transfer_off_wifi_queued_multiple", targets)
    }

    fun fileTransferOffWifiDriveNotReady(deviceNames: List<String>): String {
        val targets = when {
            deviceNames.isEmpty() -> AppI18n.t("file_generic")
            deviceNames.size == 1 -> deviceNames.first()
            else -> deviceNames.joinToString(", ")
        }
        return AppI18n.t("file_transfer_off_wifi_drive_not_ready", targets)
    }

    fun localWifiRequired(): String = AppI18n.t("local_wifi_required")

    fun peerOffline(): String = AppI18n.t("unable_to_reach_device")
}
