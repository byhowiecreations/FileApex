package com.fileapex.platform

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.net.toUri
import com.fileapex.data.settings.androidAppContextOrNull

actual object ClipboardAccessibilitySettings {
    private const val OP_ACCESS_RESTRICTED_SETTINGS = "android:access_restricted_settings"

    actual fun openSystemPrompt() {
        val context = androidAppContextOrNull() ?: return
        val component = ComponentName(context, ClipboardAccessibilityService::class.java)
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(":settings:fragment_args_key", component.flattenToString())
        }
        context.startActivity(intent)
    }

    actual fun openAppInfo() {
        val context = androidAppContextOrNull() ?: return
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    actual fun isServiceEnabled(): Boolean {
        val context = androidAppContextOrNull() ?: return false
        val expected = ComponentName(context, ClipboardAccessibilityService::class.java).flattenToString()
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val enabled = manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .orEmpty()
        val listed = enabled.any { info ->
            val id = info.resolveInfo?.serviceInfo?.let { service ->
                ComponentName(service.packageName, service.name).flattenToString()
            }.orEmpty()
            id.equals(expected, ignoreCase = true)
        }
        if (listed) return true
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        if (raw.isBlank()) return false
        return raw.split(':', ';').any { token ->
            val id = token.trim()
            id.equals(expected, ignoreCase = true) ||
                id.endsWith("/${ClipboardAccessibilityService::class.java.name}", ignoreCase = true) ||
                id.endsWith("/ClipboardAccessibilityService", ignoreCase = true)
        }
    }

    actual fun isRestrictedSettingsBlocked(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (isServiceEnabled()) return false
        val context = androidAppContextOrNull() ?: return false
        val mode = restrictedSettingsMode(context)
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> false
            AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> true
            else -> isSideloaded(context)
        }
    }

    private fun restrictedSettingsMode(context: Context): Int {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return AppOpsManager.MODE_ALLOWED
        return runCatching {
            appOps.checkOpNoThrow(
                OP_ACCESS_RESTRICTED_SETTINGS,
                Process.myUid(),
                context.packageName
            )
        }.getOrDefault(AppOpsManager.MODE_ALLOWED)
    }

    private fun isSideloaded(context: Context): Boolean {
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = context.packageManager.getInstallSourceInfo(context.packageName)
                info.installingPackageName ?: info.initiatingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()
        return installer != "com.android.vending"
    }
}
