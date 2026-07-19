package com.omninode.update

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import com.omninode.data.settings.androidAppContextOrNull

actual object PlatformInstallPermission {
    actual fun ensureCanRequestPackageInstalls(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true
        }
        val context = androidAppContextOrNull() ?: return true
        if (context.packageManager.canRequestPackageInstalls()) {
            return true
        }
        val permissionIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri()
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(permissionIntent)
        return false
    }
}
