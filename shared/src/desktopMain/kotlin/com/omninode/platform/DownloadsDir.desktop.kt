package com.omninode.platform

import java.io.File

actual fun defaultDownloadsDir(): String {
    val home = System.getProperty("user.home") ?: "."
    val dir = File(home, "Downloads/OmniNode")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return dir.absolutePath
}
