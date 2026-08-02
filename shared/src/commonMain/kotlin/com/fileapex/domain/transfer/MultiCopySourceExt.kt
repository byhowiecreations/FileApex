package com.fileapex.domain.transfer

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Re-reads size from disk so outbound uploads never declare a stale [MultiCopySource.sizeBytes].
 * Preserves [MultiCopySource.Local.relativeDestPath] from the expander / caller.
 */
fun MultiCopySource.Local.verifiedFromDisk(): MultiCopySource.Local {
    val path = Path(absolutePath)
    check(SystemFileSystem.exists(path)) { "Missing local file: $absolutePath" }
    val metadata = SystemFileSystem.metadataOrNull(path)
    check(metadata != null && !metadata.isDirectory) { "Missing local file: $absolutePath" }
    return copy(
        fileName = path.name,
        sizeBytes = metadata.size.coerceAtLeast(0L)
    )
}

fun List<MultiCopySource>.verifiedFromDisk(): List<MultiCopySource> = map { source ->
    when (source) {
        is MultiCopySource.Local -> source.verifiedFromDisk()
        is MultiCopySource.Remote -> source
    }
}
