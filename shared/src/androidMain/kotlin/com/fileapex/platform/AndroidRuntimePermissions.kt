package com.fileapex.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Dangerous / runtime Android permissions declared in the app manifest.
 *
 * First-run grants are handled one-at-a-time in onboarding — never bulk-requested here.
 *
 * [Manifest.permission.READ_PHONE_STATE] is excluded — requested only when the user
 * enables Settings → Device Details → Allow over cellular.
 */
object AndroidRuntimePermissions {
    fun hasNearbyWifiDevices(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return isGranted(context, Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    fun hasPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
    }

    fun hasReadPhoneState(context: Context): Boolean =
        isGranted(context, Manifest.permission.READ_PHONE_STATE)

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
