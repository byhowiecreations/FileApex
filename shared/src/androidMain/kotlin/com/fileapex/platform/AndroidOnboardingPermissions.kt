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
                reason = com.fileapex.i18n.AppI18n.t("onboard_storage_reason"),
                deniedHint = com.fileapex.i18n.AppI18n.t("onboard_storage_denied"),
                granted = hasAllFilesAccess(context)
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                OnboardingPermissionStep(
                    id = ID_NEARBY_WIFI_DEVICES,
                    permissionName = "NEARBY_WIFI_DEVICES",
                    reason = com.fileapex.i18n.AppI18n.t("onboard_nearby_reason"),
                    deniedHint = com.fileapex.i18n.AppI18n.t("onboard_nearby_denied"),
                    granted = AndroidRuntimePermissions.hasNearbyWifiDevices(context)
                )
            )
            add(
                OnboardingPermissionStep(
                    id = ID_POST_NOTIFICATIONS,
                    permissionName = "POST_NOTIFICATIONS",
                    reason = com.fileapex.i18n.AppI18n.t("onboard_notify_reason"),
                    deniedHint = com.fileapex.i18n.AppI18n.t("onboard_notify_denied"),
                    granted = AndroidRuntimePermissions.hasPostNotifications(context)
                )
            )
        }
        add(
            OnboardingPermissionStep(
                id = ID_IGNORE_BATTERY_OPTIMIZATIONS,
                permissionName = "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                reason = com.fileapex.i18n.AppI18n.t("onboard_battery_reason"),
                deniedHint = com.fileapex.i18n.AppI18n.t("onboard_battery_denied"),
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
