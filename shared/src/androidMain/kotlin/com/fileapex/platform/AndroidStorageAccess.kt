package com.fileapex.platform

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import androidx.core.content.ContextCompat

/**
 * All-files / legacy storage grant. Android 10 (MIUI 12) must not require
 * WRITE_EXTERNAL_STORAGE — that permission is maxSdkVersion 28 in the manifest, so
 * checkSelfPermission(WRITE) is always denied and onboarding never completes.
 */
object AndroidStorageAccess {
    private const val OPSTR_MANAGE_EXTERNAL_STORAGE = "android:manage_external_storage"

    fun hasFullAccess(context: Context): Boolean {
        val sdk = Build.VERSION.SDK_INT
        if (AndroidStorageAccessPolicy.usesManageAllFiles(sdk)) {
            if (Environment.isExternalStorageManager()) return true
            // MIUI / OEM: Settings grant can lag isExternalStorageManager() until process restart.
            return isOpAllowed(context, OPSTR_MANAGE_EXTERNAL_STORAGE)
        }
        if (!hasReadStorage(context)) return false
        if (!AndroidStorageAccessPolicy.requiresLegacyWrite(sdk)) return true
        return hasWriteStorage(context)
    }

    fun runtimePermissionsToRequest(): Array<String> =
        AndroidStorageAccessPolicy.runtimePermissionNames(Build.VERSION.SDK_INT)

    private fun hasReadStorage(context: Context): Boolean {
        return isRuntimeGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE) ||
            isPermissionOpAllowed(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasWriteStorage(context: Context): Boolean {
        return isRuntimeGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ||
            isPermissionOpAllowed(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun isRuntimeGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun isPermissionOpAllowed(context: Context, permission: String): Boolean {
        val op = AppOpsManager.permissionToOp(permission) ?: return false
        return isOpAllowed(context, op)
    }

    private fun isOpAllowed(context: Context, op: String): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val uid = Process.myUid()
        val pkg = context.packageName
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                appOps.checkOpNoThrow(op, uid, pkg, context.attributionTag)
            } else {
                appOps.checkOpNoThrow(op, uid, pkg)
            }
        }.getOrDefault(AppOpsManager.MODE_DEFAULT)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}

object AndroidStorageAccessPolicy {
    fun usesManageAllFiles(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.R

    fun requiresLegacyWrite(sdkInt: Int): Boolean = sdkInt < Build.VERSION_CODES.Q

    fun runtimePermissionNames(sdkInt: Int): Array<String> {
        if (usesManageAllFiles(sdkInt)) return emptyArray()
        return if (sdkInt >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }
}
