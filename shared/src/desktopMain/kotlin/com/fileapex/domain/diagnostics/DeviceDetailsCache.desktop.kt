package com.fileapex.domain.diagnostics

import com.fileapex.data.db.seal.FieldSealer
import com.fileapex.data.seal.SealedText
import com.fileapex.platform.DesktopPlatformPaths
import java.io.File

private const val PURPOSE = "device-details"

private fun cacheFile() = File(DesktopPlatformPaths.applicationSupportDirectory(), "device-details.json")

internal actual fun readDeviceDetailsCacheRaw(): String? {
    val file = cacheFile()
    if (!file.isFile) return ""
    val stored = runCatching { file.readText() }.getOrDefault("")
    if (stored.isEmpty()) return ""
    val plain = SealedText.open(PURPOSE, stored) ?: return null
    if (!stored.startsWith(FieldSealer.PREFIX)) writeDeviceDetailsCacheRaw(plain)
    return plain
}

internal actual fun writeDeviceDetailsCacheRaw(raw: String) {
    runCatching { SealedText.writeFile(cacheFile(), PURPOSE, raw) }
}
