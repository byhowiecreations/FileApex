package com.fileapex.platform

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants

object BackgroundPersistenceGuidance {
    private const val TAG = "BackgroundPersistence"
    private const val APP_BATTERY_USAGE_ACTIVITY =
        "com.android.settings.Settings\$AppBatteryUsageActivity"
    private const val EXTRA_PACKAGE = "package"

    data class Snapshot(
        val batteryOptimizationRestricted: Boolean,
        val backgroundRestricted: Boolean,
        val unusedAppRestrictionsActive: Boolean,
        val oemGuidance: OemBackgroundGuidance?
    ) {
        val persistenceRestricted: Boolean
            get() = batteryOptimizationRestricted || backgroundRestricted
    }

    fun evaluate(context: Context): Snapshot {
        val vendor = detectOemVendor()
        return Snapshot(
            batteryOptimizationRestricted = isBatteryOptimizationRestricted(context),
            backgroundRestricted = isBackgroundRestricted(context),
            unusedAppRestrictionsActive = isUnusedAppRestrictionsActive(context),
            oemGuidance = OemBackgroundGuidance.forVendor(vendor)
        )
    }

    fun isBatteryOptimizationRestricted(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Android "App battery usage" / background restriction (API 28+). */
    fun isBackgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.isBackgroundRestricted
    }

    /**
     * True when unused-app restrictions (permission auto-reset and/or app hibernation) are active.
     */
    fun isUnusedAppRestrictionsActive(context: Context): Boolean {
        val status = runCatching { queryUnusedAppRestrictionsStatus(context) }
            .getOrElse { error ->
                Log.w(TAG, "Unused-app restrictions check failed :: ${error.message}")
                return false
            }
        return status == UnusedAppRestrictionsConstants.API_31 ||
            status == UnusedAppRestrictionsConstants.API_30 ||
            status == UnusedAppRestrictionsConstants.API_30_BACKPORT
    }

    private fun queryUnusedAppRestrictionsStatus(context: Context): Int {
        val future = PackageManagerCompat::class.java
            .getMethod("getUnusedAppRestrictionsStatus", Context::class.java)
            .invoke(null, context)
            ?: error("PackageManagerCompat returned null future")
        return future.javaClass.getMethod("get").invoke(future) as Int
    }

    fun detectOemVendor(): OemVendor =
        com.fileapex.platform.detectOemVendor(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty())

    @SuppressLint("BatteryLife")
    fun createBatteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    fun createUnusedAppRestrictionsIntent(context: Context): Intent =
        IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName)

    /** Best-effort per-app App battery usage screen (Android 14+ on most OEMs). */
    fun createAppBatteryUsageIntent(context: Context): Intent? {
        val packageName = context.packageName
        val candidates = listOf(
            Intent().setComponent(
                ComponentName("com.android.settings", APP_BATTERY_USAGE_ACTIVITY)
            ).putExtra(EXTRA_PACKAGE, packageName),
            Intent().setComponent(
                ComponentName("com.android.settings", APP_BATTERY_USAGE_ACTIVITY)
            ).putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
        )
        val packageManager = context.packageManager
        return candidates.firstOrNull { intent -> intent.resolveActivity(packageManager) != null }
    }

    /** Best-effort OEM-specific battery / auto-start screens. */
    fun createOemBackgroundIntent(context: Context, vendor: OemVendor): Intent? {
        val packageManager = context.packageManager
        val components = oemBackgroundComponents(vendor)
        for (component in components) {
            val intent = Intent().setComponent(component)
            if (intent.resolveActivity(packageManager) != null) {
                return intent
            }
        }
        val actionFallback = oemBackgroundActionFallback(vendor) ?: return null
        return actionFallback.takeIf { it.resolveActivity(packageManager) != null }
    }

    private fun oemBackgroundComponents(vendor: OemVendor): List<ComponentName> = when (vendor) {
        OemVendor.Motorola -> listOf(
            ComponentName(
                "com.motorola.batterycare",
                "com.motorola.batterycare.ui.activity.MainActivity"
            ),
            ComponentName(
                "com.motorola.batterycare",
                "com.motorola.batterycare.ui.activity.BatteryCareActivity"
            )
        )
        OemVendor.Samsung -> listOf(
            ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity"
            ),
            ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
        )
        OemVendor.Xiaomi, OemVendor.Poco -> listOf(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        )
        OemVendor.Oppo, OemVendor.OnePlus -> listOf(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            ComponentName(
                "com.oplus.safecenter",
                "com.oplus.safecenter.permission.startup.StartupAppListActivity"
            )
        )
        OemVendor.Vivo -> listOf(
            ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            ),
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
        )
        OemVendor.Honor -> listOf(
            ComponentName(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            ComponentName(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.power.ui.HwPowerManagerActivity"
            )
        )
        OemVendor.Huawei -> listOf(
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"
            )
        )
        OemVendor.Pixel, OemVendor.Other -> emptyList()
    }

    private fun oemBackgroundActionFallback(vendor: OemVendor): Intent? = when (vendor) {
        OemVendor.Honor -> Intent("hihonor.intent.action.HSM_STARTUPAPP_MANAGER")
            .setPackage("com.hihonor.systemmanager")
        OemVendor.Huawei -> Intent("huawei.intent.action.HSM_STARTUPAPP_MANAGER")
            .setPackage("com.huawei.systemmanager")
        else -> null
    }

    @SuppressLint("BatteryLife")
    fun launchBatteryOptimizationRequest(activity: Activity) {
        if (!isBatteryOptimizationRestricted(activity)) return
        runCatching { activity.startActivity(createBatteryOptimizationIntent(activity)) }
            .onFailure { error ->
                Log.w(TAG, "Battery exemption intent failed :: ${error.message}")
                runCatching {
                    activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }.onFailure { fallbackError ->
                    Log.w(TAG, "Battery settings fallback failed :: ${fallbackError.message}")
                    launchAppDetailsSettings(activity)
                }
            }
    }

    fun launchUnusedAppRestrictionsSettings(activity: Activity) {
        runCatching {
            activity.startActivity(createUnusedAppRestrictionsIntent(activity))
        }.onFailure { error ->
            Log.w(TAG, "Unused-app restrictions intent failed :: ${error.message}")
            launchAppDetailsSettings(activity)
        }
    }

    /**
     * Opens the best available screen for OEM background / app battery usage setup.
     * Order: OEM deep link → App battery usage → battery optimization dialog → app details.
     */
    fun launchBackgroundPersistenceSettings(activity: Activity, snapshot: Snapshot = evaluate(activity)) {
        snapshot.oemGuidance?.vendor?.let { vendor ->
            createOemBackgroundIntent(activity, vendor)?.let { oemIntent ->
                if (runCatching { activity.startActivity(oemIntent); true }.getOrDefault(false)) {
                    return
                }
            }
        }
        createAppBatteryUsageIntent(activity)?.let { batteryUsageIntent ->
            if (runCatching { activity.startActivity(batteryUsageIntent); true }.getOrDefault(false)) {
                return
            }
        }
        if (snapshot.batteryOptimizationRestricted) {
            launchBatteryOptimizationRequest(activity)
            return
        }
        launchAppDetailsSettings(activity)
    }

    fun launchAppBatteryUsageSettings(activity: Activity) {
        createAppBatteryUsageIntent(activity)?.let { intent ->
            runCatching { activity.startActivity(intent) }
                .onFailure { error ->
                    Log.w(TAG, "App battery usage intent failed :: ${error.message}")
                    launchAppDetailsSettings(activity)
                }
            return
        }
        launchAppDetailsSettings(activity)
    }

    fun launchAppDetailsSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        runCatching { activity.startActivity(intent) }
            .onFailure { error ->
                Log.w(TAG, "App details intent failed :: ${error.message}")
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
    }
}

fun BackgroundPersistenceGuidance.Snapshot.toUiState(): BackgroundPersistenceUiState =
    BackgroundPersistenceUiState(
        batteryOptimizationRestricted = batteryOptimizationRestricted,
        backgroundRestricted = backgroundRestricted,
        unusedAppRestrictionsActive = unusedAppRestrictionsActive,
        oemGuidance = oemGuidance
    )
