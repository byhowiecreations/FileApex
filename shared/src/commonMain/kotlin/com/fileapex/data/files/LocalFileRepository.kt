package com.fileapex.data.files

import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.platform.fastScanDirectory
import com.fileapex.util.TimeUtils
import kotlinx.io.files.Path

class LocalFileRepository {
    private data class CachedListing(
        val timestampEpochMs: Long,
        val listing: DirectoryListing
    )

    private val cache = mutableMapOf<String, CachedListing>()
    private val cacheLock = Any()

    fun listDirectory(absolutePath: String, bypassCache: Boolean = false): Result<DirectoryListing> {
        return runCatching {
            val now = TimeUtils.now()
            if (!bypassCache) {
                synchronized(cacheLock) {
                    val cached = cache[absolutePath]
                    if (cached != null && (now - cached.timestampEpochMs) < CACHE_TTL_MS) {
                        return@runCatching cached.listing
                    }
                }
            }

            val (directories, files) = fastScanDirectory(absolutePath)
            val parent = Path(absolutePath).parent?.toString()

            val listing = DirectoryListing(
                path = absolutePath,
                parentPath = parent,
                directories = directories,
                files = files
            )

            synchronized(cacheLock) {
                cache[absolutePath] = CachedListing(now, listing)
                if (cache.size > 100) {
                    val cutoff = now - CACHE_TTL_MS
                    cache.entries.removeAll { it.value.timestampEpochMs < cutoff }
                }
            }

            listing
        }
    }

    fun parentPath(absolutePath: String): String? {
        return Path(absolutePath).parent?.toString()
    }

    fun invalidateCache(path: String? = null) {
        synchronized(cacheLock) {
            if (path == null) {
                cache.clear()
            } else {
                cache.remove(path)
            }
        }
    }

    companion object {
        private const val CACHE_TTL_MS = 15_000L
    }
}

fun guessMimeType(name: String): String {
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

data class DirectoryListing(
    val path: String,
    val parentPath: String?,
    val directories: List<RemoteFileItem>,
    val files: List<RemoteFileItem>
)
