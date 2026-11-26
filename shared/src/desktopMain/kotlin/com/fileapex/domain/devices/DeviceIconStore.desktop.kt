package com.fileapex.domain.devices

import com.fileapex.platform.DesktopPlatformPaths
import java.io.File

actual fun createDeviceIconStore(): DeviceIconStore =
    SealedFileDeviceIconStore(File(DesktopPlatformPaths.applicationSupportDirectory(), "device-icons"))
