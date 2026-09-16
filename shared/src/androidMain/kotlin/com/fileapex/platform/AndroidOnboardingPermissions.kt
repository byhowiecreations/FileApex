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
    const val ID_OEM_BACKGROUND_PERSISTENCE = "oem_background_persistence"

    private const val PREFS_NAME = "fileapex_onboarding_oem"
    private const val KEY_OEM_PERSISTENCE_DONE = "oem_persistence_completed"

    fun isOemPersistenceCompleted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_OEM_PERSISTENCE_DONE, false)
    }

    fun markOemPersistenceCompleted(context: Context, completed: Boolean = true) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_OEM_PERSISTENCE_DONE, completed).apply()
    }

    fun isOemPersistenceRequired(context: Context): Boolean {
        val vendor = detectOemVendor(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty())
        return when (vendor) {
            OemVendor.Pixel, OemVendor.Other -> false
            else -> true
        }
    }

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
        if (isOemPersistenceRequired(context)) {
            val vendor = detectOemVendor(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty())
            val (titleKey, reasonKey, deniedHintKey) = oemKeysForVendor(vendor)
            add(
                OnboardingPermissionStep(
                    id = ID_OEM_BACKGROUND_PERSISTENCE,
                    titleKey = titleKey,
                    reasonKey = reasonKey,
                    deniedHintKey = deniedHintKey,
                    granted = isOemPersistenceCompleted(context)
                )
            )
        }
    }

    fun isComplete(steps: List<OnboardingPermissionStep>): Boolean =
        steps.all { it.granted }

    private fun isBatteryOptimizationRestricted(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun oemKeysForVendor(vendor: OemVendor): Triple<String, String, String> {
        val titleKey = when (vendor) {
            OemVendor.Honor -> "onboard_perm_oem_honor"
            OemVendor.Huawei -> "onboard_perm_oem_huawei"
            OemVendor.Xiaomi -> "onboard_perm_oem_xiaomi"
            OemVendor.Poco -> "onboard_perm_oem_poco"
            OemVendor.Oppo -> "onboard_perm_oem_oppo"
            OemVendor.OnePlus -> "onboard_perm_oem_oneplus"
            OemVendor.Samsung -> "onboard_perm_oem_samsung"
            OemVendor.Motorola -> "onboard_perm_oem_motorola"
            OemVendor.Vivo -> "onboard_perm_oem_vivo"
            OemVendor.Tcl -> "onboard_perm_oem_tcl"
            OemVendor.Asus -> "onboard_perm_oem_asus"
            OemVendor.Transsion -> "onboard_perm_oem_transsion"
            else -> "onboard_perm_oem_generic"
        }
        val reasonKey = when (vendor) {
            OemVendor.Honor -> "onboard_reason_oem_honor"
            OemVendor.Huawei -> "onboard_reason_oem_huawei"
            OemVendor.Xiaomi -> "onboard_reason_oem_xiaomi"
            OemVendor.Poco -> "onboard_reason_oem_poco"
            OemVendor.Oppo -> "onboard_reason_oem_oppo"
            OemVendor.OnePlus -> "onboard_reason_oem_oneplus"
            OemVendor.Samsung -> "onboard_reason_oem_samsung"
            OemVendor.Motorola -> "onboard_reason_oem_motorola"
            OemVendor.Vivo -> "onboard_reason_oem_vivo"
            OemVendor.Tcl -> "onboard_reason_oem_tcl"
            OemVendor.Asus -> "onboard_reason_oem_asus"
            OemVendor.Transsion -> "onboard_reason_oem_transsion"
            else -> "onboard_reason_oem_generic"
        }
        val deniedHintKey = "onboard_oem_denied"
        return Triple(titleKey, reasonKey, deniedHintKey)
    }
}
