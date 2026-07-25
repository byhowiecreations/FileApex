package com.fileapex.platform

/**
 * Removes a Direct Share shortcut after a peer is permanently removed.
 */
expect fun purgeDirectShareTarget(deviceId: String)
