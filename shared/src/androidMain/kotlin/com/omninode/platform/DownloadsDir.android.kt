package com.omninode.platform

import android.os.Environment
import java.io.File

actual fun defaultDownloadsDir(): String {
    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    return File(downloads, "OmniNode").absolutePath
}
