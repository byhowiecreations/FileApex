package com.omninode.platform

import android.annotation.SuppressLint
import android.app.Activity
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

/**
 * SSOT for battery exemption, unused-app hibernation, and OEM background-app guidance.
 */
object BackgroundPersistenceGuidance {
    private const val TAG = "BackgroundPersistence"

    fun isBatteryOptimizationRestricted(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * True when unused-app restrictions (permission auto-reset and/or app hibernation) are active.
     * Uses [PackageManagerCompat.getUnusedAppRestrictionsStatus] via reflection so Kotlin does not
     * need Guava [com.google.common.util.concurrent.ListenableFuture] on its compile classpath.
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

    fun isMotorolaDevice(): Boolean =
        Build.MANUFACTURER.equals("motorola", ignoreCase = true)

    @SuppressLint("BatteryLife")
    fun createBatteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    fun createUnusedAppRestrictionsIntent(context: Context): Intent =
        IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName)

    /** Best-effort deep link into Motorola background-app / Smart use management. */
    fun createMotorolaBackgroundAppsIntent(context: Context): Intent? {
        val candidates = listOf(
            ComponentName(
                "com.motorola.batterycare",
                "com.motorola.batterycare.ui.activity.MainActivity"
            ),
            ComponentName(
                "com.motorola.batterycare",
                "com.motorola.batterycare.ui.activity.BatteryCareActivity"
            ),
            ComponentName(
                "com.motorola.ccc",
                "com.motorola.ccc.mainactivity.MainActivity"
            )
        )
        val packageManager = context.packageManager
        for (component in candidates) {
            val intent = Intent().setComponent(component)
            if (intent.resolveActivity(packageManager) != null) {
                return intent
            }
        }
        return null
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

    fun launchMotorolaBackgroundAppsSettings(activity: Activity) {
        val motorolaIntent = createMotorolaBackgroundAppsIntent(activity)
        if (motorolaIntent != null) {
            runCatching { activity.startActivity(motorolaIntent) }
                .onFailure { error ->
                    Log.w(TAG, "Motorola background-apps intent failed :: ${error.message}")
                    launchBatteryOptimizationRequest(activity)
                }
            return
        }
        Log.i(TAG, "Motorola background-apps deep link unavailable — opening battery settings")
        runCatching {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.onFailure {
            launchAppDetailsSettings(activity)
        }
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
