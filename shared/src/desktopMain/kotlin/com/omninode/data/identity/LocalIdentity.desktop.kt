package com.omninode.data.identity

import com.omninode.platform.defaultStorageRoot
import com.omninode.platform.generateDeviceId
import com.omninode.platform.platformDeviceName
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
    val dir = File(System.getProperty("user.home"), ".omninode")
    if (!dir.exists()) dir.mkdirs()
    return File(dir, "identity.properties")
}

private fun loadProps(): Properties {
    val file = identityFile()
    val props = Properties()
    if (file.exists()) {
        file.inputStream().use { props.load(it) }
    }
    return props
}

private fun persistProps(props: Properties) {
    identityFile().outputStream().use { props.store(it, "OmniNode identity") }
}
