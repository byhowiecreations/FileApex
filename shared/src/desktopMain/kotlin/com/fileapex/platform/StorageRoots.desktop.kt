package com.fileapex.platform

actual fun defaultStorageRoot(): String {
    return System.getProperty("user.home") ?: "/"
}

actual fun platformDeviceName(): String {
    return System.getProperty("user.name")?.let { "FileApex ($it)" } ?: "FileApex Desktop"
}
