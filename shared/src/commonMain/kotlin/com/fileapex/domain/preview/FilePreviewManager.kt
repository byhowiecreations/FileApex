package com.fileapex.domain.preview

import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.presentation.BrowseTarget
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readAtMostTo

/**
 * Loads capped preview bytes for local/remote files. Rejects unknown/zero sizes.
 */
class FilePreviewManager(
    private val target: BrowseTarget
) {
    fun assertPreviewAllowed(item: RemoteFileItem, maxBytes: Long) {
        if (item.sizeBytes <= 0L) {
            error(AppI18n.t("preview_unknown_size"))
        }
        if (item.sizeBytes > maxBytes) {
            if (maxBytes >= 1024L * 1024L) {
                error(AppI18n.t("file_too_large_preview_mb", (maxBytes / (1024L * 1024L)).toString()))
            } else {
                error(AppI18n.t("file_too_large_preview"))
            }
        }
    }

    suspend fun loadPreviewBytes(item: RemoteFileItem, maxBytes: Long): ByteArray {
        assertPreviewAllowed(item, maxBytes)
        return when (val browseTarget = target) {
            is BrowseTarget.Local -> readLocalBytesCapped(item.absolutePath, maxBytes)
            is BrowseTarget.Remote -> {
                FileApexServices.client.downloadBytes(
                    host = browseTarget.host,
                    port = browseTarget.port,
                    remotePath = item.absolutePath,
                    maxBytes = maxBytes
                )
            }
        }
    }

    fun isImageFile(item: RemoteFileItem): Boolean {
        val name = item.name.lowercase()
        return item.mimeType.startsWith("image/") ||
            name.endsWith(".jpg") ||
            name.endsWith(".jpeg") ||
            name.endsWith(".png") ||
            name.endsWith(".webp") ||
            name.endsWith(".gif") ||
            name.endsWith(".bmp")
    }

    fun isTextFile(item: RemoteFileItem): Boolean {
        val name = item.name.lowercase()
        return item.mimeType.startsWith("text/") ||
            name.endsWith(".txt") ||
            name.endsWith(".md") ||
            name.endsWith(".log") ||
            name.endsWith(".json") ||
            name.endsWith(".csv")
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${(kb * 10).toInt() / 10.0} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${(mb * 10).toInt() / 10.0} MB"
        val gb = mb / 1024.0
        return "${(gb * 10).toInt() / 10.0} GB"
    }

    companion object {
        const val MAX_PREVIEW_BYTES = 25L * 1024L * 1024L
        const val MAX_TEXT_PREVIEW_BYTES = 1L * 1024L * 1024L

        /**
         * Stream-reads a local file with a hard byte cap (never loads past [maxBytes]).
         */
        fun readLocalBytesCapped(absolutePath: String, maxBytes: Long): ByteArray {
            require(maxBytes > 0L) { "maxBytes must be positive" }
            val path = Path(absolutePath)
            check(SystemFileSystem.exists(path)) { "Missing local file: $absolutePath" }
            val maxInt = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val parts = ArrayList<ByteArray>()
            var total = 0
            SystemFileSystem.source(path).buffered().use { input ->
                val buffer = ByteArray(8_192)
                while (!input.exhausted()) {
                    val read = input.readAtMostTo(buffer)
                    if (read <= 0) continue
                    if (total + read > maxInt) {
                        error(AppI18n.t("file_too_large_preview"))
                    }
                    parts.add(buffer.copyOf(read))
                    total += read
                }
            }
            if (total == 0) {
                error(AppI18n.t("preview_unknown_size"))
            }
            val result = ByteArray(total)
            var offset = 0
            for (part in parts) {
                part.copyInto(result, destinationOffset = offset)
                offset += part.size
            }
            return result
        }
    }
}
