package com.fileapex.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Dangerous / runtime Android permissions declared in the app manifest.
 * Requested on every cold start and resume while still denied so upgraders
 * are prompted for permissions added after their original install.
 */
object AndroidRuntimePermissions {
    fun missingPermissions(context: Context): Array<String> {
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                addIfMissing(context, Manifest.permission.POST_NOTIFICATIONS)
                addIfMissing(context, Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            addIfMissing(context, Manifest.permission.READ_PHONE_STATE)
        }.toTypedArray()
    }

    fun hasNearbyWifiDevices(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return isGranted(context, Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    fun hasReadPhoneState(context: Context): Boolean =
        isGranted(context, Manifest.permission.READ_PHONE_STATE)

    private fun MutableList<String>.addIfMissing(context: Context, permission: String) {
        if (!isGranted(context, permission)) {
            add(permission)
        }
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
