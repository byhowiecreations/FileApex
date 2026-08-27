package com.fileapex.data.bulletin

import com.fileapex.platform.UniqueFileNames
import com.fileapex.util.PathUtils
import com.fileapex.util.sha256HexFile
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

object BulletinRemoteFilePurgeResolver {
    private const val MAX_HASH_CANDIDATES = 32

    fun resolve(meta: BulletinFileMetadata, downloadsDir: String): String? {
        val downloads = downloadsDir.trim().trimEnd('/', '\\')
        if (downloads.isBlank()) return null

        val stored = meta.localPath?.trim()?.takeIf { it.isNotEmpty() }
        if (stored != null &&
            PathUtils.isWithinRoot(stored, downloads) &&
            matchesSandboxFile(stored, downloads, meta)
        ) {
            return stored
        }

        val listed = listFiles(downloads)
        val nameHits = listed.filter { path ->
            UniqueFileNames.matchesOriginalOrCollision(meta.fileName, fileNameOf(path))
        }
        for (candidate in nameHits) {
            if (matchesSandboxFile(candidate, downloads, meta)) return candidate
        }

        if (meta.sha256.isBlank() || meta.sizeBytes <= 0L) return null
        val sizeHits = listed
            .filter { fileSize(it) == meta.sizeBytes }
            .take(MAX_HASH_CANDIDATES)
        for (candidate in sizeHits) {
            if (matchesSandboxFile(candidate, downloads, meta)) return candidate
        }
        return null
    }

    fun isSafeDeletePath(absolutePath: String, downloadsDir: String): Boolean {
        val downloads = downloadsDir.trim().trimEnd('/', '\\')
        if (downloads.isBlank() || absolutePath.isBlank()) return false
        return PathUtils.isWithinRoot(absolutePath, downloads)
    }

    private fun matchesSandboxFile(
        absolutePath: String,
        downloadsDir: String,
        meta: BulletinFileMetadata
    ): Boolean {
        if (!isSafeDeletePath(absolutePath, downloadsDir)) return false
        val path = Path(absolutePath)
        if (!SystemFileSystem.exists(path)) return false
        val metadata = SystemFileSystem.metadataOrNull(path) ?: return false
        if (metadata.isDirectory) return false
        if (meta.sizeBytes > 0L && metadata.size != meta.sizeBytes) return false
        if (meta.sha256.isNotBlank()) {
            val hash = runCatching { sha256HexFile(absolutePath) }.getOrNull() ?: return false
            if (!hash.equals(meta.sha256, ignoreCase = true)) return false
        } else if (meta.sizeBytes <= 0L) {
            return false
        }
        return true
    }

    private fun listFiles(directory: String): List<String> {
        val root = Path(directory)
        if (!SystemFileSystem.exists(root)) return emptyList()
        val metadata = SystemFileSystem.metadataOrNull(root) ?: return emptyList()
        if (metadata.isDirectory != true) return emptyList()
        return runCatching {
            SystemFileSystem.list(root).mapNotNull { child ->
                val childMeta = SystemFileSystem.metadataOrNull(child) ?: return@mapNotNull null
                if (childMeta.isDirectory) return@mapNotNull null
                child.toString()
            }
        }.getOrDefault(emptyList())
    }

    private fun fileSize(absolutePath: String): Long? {
        val metadata = SystemFileSystem.metadataOrNull(Path(absolutePath)) ?: return null
        if (metadata.isDirectory) return null
        return metadata.size
    }

    private fun fileNameOf(absolutePath: String): String =
        absolutePath.substringAfterLast('/').substringAfterLast('\\')
}
