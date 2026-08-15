package com.fileapex.platform

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.fileapex.data.settings.androidAppContextOrNull
import java.io.File

actual fun openLocalFile(absolutePath: String, displayName: String) {
    val context = androidAppContextOrNull() ?: return
    val file = File(absolutePath)
    if (!file.isFile) return
    val uri = FileProvider.getUriForFile(
        context,
        FileApexFileProvider.authority(context),
        file
    )
    val mime = guessMime(file.name.ifBlank { displayName })
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(view) }
        .onFailure {
            val chooser = Intent.createChooser(view, displayName.ifBlank { file.name }).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(chooser) }
        }
}

private fun guessMime(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    if (ext.isBlank()) return "application/octet-stream"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: "application/octet-stream"
}
