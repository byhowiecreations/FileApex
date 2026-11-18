package com.fileapex.platform

import android.os.Environment
import java.io.File
import com.fileapex.util.cancellableCatching

actual fun downloadsFolderDisplayLabel(): String = "Download/FileApex"

actual fun defaultDownloadsDir(): String {
    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val dir = File(downloads, DownloadsPaths.FOLDER_NAME)
    cancellableCatching("DownloadsPaths defaultDownloadsDir") {
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }
    return dir.absolutePath
}
