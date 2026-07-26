package com.fileapex.platform

import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.presentation.DeviceHardwareProfile

actual fun localDeviceHardwareProfile(): DeviceHardwareProfile {
    val osName = System.getProperty("os.name").orEmpty()
    val os = when {
        osName.contains("Mac", ignoreCase = true) -> "macos"
        osName.contains("Windows", ignoreCase = true) -> "windows"
        osName.contains("Linux", ignoreCase = true) -> "linux"
        else -> osName.trim().lowercase().ifBlank { "desktop" }
    }
    val make = when (os) {
        "macos" -> "Apple"
        "windows" -> "Microsoft"
        else -> osName.trim().ifBlank { "Desktop" }
    }
    val model = readDesktopHardwareModel()
    return DeviceHardwareProfile(
        os = os,
        platform = currentPlatformLabel(),
        deviceMake = make,
        deviceModel = model
    )
}

private fun readDesktopHardwareModel(): String {
    return runCatching {
        ProcessBuilder("/usr/sbin/sysctl", "-n", "hw.model")
            .redirectErrorStream(true)
            .start()
            .inputStream
            .bufferedReader()
            .use { it.readText().trim() }
            .takeIf { it.isNotEmpty() }
    }.getOrNull()
        ?: System.getProperty("os.arch").orEmpty().trim().ifBlank { "Desktop" }
}
