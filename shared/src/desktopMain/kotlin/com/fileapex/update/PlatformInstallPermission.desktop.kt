package com.fileapex.update

import androidx.compose.runtime.Composable

actual object PlatformInstallPermission {
    actual fun canRequestPackageInstalls(): Boolean = true
}

@Composable
actual fun rememberRequestInstallUnknownAppsPermission(): () -> Unit = {}
