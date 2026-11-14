package com.fileapex.widget

import android.content.Context
import com.fileapex.domain.widget.WidgetActionTarget
import com.fileapex.platform.AndroidStorageAccess
import com.fileapex.platform.defaultStorageRoot
import java.io.File

sealed interface LatestMediaResult {
    data class Found(val file: File) : LatestMediaResult
    data object NotFound : LatestMediaResult
    data object PermissionRequired : LatestMediaResult
}

/**
 * Resolves the newest photo, screenshot, video, or download under existing All-Files-Access storage
 * without adding Play-restricted READ_MEDIA_* permissions.
 */
class LatestMediaResolver(
    private val hasFullAccess: () -> Boolean,
    private val storageRootsProvider: () -> List<File>
) {
    constructor(context: Context) : this(
        hasFullAccess = { AndroidStorageAccess.hasFullAccess(context) },
        storageRootsProvider = { listOf(File(defaultStorageRoot())) }
    )

    fun resolve(target: WidgetActionTarget): LatestMediaResult {
        if (!hasFullAccess()) return LatestMediaResult.PermissionRequired
        val roots = storageRootsProvider().filter { it.isDirectory }
        if (roots.isEmpty()) return LatestMediaResult.NotFound

        val candidates = ArrayList<File>()
        for (root in roots) {
            for (rel in candidateFoldersFor(target)) {
                val dir = File(root, rel)
                if (dir.isDirectory) {
                    collectMatchingFiles(
                        dir = dir,
                        depthRemaining = MAX_SCAN_DEPTH,
                        extensions = allowedExtensionsFor(target),
                        excludeDirName = if (target == WidgetActionTarget.SEND_LAST_PHOTO) "screenshots" else null,
                        out = candidates
                    )
                }
            }
        }
        val newest = candidates.maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { it.name })
            ?: return LatestMediaResult.NotFound
        return LatestMediaResult.Found(newest)
    }

    private fun collectMatchingFiles(
        dir: File,
        depthRemaining: Int,
        extensions: Set<String>?,
        excludeDirName: String?,
        out: MutableList<File>
    ) {
        val entries = dir.listFiles().orEmpty()
        for (entry in entries) {
            val name = entry.name
            if (isIgnoredName(name)) continue
            if (entry.isDirectory) {
                if (depthRemaining > 0 && !name.equals(excludeDirName, ignoreCase = true)) {
                    collectMatchingFiles(entry, depthRemaining - 1, extensions, excludeDirName, out)
                }
            } else if (entry.isFile && entry.length() > 0L) {
                if (extensions == null || entry.extension.lowercase() in extensions) {
                    out += entry
                }
            }
        }
    }

    private fun candidateFoldersFor(target: WidgetActionTarget): List<String> = when (target) {
        WidgetActionTarget.SEND_LAST_PHOTO -> listOf("DCIM/Camera", "DCIM", "Pictures")
        WidgetActionTarget.SEND_LAST_SCREENSHOT -> listOf("Pictures/Screenshots", "DCIM/Screenshots")
        WidgetActionTarget.SEND_LAST_VIDEO -> listOf("DCIM/Camera", "DCIM", "Movies")
        WidgetActionTarget.SEND_LAST_DOWNLOAD -> listOf("Download", "Downloads")
        else -> emptyList()
    }

    private fun allowedExtensionsFor(target: WidgetActionTarget): Set<String>? = when (target) {
        WidgetActionTarget.SEND_LAST_PHOTO -> PHOTO_EXTS
        WidgetActionTarget.SEND_LAST_SCREENSHOT -> SCREENSHOT_EXTS
        WidgetActionTarget.SEND_LAST_VIDEO -> VIDEO_EXTS
        WidgetActionTarget.SEND_LAST_DOWNLOAD -> null
        else -> emptySet()
    }

    private fun isIgnoredName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith(".") ||
            lower.endsWith(".fileapex-part") ||
            lower.endsWith(".part") ||
            lower.endsWith(".tmp") ||
            lower.endsWith(".crdownload")
    }

    private companion object {
        const val MAX_SCAN_DEPTH = 2
        val PHOTO_EXTS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "dng")
        val SCREENSHOT_EXTS = setOf("png", "jpg", "jpeg", "webp")
        val VIDEO_EXTS = setOf("mp4", "mkv", "mov", "webm", "3gp", "m4v", "avi")
    }
}
