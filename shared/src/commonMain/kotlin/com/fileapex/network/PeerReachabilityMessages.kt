package com.fileapex.network

/**
 * User-facing copy when direct LAN is unavailable — shared across transfer, navigation,
 * and Device Details entry points.
 */
object PeerReachabilityMessages {
    fun fileNavigationOffWifi(): String =
        "File navigation unavailable until back on local Wi‑Fi."

    fun fileTransferOffWifiQueued(deviceName: String): String =
        "$deviceName is off local Wi‑Fi — transfer queued until back on Wi‑Fi."

    fun fileTransferOffWifiQueuedMultiple(deviceNames: List<String>): String {
        if (deviceNames.isEmpty()) return ""
        val targets = if (deviceNames.size == 1) {
            deviceNames.first()
        } else {
            deviceNames.joinToString(", ")
        }
        return "$targets off local Wi‑Fi — transfer(s) queued until back on Wi‑Fi."
    }

    fun fileTransferOffWifiDriveNotReady(deviceNames: List<String>): String {
        val targets = when {
            deviceNames.isEmpty() -> "Device"
            deviceNames.size == 1 -> deviceNames.first()
            else -> deviceNames.joinToString(", ")
        }
        return "$targets is off local Wi‑Fi. Enable Cellular → Google Drive Relay on this device to send now."
    }

    fun localWifiRequired(): String =
        "Connect to Wi‑Fi or Ethernet to reach paired devices on your local network."

    fun peerOffline(): String = "Unable to reach device"
}
