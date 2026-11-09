package com.fileapex.platform

actual fun fullDiskAccessSupported(): Boolean = DesktopPlatformPaths.isMacOs()

actual fun isFullDiskAccessGranted(): Boolean = !DesktopPlatformPaths.isMacOs() || MacFullDiskAccess.isGranted()

actual fun openFullDiskAccessSettings() {
    if (DesktopPlatformPaths.isMacOs()) MacFullDiskAccess.openSettings()
}
