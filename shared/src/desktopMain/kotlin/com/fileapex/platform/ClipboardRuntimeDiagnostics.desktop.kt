package com.fileapex.platform

actual object ClipboardRuntimeDiagnostics {
    actual fun snapshot(): ClipboardRuntimeSnapshot = ClipboardRuntimeSnapshot(
        accessibilityBound = false,
        accessibilityListed = false,
        batteryWhitelisted = true,
        notificationsEnabled = true,
        restrictedSettingsRelevant = false,
        restrictedSettingsBlocked = false,
        shizukuActive = false,
        shizukuInstalled = false,
        shizukuRunning = false
    )

    actual fun requestShizukuPermission() = Unit

    actual fun openShizuku() = Unit

    actual fun activateShizuku() = Unit

    actual fun openNotificationSettings() = Unit
}
