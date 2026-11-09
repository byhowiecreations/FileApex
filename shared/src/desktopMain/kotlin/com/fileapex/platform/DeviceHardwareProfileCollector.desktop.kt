package com.fileapex.platform

import com.fileapex.util.cancellableCatching
import kotlin.text.Charsets

import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.presentation.DeviceHardwareProfile

actual fun localDeviceHardwareProfile(): DeviceHardwareProfile = when (DesktopPlatformPaths.desktopOs) {
    DesktopPlatformPaths.DesktopOs.Windows -> WindowsDiagnostics.hardwareProfile(WindowsRegistry, currentPlatformLabel())
    DesktopPlatformPaths.DesktopOs.MacOs -> DeviceHardwareProfile(
        os = "macos",
        platform = currentPlatformLabel(),
        deviceMake = "Apple",
        deviceModel = readMacHardwareModel() ?: archName()
    )
    else -> {
        val osName = System.getProperty("os.name").orEmpty()
        DeviceHardwareProfile(
            os = if (osName.contains("Linux", ignoreCase = true)) "linux" else osName.trim().lowercase().ifBlank { "desktop" },
            platform = currentPlatformLabel(),
            deviceMake = osName.trim().ifBlank { "Desktop" },
            deviceModel = archName()
        )
    }
}

private fun archName(): String = System.getProperty("os.arch").orEmpty().trim().ifBlank { "Desktop" }

private fun readMacHardwareModel(): String? = cancellableCatching {
    ProcessBuilder("/usr/sbin/sysctl", "-n", "hw.model")
        .redirectErrorStream(true)
        .start()
        .inputStream
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText().trim() }
        .takeIf { it.isNotEmpty() }
}.getOrNull()

actual fun localHardwareFingerprint(): Map<String, String> {
    val profile = localDeviceHardwareProfile()
    val host = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: profile.deviceModel
    return mapOf(
        "manufacturer" to profile.deviceMake,
        "model" to profile.deviceModel,
        "device" to host.trim(),
        "board" to System.getProperty("os.arch", "x86_64").trim()
    )
}
