package com.fileapex.domain.diagnostics

import com.fileapex.data.db.seal.FieldSealer
import com.fileapex.data.seal.SealedText
import com.fileapex.platform.DesktopPlatformPaths
import java.io.File
import com.fileapex.util.cancellableCatching

private const val PURPOSE = "device-details"

private fun cacheFile() = File(DesktopPlatformPaths.applicationSupportDirectory(), "device-details.json")

internal actual fun readDeviceDetailsCacheRaw(): String? {
    val file = cacheFile()
    if (!file.isFile) return ""
    val stored = cancellableCatching("DeviceDetailsCache.desktop: file.readText") { file.readText() }.getOrDefault("")
    if (stored.isEmpty()) return ""
    val plain = SealedText.open(PURPOSE, stored) ?: return null
    if (!stored.startsWith(FieldSealer.PREFIX)) writeDeviceDetailsCacheRaw(plain)
    return plain
}

internal actual fun writeDeviceDetailsCacheRaw(raw: String) {
    cancellableCatching("DeviceDetailsCache.desktop: SealedText.writeFile") { SealedText.writeFile(cacheFile(), PURPOSE, raw) }
}
