package com.fileapex.domain.preview

import com.fileapex.domain.model.RemoteFileItem

/** What a file's thumbnail is made from. */
enum class ThumbnailKind { Image, Video, Pdf, AudioArt, ApkIcon }

/** Policy constants and extension matching for bounded Explorer thumbnails. */
object RemoteThumbnailPolicy {
    const val DEFAULT_EDGE = 256
    const val MIN_EDGE = 64
    const val MAX_EDGE = 320
    const val MAX_THUMBNAIL_BYTES = 256 * 1024
    private const val MAX_PDF_BYTES = 100L * 1024 * 1024
    private const val MAX_APK_BYTES = 500L * 1024 * 1024

    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif"
    )
    private val videoExtensions = setOf(
        "mp4", "mkv", "mov", "webm", "m4v", "avi", "3gp"
    )
    private val audioExtensions = setOf(
        "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav"
    )

    fun clampEdge(edge: Int): Int = edge.coerceIn(MIN_EDGE, MAX_EDGE)

    /** The kind of thumbnail [item] can have, or null when it has none or is too large to render in a list. */
    fun kindOf(item: RemoteFileItem): ThumbnailKind? {
        if (item.isDirectory || item.absolutePath.isBlank()) return null
        return kindOf(item.name, item.mimeType, item.sizeBytes)
    }

    fun kindOf(fileName: String, mimeType: String, sizeBytes: Long = 0L): ThumbnailKind? {
        val mime = mimeType.lowercase()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("image/") || ext in imageExtensions -> ThumbnailKind.Image
            mime.startsWith("video/") || ext in videoExtensions -> ThumbnailKind.Video
            (mime == "application/pdf" || ext == "pdf") && sizeBytes <= MAX_PDF_BYTES -> ThumbnailKind.Pdf
            mime.startsWith("audio/") || ext in audioExtensions -> ThumbnailKind.AudioArt
            (mime == "application/vnd.android.package-archive" || ext == "apk") && sizeBytes <= MAX_APK_BYTES -> ThumbnailKind.ApkIcon
            else -> null
        }
    }

    /** True for images and videos, which every platform can thumbnail. */
    fun isMediaCandidate(item: RemoteFileItem): Boolean {
        val kind = kindOf(item)
        return kind == ThumbnailKind.Image || kind == ThumbnailKind.Video
    }

    fun inferMimeType(fileName: String, mimeType: String): String {
        val lower = mimeType.lowercase()
        if (lower.startsWith("image/") || lower.startsWith("video/") || lower.startsWith("audio/")) return lower
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext in videoExtensions -> "video/$ext"
            ext in imageExtensions -> "image/$ext"
            ext in audioExtensions -> "audio/$ext"
            ext == "pdf" -> "application/pdf"
            ext == "apk" -> "application/vnd.android.package-archive"
            else -> lower
        }
    }
}
