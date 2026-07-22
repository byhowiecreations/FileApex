package com.omninode.platform

actual fun purgeDirectShareTarget(deviceId: String) {
    DirectShareShortcutCoordinator.purgeTarget(deviceId)
}
