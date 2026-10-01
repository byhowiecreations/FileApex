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
    private const val KEY_BATTERY_ACKNOWLEDGED = "battery_optimization_acknowledged"
    private const val KEY_INITIAL_ONBOARDING_INITIALIZED = "initial_onboarding_initialized"

    fun isBatteryOptimizationAcknowledged(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_BATTERY_ACKNOWLEDGED, false)
    }

    fun markBatteryOptimizationAcknowledged(context: Context, acknowledged: Boolean = true) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_BATTERY_ACKNOWLEDGED, acknowledged)
            .apply()
    }

    fun isOemPersistenceCompleted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_OEM_PERSISTENCE_DONE, false)) {
            return true
        }
        val vendor = detectOemVendor(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty())
        // TCL, Motorola, and Samsung rely on standard Android battery optimization settings.
        // If battery optimization is already unrestricted, the OEM step is already satisfied.
        if (vendor == OemVendor.Tcl || vendor == OemVendor.Motorola || vendor == OemVendor.Samsung) {
            if (!isBatteryOptimizationRestricted(context) || isBatteryOptimizationAcknowledged(context)) {
                markOemPersistenceCompleted(context, true)
                return true
            }
        }
        // Migration for existing installations: If storage access was already granted before this session,
        // the user has already completed initial onboarding and configured their device.
        if (!prefs.getBoolean(KEY_INITIAL_ONBOARDING_INITIALIZED, false)) {
            if (AndroidStorageAccess.hasFullAccess(context)) {
                prefs.edit()
                    .putBoolean(KEY_OEM_PERSISTENCE_DONE, true)
                    .putBoolean(KEY_BATTERY_ACKNOWLEDGED, true)
                    .putBoolean(KEY_INITIAL_ONBOARDING_INITIALIZED, true)
                    .putBoolean(KEY_ONBOARDING_DISMISSED, true)
                    .apply()
                return true
            } else {
                prefs.edit().putBoolean(KEY_INITIAL_ONBOARDING_INITIALIZED, true).apply()
            }
        }
        return false
    }

    fun markOemPersistenceCompleted(context: Context, completed: Boolean = true) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_OEM_PERSISTENCE_DONE, completed)
            .putBoolean(KEY_INITIAL_ONBOARDING_INITIALIZED, true)
            .apply()
    }

    fun isOemPersistenceRequired(context: Context): Boolean {
        val vendor = detectOemVendor(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty())
        return when (vendor) {
            // Only prompt for OEMs that have separate, proprietary auto-start / app launch managers.
            // Vendors like TCL, Motorola, and Samsung rely on standard Android App Battery Usage,
            // which is already handled by the Unrestricted Battery step.
            OemVendor.Honor,
            OemVendor.Huawei,
            OemVendor.Xiaomi,
            OemVendor.Poco,
            OemVendor.Oppo,
            OemVendor.OnePlus,
            OemVendor.Vivo,
            OemVendor.Asus,
            OemVendor.Transsion -> true
            else -> false
        }
    }

    fun buildSteps(context: Context): List<OnboardingPermissionStep> = buildList {
        add(
            OnboardingPermissionStep(
                id = ID_MANAGE_EXTERNAL_STORAGE,
                titleKey = "onboard_perm_storage",
                reasonKey = "onboard_storage_reason",
                deniedHintKey = "onboard_storage_denied",
                granted = AndroidStorageAccess.hasFullAccess(context),
                isOptional = true
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                OnboardingPermissionStep(
                    id = ID_POST_NOTIFICATIONS,
                    titleKey = "onboard_perm_notify",
                    reasonKey = "onboard_notify_reason",
                    deniedHintKey = "onboard_notify_denied",
                    granted = AndroidRuntimePermissions.hasPostNotifications(context),
                    isOptional = true
                )
            )
        }
        val batteryGranted = !isBatteryOptimizationRestricted(context) || isBatteryOptimizationAcknowledged(context)
        add(
            OnboardingPermissionStep(
                id = ID_IGNORE_BATTERY_OPTIMIZATIONS,
                titleKey = "onboard_perm_battery",
                reasonKey = "onboard_battery_reason",
                deniedHintKey = "onboard_battery_denied",
                granted = batteryGranted,
                isOptional = true
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
                    granted = isOemPersistenceCompleted(context),
                    isOptional = true
                )
            )
        }
    }

    private const val KEY_SKIPPED_OPTIONAL_STEPS = "skipped_optional_steps"
    private const val KEY_ONBOARDING_DISMISSED = "onboarding_dismissed"

    fun markStepSkipped(context: Context, stepId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_SKIPPED_OPTIONAL_STEPS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(stepId)
        prefs.edit().putStringSet(KEY_SKIPPED_OPTIONAL_STEPS, current).apply()
    }

    fun markOnboardingDismissed(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ONBOARDING_DISMISSED, true).apply()
    }

    fun markAllOptionalStepsSkipped(context: Context, steps: List<OnboardingPermissionStep>) {
        markOnboardingDismissed(context)
        markBatteryOptimizationAcknowledged(context, true)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_SKIPPED_OPTIONAL_STEPS, emptySet())?.toMutableSet() ?: mutableSetOf()
        steps.filter { it.isOptional }.forEach { current.add(it.id) }
        prefs.edit().putStringSet(KEY_SKIPPED_OPTIONAL_STEPS, current).apply()
    }

    fun isComplete(context: Context, steps: List<OnboardingPermissionStep>): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ONBOARDING_DISMISSED, false)) {
            return true
        }
        val skipped = prefs.getStringSet(KEY_SKIPPED_OPTIONAL_STEPS, emptySet()) ?: emptySet()
        val batteryAck = prefs.getBoolean(KEY_BATTERY_ACKNOWLEDGED, false)
        return steps.all { step ->
            step.granted ||
                (step.id == ID_IGNORE_BATTERY_OPTIMIZATIONS && batteryAck) ||
                (step.isOptional && step.id in skipped)
        }
    }

    fun isComplete(steps: List<OnboardingPermissionStep>): Boolean =
        steps.filterNot { it.isOptional }.all { it.granted }

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
