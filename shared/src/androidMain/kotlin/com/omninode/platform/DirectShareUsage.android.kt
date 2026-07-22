package com.omninode.platform

actual fun recordDirectShareTargetUsed(deviceId: String) {
    DirectShareShortcutCoordinator.recordTargetUsed(deviceId)
}
