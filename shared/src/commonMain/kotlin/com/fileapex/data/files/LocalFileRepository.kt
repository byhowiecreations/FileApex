package com.fileapex.data.files

import com.fileapex.domain.model.RemoteFileItem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Single source of truth for local filesystem listing via kotlinx.io.
 * No java.io.File in commonMain.
 */
class LocalFileRepository {
    fun listDirectory(absolutePath: String): Result<DirectoryListing> {
        return runCatching {
            val path = Path(absolutePath)
            if (!SystemFileSystem.exists(path)) {
                error("Path does not exist: $absolutePath")
            }
            val metadata = SystemFileSystem.metadataOrNull(path)
            if (metadata?.isDirectory != true) {
                error("Not a directory: $absolutePath")
            }

            val children = SystemFileSystem.list(path).mapNotNull { child ->
                val name = child.name.ifBlank { child.toString() }
                if (isHiddenDotName(name)) return@mapNotNull null
                val childMeta = SystemFileSystem.metadataOrNull(child) ?: return@mapNotNull null
                val isDirectory = childMeta.isDirectory
                RemoteFileItem(
                    id = child.toString(),
                    name = name,
                    absolutePath = child.toString(),
                    sizeBytes = if (isDirectory) 0L else childMeta.size.coerceAtLeast(0L),
                    lastModified = 0L,
                    isDirectory = isDirectory,
                    mimeType = if (isDirectory) "inode/directory" else guessMimeType(name)
                )
            }

            val directories = children
                .filter { it.isDirectory }
                .sortedBy { it.name.lowercase() }
            val files = children
                .filter { !it.isDirectory }
                .sortedBy { it.name.lowercase() }

            DirectoryListing(
                path = absolutePath,
                parentPath = path.parent?.toString(),
                directories = directories,
                files = files
            )
        }
    }

    fun parentPath(absolutePath: String): String? {
        return Path(absolutePath).parent?.toString()
    }

    private fun guessMimeType(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log") -> "text/plain"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}

data class DirectoryListing(
    val path: String,
    val parentPath: String?,
    val directories: List<RemoteFileItem>,
    val files: List<RemoteFileItem>
)
