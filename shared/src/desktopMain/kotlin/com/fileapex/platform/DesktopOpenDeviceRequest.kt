package com.fileapex.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A request from the tray to open one device's files in the main window. The window takes it with [consume]. */
object DesktopOpenDeviceRequest {
    private val pending = MutableStateFlow<String?>(null)
    val pendingDeviceId: StateFlow<String?> = pending.asStateFlow()

    fun request(deviceId: String) {
        if (deviceId.isBlank() || isFriendTrayId(deviceId)) return
        pending.value = deviceId
    }

    fun consume() {
        pending.value = null
    }
}
