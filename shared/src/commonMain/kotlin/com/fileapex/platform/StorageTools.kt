package com.fileapex.platform

enum class StorageCategory(val key: String) {
    Images("images"),
    Videos("videos"),
    Audio("audio"),
    Apk("apk"),
    Documents("documents");

    companion object {
        private val documentExtensions = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
            "txt", "rtf", "csv", "epub", "md"
        )

        fun of(name: String, mimeType: String?): StorageCategory? {
            val mime = mimeType.orEmpty().lowercase()
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext == "apk" || ext == "apks" || ext == "xapk" || mime == "application/vnd.android.package-archive" ->
                    Apk
                mime.startsWith("image/") -> Images
                mime.startsWith("video/") -> Videos
                mime.startsWith("audio/") -> Audio
                ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng") -> Images
                ext in setOf("mp4", "mkv", "mov", "webm", "avi", "3gp", "m4v") -> Videos
                ext in setOf("mp3", "wav", "flac", "m4a", "aac", "ogg", "opus", "amr") -> Audio
                mime.startsWith("text/") || mime.contains("pdf") || mime.contains("document") ||
                    mime.contains("sheet") || mime.contains("presentation") || ext in documentExtensions -> Documents
                else -> null
            }
        }
    }
}

data class StorageFileEntry(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedMillis: Long,
    val mimeType: String,
)

/** [totalBytes] is the advertised capacity (128, 256, 512 GB…), not the smaller usable figure. */
data class StorageUsage(val totalBytes: Long, val usedBytes: Long) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

class StorageScan(
    val usage: StorageUsage,
    /** Every file in each category, largest first. */
    val byCategory: Map<StorageCategory, List<StorageFileEntry>>,
    /** Biggest files of any type, largest first. */
    val largest: List<StorageFileEntry>,
    /** Every indexed file, unsorted. Feeds folder sizes, duplicates and the cleanup views. */
    val everything: List<StorageFileEntry> = emptyList(),
)

data class TrashedEntry(
    /** Opaque to callers; handed back to [restoreTrashed] and [purgeTrashed]. */
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val trashedAtMillis: Long,
    val originalPath: String?,
    val isDirectory: Boolean,
)

/**
 * SHA-256 of the first [maxBytes] of the file, or all of it when [maxBytes] is negative.
 * Null when the file can't be read.
 */
expect fun fileDigest(path: String, maxBytes: Long): String?

data class LocalPathInfo(val isDirectory: Boolean, val sizeBytes: Long)

/** Null when the path no longer exists. */
expect fun localPathInfo(path: String): LocalPathInfo?

/** Fires when something outside the Tools screen changed the trash (e.g. the system confirm sheet closed). */
object StorageToolsEvents {
    val changed = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The Local Files tab was tapped; an open Tools screen should return to the folder view. */
    val closeRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

internal val advertisedStorageGigabytes = longArrayOf(8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096)

/** Phones report usable space (a "128 GB" phone shows about 112 GiB); this snaps it back to the number on the box. */
fun advertisedStorageBytes(reportedBytes: Long): Long {
    val gigabytes = reportedBytes / 1_000_000_000.0
    val tier = advertisedStorageGigabytes.firstOrNull { it >= gigabytes } ?: return reportedBytes
    return tier * 1_000_000_000L
}

/** True where this host can analyse its own storage and manage a trash folder. */
expect fun storageToolsSupported(): Boolean

expect fun queryStorageUsage(): StorageUsage

/** Walks the device. Slow on big phones: call off the main thread. */
expect fun scanStorage(): StorageScan

expect fun listTrashed(): List<TrashedEntry>

/** @return true when finished, false when the system is still asking the user to confirm. */
expect fun restoreTrashed(ids: List<String>): Boolean

/** @return true when finished, false when the system is still asking the user to confirm. */
expect fun purgeTrashed(ids: List<String>): Boolean
