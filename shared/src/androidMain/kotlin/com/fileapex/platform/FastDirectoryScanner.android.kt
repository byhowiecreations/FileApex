package com.fileapex.platform

import com.fileapex.data.files.guessMimeType
import com.fileapex.data.files.isHiddenDotName
import com.fileapex.domain.model.RemoteFileItem
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

actual fun fastScanDirectory(absolutePath: String): Pair<List<RemoteFileItem>, List<RemoteFileItem>> {
    val dirPath = Paths.get(absolutePath)
    if (!Files.exists(dirPath)) error("Path does not exist: $absolutePath")
    if (!Files.isDirectory(dirPath)) error("Not a directory: $absolutePath")

    val dirsList = ArrayList<RemoteFileItem>(64)
    val filesList = ArrayList<RemoteFileItem>(128)

    Files.newDirectoryStream(dirPath).use { stream ->
        for (entry in stream) {
            val fileName = entry.fileName?.toString() ?: continue
            if (isHiddenDotName(fileName)) continue

            val attrs = runCatching {
                Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }.getOrNull()

            val isDir = attrs?.isDirectory ?: Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
            val size = if (isDir) 0L else (attrs?.size() ?: runCatching { Files.size(entry) }.getOrDefault(0L)).coerceAtLeast(0L)
            val lastModified = attrs?.lastModifiedTime()?.toMillis() ?: 0L
            val fullPath = entry.toAbsolutePath().toString()

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
    }

    dirsList.sortBy { it.name.lowercase() }
    filesList.sortBy { it.name.lowercase() }

    return dirsList to filesList
}
