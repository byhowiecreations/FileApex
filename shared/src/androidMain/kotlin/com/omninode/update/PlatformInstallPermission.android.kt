package com.omninode.update

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.omninode.data.settings.androidAppContextOrNull

actual object PlatformInstallPermission {
    actual fun canRequestPackageInstalls(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true
        }
        val context = androidAppContextOrNull() ?: return false
        return context.packageManager.canRequestPackageInstalls()
    }
}

@Composable
actual fun rememberRequestInstallUnknownAppsPermission(): () -> Unit {
    val context = LocalContext.current
    return remember(context) {
        {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return@remember
            }
            if (context.packageManager.canRequestPackageInstalls()) {
                return@remember
            }
            // Must run only from an explicit UI click (Activity context, no NEW_TASK).
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri()
            )
            context.startActivity(intent)
        }
    }
}
