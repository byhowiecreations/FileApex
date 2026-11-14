package com.fileapex.domain.preview

import com.fileapex.domain.model.RemoteFileItem

/** Policy constants and extension matching for bounded remote Explorer media thumbnails. */
object RemoteThumbnailPolicy {
    const val DEFAULT_EDGE = 256
    const val MIN_EDGE = 64
    const val MAX_EDGE = 320
    const val MAX_THUMBNAIL_BYTES = 256 * 1024

    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif"
    )
    private val videoExtensions = setOf(
        "mp4", "mkv", "mov", "webm", "m4v", "avi", "3gp"
    )

    fun clampEdge(edge: Int): Int = edge.coerceIn(MIN_EDGE, MAX_EDGE)

    fun isMediaCandidate(item: RemoteFileItem): Boolean {
        if (item.isDirectory || item.absolutePath.isBlank()) return false
        val mime = item.mimeType.lowercase()
        if (mime.startsWith("image/") || mime.startsWith("video/")) return true
        val ext = item.name.substringAfterLast('.', "").lowercase()
        return ext in imageExtensions || ext in videoExtensions
    }

    fun inferMimeType(fileName: String, mimeType: String): String {
        val lower = mimeType.lowercase()
        if (lower.startsWith("image/") || lower.startsWith("video/")) return lower
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext in videoExtensions -> "video/$ext"
            ext in imageExtensions -> "image/$ext"
            else -> lower
        }
    }
}
