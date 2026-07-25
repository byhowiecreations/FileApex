package com.fileapex.platform

import android.os.Environment
import java.io.File

actual fun defaultDownloadsDir(): String {
    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val dir = File(downloads, DownloadsPaths.FOLDER_NAME)
    runCatching {
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }
    return dir.absolutePath
}
