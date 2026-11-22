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

    fun previewFits(item: RemoteFileItem, maxBytes: Long): Boolean = item.sizeBytes in 1..maxBytes

    suspend fun loadPreviewBytes(item: RemoteFileItem, maxBytes: Long): ByteArray {
        assertPreviewAllowed(item, maxBytes)
        return when (val browseTarget = target) {
            is BrowseTarget.Local -> readLocalBytesCapped(item.absolutePath, maxBytes)
            is BrowseTarget.Remote -> {
                FileApexServices.client.downloads.downloadBytes(
                    host = browseTarget.host,
                    port = browseTarget.port,
                    remotePath = item.absolutePath,
                    maxBytes = maxBytes
                )
            }
            is BrowseTarget.Demo -> {
                com.fileapex.domain.demo.DemoModeState.getDemoPreviewBytes(item.name)
            }
        }
    }

    fun isImageFile(item: RemoteFileItem): Boolean {
        if (target is BrowseTarget.Demo) return false
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
        if (target is BrowseTarget.Demo) return true
        if (item.mimeType.startsWith("text/")) return true
        val name = item.name.lowercase()
        val ext = name.substringAfterLast('.', "")
        return name in EXACT_TEXT_NAMES || ext in TEXT_EXTENSIONS
    }

    fun isPdfFile(item: RemoteFileItem): Boolean {
        if (item.isDirectory || target is BrowseTarget.Demo) return false
        return item.mimeType.lowercase() == "application/pdf" || item.name.lowercase().endsWith(".pdf")
    }

    fun isExternalMediaFile(item: RemoteFileItem): Boolean {
        if (item.isDirectory || target is BrowseTarget.Demo) return false
        val mime = item.mimeType.lowercase()
        if (mime == "application/pdf" || mime.startsWith("audio/")) return true
        val ext = item.name.lowercase().substringAfterLast('.', "")
        return ext == "pdf" || ext in AUDIO_EXTENSIONS
    }

    fun formatBytes(bytes: Long): String = com.fileapex.platform.formatHostFileSize(bytes)

    companion object {
        const val MAX_PREVIEW_BYTES = 25L * 1024L * 1024L
        const val MAX_TEXT_PREVIEW_BYTES = 1L * 1024L * 1024L

        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "aiff"
        )

        private val EXACT_TEXT_NAMES = setOf(
            ".env", ".gitignore", ".gitattributes", ".editorconfig", "makefile", "dockerfile"
        )
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "log", "json", "csv", "tsv",
            "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx",
            "xml", "yaml", "yml", "toml", "gradle", "sh", "zsh", "bash", "bat", "ps1",
            "sql", "css", "scss", "html", "htm", "svg",
            "c", "cc", "cpp", "h", "hpp", "rs", "go", "rb", "php", "swift",
            "ini", "conf", "cfg", "env", "properties"
        )

        /**
         * Stream-reads a local file with a hard byte cap (never loads past [maxBytes]).
         */
        fun readLocalBytesCapped(absolutePath: String, maxBytes: Long): ByteArray {
            require(maxBytes > 0L) { "maxBytes must be positive" }
            val path = Path(absolutePath)
            check(SystemFileSystem.exists(path)) { "Missing local file: $absolutePath" }
            val maxInt = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val size = SystemFileSystem.metadataOrNull(path)?.size ?: 0L
            if (size > maxInt) error(AppI18n.t("file_too_large_preview"))
            val result = if (size > 0L) readKnownSize(path, size.toInt()) else readUnknownSize(path, maxInt)
            if (result.isEmpty()) {
                error(AppI18n.t("preview_unknown_size"))
            }
            return result
        }

        /** One allocation of the file's own size; a file that grew meanwhile is cut at that size, one that shrank is trimmed. */
        private fun readKnownSize(path: Path, size: Int): ByteArray {
            val buffer = ByteArray(size)
            var filled = 0
            SystemFileSystem.source(path).buffered().use { input ->
                while (filled < size) {
                    val read = input.readAtMostTo(buffer, filled, size)
                    if (read < 0) break
                    filled += read
                }
            }
            return if (filled == size) buffer else buffer.copyOf(filled)
        }

        private fun readUnknownSize(path: Path, maxInt: Int): ByteArray {
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

enum class ExplorerOpenKind {
    Directory,
    Image,
    Text,
    Pdf,
    ExternalMedia,
    Fallback
}

object ExplorerFileOpenPolicy {
    fun classify(preview: FilePreviewManager, item: RemoteFileItem): ExplorerOpenKind = when {
        item.isDirectory -> ExplorerOpenKind.Directory
        preview.isImageFile(item) -> ExplorerOpenKind.Image
        preview.isTextFile(item) -> ExplorerOpenKind.Text
        preview.isPdfFile(item) -> ExplorerOpenKind.Pdf
        preview.isExternalMediaFile(item) -> ExplorerOpenKind.ExternalMedia
        else -> ExplorerOpenKind.Fallback
    }
}
