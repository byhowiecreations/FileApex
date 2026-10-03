package com.fileapex.platform

actual fun isDesktopHost(): Boolean = true

actual fun hostFileSizeBase(): Int {
    val os = System.getProperty("os.name").orEmpty()
    return if (os.startsWith("Windows", ignoreCase = true)) 1024 else 1000
}
