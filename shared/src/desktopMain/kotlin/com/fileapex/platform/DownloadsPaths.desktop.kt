package com.fileapex.platform

import java.io.File

actual fun downloadsFolderDisplayLabel(): String = "~/Downloads/FileApex"

actual fun defaultDownloadsDir(): String {
    val home = System.getProperty("user.home") ?: "."
    val root = DesktopPlatformPaths.xdgUserDirectory("DOWNLOAD")?.takeIf { DesktopPlatformPaths.isLinux() && it.isDirectory }
        ?: File(home, "Downloads")
    val dir = File(root, DownloadsPaths.FOLDER_NAME)
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return dir.absolutePath
}
