package com.fileapex.platform

import com.fileapex.data.files.guessMimeType
import com.fileapex.data.files.isHiddenDotName
import com.fileapex.domain.model.RemoteFileItem
import java.io.File

actual fun fastScanDirectory(absolutePath: String): Pair<List<RemoteFileItem>, List<RemoteFileItem>> {
    val dir = File(absolutePath)
    if (!dir.exists()) error("Path does not exist: $absolutePath")
    if (!dir.isDirectory) error("Not a directory: $absolutePath")

    val dirsList = ArrayList<RemoteFileItem>(64)
    val filesList = ArrayList<RemoteFileItem>(128)

    val children = dir.listFiles() ?: emptyArray()
    for (entry in children) {
        val fileName = entry.name
        if (isHiddenDotName(fileName)) continue

        val isDir = entry.isDirectory
        val size = if (isDir) 0L else entry.length().coerceAtLeast(0L)
        val lastModified = entry.lastModified()
        val fullPath = entry.absolutePath

        val item = RemoteFileItem(
            id = fullPath,
            name = fileName,
            absolutePath = fullPath,
            sizeBytes = size,
            lastModified = lastModified,
            isDirectory = isDir,
            mimeType = if (isDir) "inode/directory" else guessMimeType(fileName)
        )

        if (isDir) {
            dirsList.add(item)
        } else {
            filesList.add(item)
        }
    }

    dirsList.sortBy { it.name.lowercase() }
    filesList.sortBy { it.name.lowercase() }

    return dirsList to filesList
}
