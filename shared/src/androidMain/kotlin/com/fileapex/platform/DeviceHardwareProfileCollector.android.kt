package com.fileapex.platform

import android.os.Build
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.presentation.DeviceHardwareProfile

actual fun localDeviceHardwareProfile(): DeviceHardwareProfile =
    DeviceHardwareProfile(
        os = "android",
        platform = currentPlatformLabel(),
        deviceMake = Build.MANUFACTURER.trim(),
        deviceModel = Build.MODEL.trim()
    )
