package com.fileapex.domain.diagnostics

import com.fileapex.platform.DesktopPlatformPaths
import java.io.File

private fun cacheFile() = File(DesktopPlatformPaths.applicationSupportDirectory(), "device-details.json")

internal actual fun readDeviceDetailsCacheRaw(): String =
    cacheFile().takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrDefault("") }.orEmpty()

internal actual fun writeDeviceDetailsCacheRaw(raw: String) {
    runCatching { cacheFile().writeText(raw) }
}
