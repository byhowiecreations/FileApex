package com.omninode.platform

import java.io.File

actual fun defaultDownloadsDir(): String {
    val home = System.getProperty("user.home") ?: "."
    return File(home, "Downloads/OmniNode").absolutePath
}
