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

actual fun localHardwareFingerprint(): Map<String, String> = mapOf(
    "manufacturer" to Build.MANUFACTURER.trim(),
    "model" to Build.MODEL.trim(),
    "device" to Build.DEVICE.trim(),
    "board" to Build.BOARD.trim()
)
