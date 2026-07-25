package com.fileapex.update

import androidx.compose.runtime.Composable

/**
 * Read-only install-permission probe. Never launches system Settings.
 * Safe to call from background / update-install paths.
 */
expect object PlatformInstallPermission {
    fun canRequestPackageInstalls(): Boolean
}

/**
 * Returns a callback that may open [android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES]
 * only when invoked from a direct user gesture on the UI thread (e.g. Switch onCheckedChange).
 * Desktop: no-op.
 */
@Composable
expect fun rememberRequestInstallUnknownAppsPermission(): () -> Unit
