package com.fileapex.platform

import java.io.File

actual fun defaultDownloadsDir(): String {
    val home = System.getProperty("user.home") ?: "."
    val dir = File(home, "Downloads/${DownloadsPaths.FOLDER_NAME}")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return dir.absolutePath
}
