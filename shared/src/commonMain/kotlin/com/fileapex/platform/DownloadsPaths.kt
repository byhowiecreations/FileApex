package com.fileapex.platform

/** User-facing short label for where received files land on this platform. */
expect fun downloadsFolderDisplayLabel(): String

/**
 * SSOT for inbound file landing folders on each device.
 *
 * Android public folder: `Download/FileApex`
 * macOS/desktop: `~/Downloads/FileApex`
 */
object DownloadsPaths {
    const val FOLDER_NAME = "FileApex"
    private const val LEGACY_FOLDER_NAME = "OmniNode"

    fun displayLabel(): String = downloadsFolderDisplayLabel()

    /** Rewrites legacy OmniNode receive folders to [FOLDER_NAME]. */
    fun normalize(path: String): String {
        if (path.isBlank()) return path
        return path
            .replace("/$LEGACY_FOLDER_NAME", "/$FOLDER_NAME")
            .replace("\\$LEGACY_FOLDER_NAME", "\\$FOLDER_NAME")
    }

    /**
     * Resolves where received files should land on a peer.
     * Prefer live [downloadsPath] from [PeerNodeState]; fall back using [platform] + [rootPath].
     */
    fun resolveReceiveRoot(
        downloadsPath: String,
        rootPath: String,
        platform: String
    ): String {
        val normalized = normalize(downloadsPath.trim())
        if (normalized.isNotBlank() && !normalized.contains(LEGACY_FOLDER_NAME, ignoreCase = true)) {
            return normalized
        }
        return fallbackFromRoot(rootPath, platform)
    }

    /** Platform-aware fallback when a peer omits [PeerNodeState.downloadsPath]. */
    fun fallbackFromRoot(rootPath: String, platform: String): String {
        val root = rootPath.trim().trimEnd('/', '\\')
        if (root.isBlank()) return defaultDownloadsDir()
        val p = platform.trim().lowercase()
        return if (p == "desktop" || p.contains("mac") || p.contains("darwin")) {
            "$root/Downloads/$FOLDER_NAME"
        } else {
            "$root/Download/$FOLDER_NAME"
        }
    }
}
