package com.fileapex.platform

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.fileapex.data.settings.androidAppContextOrNull

actual object ClipboardRuntimeDiagnostics {
    actual fun snapshot(): ClipboardRuntimeSnapshot {
        val context = androidAppContextOrNull()
        return ClipboardRuntimeSnapshot(
            accessibilityBound = ClipboardAccessibilityHealth.isBound(),
            accessibilityListed = ClipboardAccessibilityHealth.isListed(),
            batteryWhitelisted = context?.let { isBatteryWhitelisted(it) } ?: false,
            notificationsEnabled = context?.let { notificationsEnabled(it) } ?: false,
            restrictedSettingsRelevant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            restrictedSettingsBlocked = ClipboardAccessibilitySettings.isRestrictedSettingsBlocked(),
            shizukuActive = ClipboardShizukuAccess.isReady(),
            shizukuInstalled = ClipboardShizukuAccess.isInstalled(),
            shizukuRunning = ClipboardShizukuAccess.isRunning()
        )
    }

    actual fun requestShizukuPermission() {
        if (!ClipboardShizukuAccess.isInstalled()) {
            ClipboardShizukuAccess.openManager()
            return
        }
        ClipboardShizukuAccess.requestPermission()
    }

    actual fun openShizuku() {
        ClipboardShizukuAccess.openManager()
    }

    actual fun activateShizuku() {
        ClipboardShizukuAccess.activate()
    }

    actual fun openNotificationSettings() {
        val context = androidAppContextOrNull() ?: return
        val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun isBatteryWhitelisted(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun notificationsEnabled(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.areNotificationsEnabled()
    }
}
