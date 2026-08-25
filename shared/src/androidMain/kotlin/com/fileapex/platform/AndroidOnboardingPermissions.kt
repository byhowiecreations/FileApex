package com.fileapex.platform

import android.content.Context
import android.os.Build
import android.os.PowerManager

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
                titleKey = "onboard_perm_storage",
                reasonKey = "onboard_storage_reason",
                deniedHintKey = "onboard_storage_denied",
                granted = AndroidStorageAccess.hasFullAccess(context)
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                OnboardingPermissionStep(
                    id = ID_NEARBY_WIFI_DEVICES,
                    titleKey = "onboard_perm_nearby",
                    reasonKey = "onboard_nearby_reason",
                    deniedHintKey = "onboard_nearby_denied",
                    granted = AndroidRuntimePermissions.hasNearbyWifiDevices(context)
                )
            )
            add(
                OnboardingPermissionStep(
                    id = ID_POST_NOTIFICATIONS,
                    titleKey = "onboard_perm_notify",
                    reasonKey = "onboard_notify_reason",
                    deniedHintKey = "onboard_notify_denied",
                    granted = AndroidRuntimePermissions.hasPostNotifications(context)
                )
            )
        }
        add(
            OnboardingPermissionStep(
                id = ID_IGNORE_BATTERY_OPTIMIZATIONS,
                titleKey = "onboard_perm_battery",
                reasonKey = "onboard_battery_reason",
                deniedHintKey = "onboard_battery_denied",
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
}
