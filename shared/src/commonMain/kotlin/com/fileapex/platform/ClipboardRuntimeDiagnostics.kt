package com.fileapex.platform

data class ClipboardRuntimeSnapshot(
    val accessibilityBound: Boolean,
    val accessibilityListed: Boolean,
    val batteryWhitelisted: Boolean,
    val notificationsEnabled: Boolean,
    val restrictedSettingsRelevant: Boolean,
    val restrictedSettingsBlocked: Boolean,
    val shizukuActive: Boolean,
    val shizukuInstalled: Boolean,
    val shizukuRunning: Boolean
)

expect object ClipboardRuntimeDiagnostics {
    fun snapshot(): ClipboardRuntimeSnapshot

    fun requestShizukuPermission()

    fun openShizuku()

    fun activateShizuku()

    fun openNotificationSettings()
}
