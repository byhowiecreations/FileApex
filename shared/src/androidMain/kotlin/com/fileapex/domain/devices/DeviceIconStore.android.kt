package com.fileapex.domain.devices

import com.fileapex.data.settings.androidAppContextOrNull
import java.io.File

actual fun createDeviceIconStore(): DeviceIconStore {
    val context = checkNotNull(androidAppContextOrNull()) { "Call initAndroidAppSettings(context) before using device icons" }
    return SealedFileDeviceIconStore(File(context.filesDir, "device-icons"))
}
