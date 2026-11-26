package com.fileapex.domain.devices

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow

/** The custom icons a user picked for devices, kept by device id. Everything stored is sealed. */
interface DeviceIconStore {
    /** Changes whenever an icon is saved or removed, so cards that show icons redraw. */
    val version: StateFlow<Int>

    fun get(deviceId: String): ImageBitmap?

    /** False when the icon could not be stored (no key yet, or the image could not be encoded); nothing is written then. */
    suspend fun save(deviceId: String, bitmap: ImageBitmap): Boolean

    fun remove(deviceId: String)
}

expect fun createDeviceIconStore(): DeviceIconStore
