package com.fileapex.platform

actual fun defaultStorageRoot(): String = "/storage/emulated/0"

actual fun platformDeviceName(): String {
    val manufacturer = android.os.Build.MANUFACTURER.orEmpty()
    val model = android.os.Build.MODEL.orEmpty()
    return listOf(manufacturer, model)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { "Android Device" }
}
