package com.fileapex.domain.transfer

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Expand local drop/send roots into a flat file list that keeps relative folders
 * under each top-level name.
 *
 * Desktop drag-and-drop (and any other path list) must call this before
 * [TransferManager.sendToDevices]. Do not re-implement directory walks in UI layers.
 */
object LocalTransferTree {
    /**
     * Expands absolute file and directory paths into [MultiCopySource.Local] entries.
     * Each directory becomes a same-named root folder via [MultiCopySource.relativeDestPath]
     * (`Photos/vacation/a.jpg`). Empty directories contribute no entries.
     */
    fun expandAbsolutePaths(absolutePaths: List<String>): List<MultiCopySource.Local> {
        val out = ArrayList<MultiCopySource.Local>()
        val visitedPaths = HashSet<String>()
        for (raw in absolutePaths) {
            val path = Path(raw)
            if (!SystemFileSystem.exists(path)) continue
            val metadata = SystemFileSystem.metadataOrNull(path) ?: continue
            if (metadata.isDirectory) {
                out += MultiCopySource.Local(
                    fileName = path.name,
                    sizeBytes = 0L,
                    absolutePath = path.toString(),
                    isDirectory = true,
                    relativeDestPath = path.name
                )
                expandDirectory(
                    dir = path,
                    relativePrefix = path.name,
                    visitedPaths = visitedPaths,
                    out = out
                )
            } else {
                out += MultiCopySource.Local(
                    fileName = path.name,
                    sizeBytes = metadata.size.coerceAtLeast(0L),
                    absolutePath = path.toString(),
                    isDirectory = false,
                    relativeDestPath = path.name
                )
            }
        }
        return out
    }

    private fun expandDirectory(
        dir: Path,
        relativePrefix: String,
        visitedPaths: MutableSet<String>,
        out: MutableList<MultiCopySource.Local>
    ) {
        val canonical = runCatching { java.io.File(dir.toString()).canonicalPath }.getOrDefault(dir.toString())
        if (!visitedPaths.add(canonical)) return

        val children = runCatching { SystemFileSystem.list(dir).toList() }.getOrDefault(emptyList())
        for (child in children) {
            if (isIgnoredTransferFile(child.name)) continue
            val metadata = SystemFileSystem.metadataOrNull(child) ?: continue
            val relative = "$relativePrefix/${child.name}"
            if (metadata.isDirectory) {
                out += MultiCopySource.Local(
                    fileName = child.name,
                    sizeBytes = 0L,
                    absolutePath = child.toString(),
                    isDirectory = true,
                    relativeDestPath = relative
                )
                expandDirectory(child, relative, visitedPaths, out)
            } else {
                out += MultiCopySource.Local(
                    fileName = child.name,
                    sizeBytes = metadata.size.coerceAtLeast(0L),
                    absolutePath = child.toString(),
                    isDirectory = false,
                    relativeDestPath = relative
                )
            }
        }
    }

    fun isIgnoredTransferFile(name: String): Boolean {
        if (name.equals(".DS_Store", ignoreCase = true)) return true
        if (name.startsWith("._")) return true
        if (name.equals("Thumbs.db", ignoreCase = true)) return true
        if (name.equals("desktop.ini", ignoreCase = true)) return true
        if (name.equals(".Spotlight-V100", ignoreCase = true)) return true
        if (name.equals(".Trashes", ignoreCase = true)) return true
        if (name.equals(".fseventsd", ignoreCase = true)) return true
        return false
    }
}
