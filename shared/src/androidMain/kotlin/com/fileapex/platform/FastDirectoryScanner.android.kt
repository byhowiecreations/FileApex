package com.fileapex.platform

import com.fileapex.data.files.guessMimeType
import com.fileapex.data.files.isHiddenDotName
import com.fileapex.domain.model.RemoteFileItem
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

actual suspend fun prepareLocalDirectoryAccess(absolutePath: String) = Unit

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

        // One stat per entry; File.isDirectory/length/lastModified is three, which is slow on FUSE storage.
        val attrs = try {
            Files.readAttributes(entry.toPath(), BasicFileAttributes::class.java)
        } catch (_: IOException) {
            null
        }
        val isDir = attrs?.isDirectory ?: false
        val size = if (isDir || attrs == null) 0L else attrs.size().coerceAtLeast(0L)
        val lastModified = attrs?.lastModifiedTime()?.toMillis() ?: 0L
        val fullPath = entry.absolutePath

        val item = RemoteFileItem(
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
