package com.fileapex.presentation

import com.fileapex.data.files.guessMimeType
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.util.cancellableCatching
import java.io.File
import java.nio.file.Files

data class LocalSearchListing(
    val directories: List<RemoteFileItem> = emptyList(),
    val files: List<RemoteFileItem> = emptyList(),
    val truncated: Boolean = false
)

/** Bounded iterative search under a local folder root, skipping hidden dirs and symlinks. */
object LocalRecursiveSearch {
    fun search(
        rootPath: String,
        query: String,
        mode: ExplorerSortMode,
        maxDepth: Int = 8,
        maxResults: Int = 400
    ): LocalSearchListing {
        val needle = query.trim()
        if (needle.isEmpty() || rootPath.isBlank()) return LocalSearchListing()
        val root = File(rootPath)
        if (!root.exists() || !root.isDirectory) return LocalSearchListing()

        val matchedDirs = mutableListOf<RemoteFileItem>()
        val matchedFiles = mutableListOf<RemoteFileItem>()
        var truncated = false
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty() && !truncated) {
            val (currentDir, depth) = queue.removeFirst()
            val children = currentDir.listFiles() ?: continue
            for (child in children) {
                val name = child.name
                if (name.startsWith(".")) continue
                val isSymlink = cancellableCatching("LocalRecursiveSearch search") { Files.isSymbolicLink(child.toPath()) }.getOrDefault(true)
                if (isSymlink) continue

                val isDir = child.isDirectory
                if (name.contains(needle, ignoreCase = true)) {
                    val item = RemoteFileItem(
                        name = name,
                        absolutePath = child.absolutePath,
                        sizeBytes = if (isDir) 0L else child.length(),
                        lastModified = child.lastModified(),
                        isDirectory = isDir,
                        mimeType = if (isDir) "inode/directory" else guessMimeType(name)
                    )
                    if (isDir) matchedDirs += item else matchedFiles += item
                    if (matchedDirs.size + matchedFiles.size >= maxResults) {
                        truncated = true
                        break
                    }
                }
                if (isDir && depth < maxDepth) {
                    queue.add(child to (depth + 1))
                }
            }
        }

        return LocalSearchListing(
            directories = ExplorerListOrdering.apply(matchedDirs, query = "", mode = mode),
            files = ExplorerListOrdering.apply(matchedFiles, query = "", mode = mode),
            truncated = truncated
        )
    }
}
