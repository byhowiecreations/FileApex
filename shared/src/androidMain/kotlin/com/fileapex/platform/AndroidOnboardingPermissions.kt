package com.fileapex.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Builds the ordered onboarding grant list — runtime/special permissions only.
 */
object AndroidOnboardingPermissions {
    const val ID_MANAGE_EXTERNAL_STORAGE = "manage_external_storage"
    const val ID_NEARBY_WIFI_DEVICES = "nearby_wifi_devices"
    const val ID_POST_NOTIFICATIONS = "post_notifications"
    const val ID_IGNORE_BATTERY_OPTIMIZATIONS = "request_ignore_battery_optimizations"

    fun buildSteps(context: Context): List<OnboardingPermissionStep> = buildList {
        add(
            OnboardingPermissionStep(
                id = ID_MANAGE_EXTERNAL_STORAGE,
                permissionName = "MANAGE_EXTERNAL_STORAGE",
                reason = "Browse folders on this device and share files with paired devices on your Wi‑Fi.",
                deniedHint = "File access is required — FileApex cannot browse or send local files without it.",
                granted = hasAllFilesAccess(context)
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                OnboardingPermissionStep(
                    id = ID_NEARBY_WIFI_DEVICES,
                    permissionName = "NEARBY_WIFI_DEVICES",
                    reason = "Discover and connect to other FileApex devices on your local network.",
                    deniedHint = "Nearby device access is required for LAN pairing and file sharing.",
                    granted = AndroidRuntimePermissions.hasNearbyWifiDevices(context)
                )
            )
            add(
                OnboardingPermissionStep(
                    id = ID_POST_NOTIFICATIONS,
                    permissionName = "POST_NOTIFICATIONS",
                    reason = "Show the persistent share-server notification while FileApex runs in the background.",
                    deniedHint = "Notifications keep the LAN share service alive — allow them for reliable background sharing.",
                    granted = AndroidRuntimePermissions.hasPostNotifications(context)
                )
            )
        }
        add(
            OnboardingPermissionStep(
                id = ID_IGNORE_BATTERY_OPTIMIZATIONS,
                permissionName = "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                reason = "Let FileApex keep sharing files when the screen is off or you switch apps.",
                deniedHint = "Without unrestricted battery, Android may stop background file sharing.",
                granted = !isBatteryOptimizationRestricted(context)
            )
        )
    }

    fun isComplete(steps: List<OnboardingPermissionStep>): Boolean =
        steps.all { it.granted }

    private fun isBatteryOptimizationRestricted(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val read = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            val write = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            read && write
        }
}
