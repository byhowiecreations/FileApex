package com.fileapex.platform

import kotlinx.serialization.Serializable

/** Device row pushed to desktop tray UIs (macOS popover + Windows left-click menu). */
@Serializable
data class DesktopTrayDeviceSnapshot(
    val id: String,
    val name: String,
    val isOnline: Boolean
)
