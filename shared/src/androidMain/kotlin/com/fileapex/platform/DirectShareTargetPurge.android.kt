package com.fileapex.platform

actual fun purgeDirectShareTarget(deviceId: String) {
    DirectShareShortcutCoordinator.purgeTarget(deviceId)
}
