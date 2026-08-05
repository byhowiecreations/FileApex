package com.fileapex.domain.transfer

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Android share-sheet files are copied into `cache/share-staging/{sessionId}/` so sends can
 * stream by path. Those copies must live until the transfer (or queued drain) finishes.
 */
object ShareStagingCleanup {
    private const val MARKER = "/share-staging/"

    fun sessionRootForFile(absolutePath: String): String? {
        val idx = absolutePath.indexOf(MARKER)
        if (idx < 0) return null
        val afterMarker = absolutePath.substring(idx + MARKER.length)
        val sessionId = afterMarker.substringBefore('/').substringBefore('\\')
        if (sessionId.isEmpty()) return null
        return absolutePath.substring(0, idx + MARKER.length + sessionId.length)
    }

    fun deleteSessionRootsForPaths(paths: Iterable<String>) {
        paths.mapNotNull(::sessionRootForFile).distinct().forEach { root ->
            val dir = Path(root)
            if (!SystemFileSystem.exists(dir)) return@forEach
            runCatching {
                SystemFileSystem.list(dir).forEach { child ->
                    runCatching { SystemFileSystem.delete(child) }
                }
                SystemFileSystem.delete(dir)
            }
        }
    }
}
