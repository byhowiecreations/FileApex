package com.fileapex.domain.diagnostics

import com.fileapex.data.seal.SealedText
import com.fileapex.platform.DesktopPlatformPaths
import java.io.File

private const val PURPOSE = "device-details"

private fun cacheFile() = File(DesktopPlatformPaths.applicationSupportDirectory(), "device-details.json")

internal actual fun readDeviceDetailsCacheRaw(): String = SealedText.readFile(cacheFile(), PURPOSE)

internal actual fun writeDeviceDetailsCacheRaw(raw: String) {
    runCatching { SealedText.writeFile(cacheFile(), PURPOSE, raw) }
}
