package com.fileapex.data.identity

import com.fileapex.platform.DesktopPlatformPaths
import com.fileapex.platform.defaultStorageRoot
import com.fileapex.platform.generateDeviceId
import com.fileapex.platform.platformDeviceName
import java.io.File
import java.util.Properties

actual fun loadLocalIdentity(): LocalIdentity {
    val props = loadProps()
    val deviceId = props.getProperty("deviceId") ?: generateDeviceId().also { id ->
        props.setProperty("deviceId", id)
        persistProps(props)
    }
    val storedName = props.getProperty("deviceName")?.trim().orEmpty()
    return LocalIdentity(
        deviceId = deviceId,
        deviceName = storedName.ifBlank { platformDeviceName() },
        rootPath = defaultStorageRoot(),
        sharePort = LocalIdentity.DEFAULT_SHARE_PORT
    )
}

actual fun updateLocalDeviceName(newName: String) {
    val trimmed = newName.trim()
    require(trimmed.isNotEmpty()) { "Device name cannot be empty" }
    val props = loadProps()
    if (props.getProperty("deviceId").isNullOrBlank()) {
        props.setProperty("deviceId", generateDeviceId())
    }
    props.setProperty("deviceName", trimmed)
    persistProps(props)
}

private fun identityFile(): File {
    DesktopPlatformPaths.applicationSupportDirectory()
    return DesktopPlatformPaths.identityPropertiesFile()
}

private fun loadProps(): Properties {
    val props = Properties()
    for (candidate in identityLoadCandidates()) {
        if (!candidate.isFile) continue
        candidate.inputStream().use { props.load(it) }
        if (props.getProperty("deviceId") != null) {
            migrateIdentityIfNeeded(candidate)
            return props
        }
    }
    return props
}

private fun identityLoadCandidates(): List<File> {
    val primary = identityFile()
    return (listOf(primary) + DesktopPlatformPaths.legacyIdentityPropertiesCandidates())
        .distinctBy { it.absolutePath }
}

private fun migrateIdentityIfNeeded(loadedFrom: File) {
    val target = identityFile()
    if (loadedFrom.canonicalPath == target.canonicalPath) return
    runCatching {
        DesktopPlatformPaths.applicationSupportDirectory()
        loadedFrom.copyTo(target, overwrite = true)
    }
}

private fun persistProps(props: Properties) {
    DesktopPlatformPaths.applicationSupportDirectory()
    identityFile().outputStream().use { props.store(it, "FileApex identity") }
}
