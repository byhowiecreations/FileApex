package com.fileapex.platform

actual fun recordDirectShareTargetUsed(deviceId: String) {
    DirectShareShortcutCoordinator.recordTargetUsed(deviceId)
}
