package com.omninode.update

actual object PlatformInstallPermission {
    actual fun ensureCanRequestPackageInstalls(): Boolean = true
}
