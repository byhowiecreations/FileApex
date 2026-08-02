package com.fileapex.domain.transfer

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Single source of truth for expanding local drop/send roots into a flat file list that
 * preserves relative folder structure under each top-level name.
 *
 * Desktop drag-and-drop (and any other path list) must call this before
 * [TransferManager.sendToDevices] — do not re-implement directory walks in UI layers.
 */
object LocalTransferTree {
    /**
     * Expands absolute file and directory paths into [MultiCopySource.Local] entries.
     * Each directory becomes a same-named root folder via [MultiCopySource.relativeDestPath]
     * (`Photos/vacation/a.jpg`). Empty directories contribute no entries.
     */
    fun expandAbsolutePaths(absolutePaths: List<String>): List<MultiCopySource.Local> {
        val out = ArrayList<MultiCopySource.Local>()
        for (raw in absolutePaths) {
            val path = Path(raw)
            if (!SystemFileSystem.exists(path)) continue
            val metadata = SystemFileSystem.metadataOrNull(path) ?: continue
            if (metadata.isDirectory) {
                expandDirectory(
                    dir = path,
                    relativePrefix = path.name,
                    out = out
                )
            } else {
                out += MultiCopySource.Local(
                    fileName = path.name,
                    sizeBytes = metadata.size.coerceAtLeast(0L),
                    absolutePath = path.toString(),
                    relativeDestPath = path.name
                )
            }
        }
        return out
    }

    private fun expandDirectory(
        dir: Path,
        relativePrefix: String,
        out: MutableList<MultiCopySource.Local>
    ) {
        val children = runCatching { SystemFileSystem.list(dir).toList() }.getOrDefault(emptyList())
        for (child in children) {
            val metadata = SystemFileSystem.metadataOrNull(child) ?: continue
            val relative = "$relativePrefix/${child.name}"
            if (metadata.isDirectory) {
                expandDirectory(child, relative, out)
            } else {
                out += MultiCopySource.Local(
                    fileName = child.name,
                    sizeBytes = metadata.size.coerceAtLeast(0L),
                    absolutePath = child.toString(),
                    relativeDestPath = relative
                )
            }
        }
    }
}
