package com.fileapex.platform

/** User-facing short label for where received files land on this platform. */
expect fun downloadsFolderDisplayLabel(): String

/** Public Downloads/FileApex directory for saving remote files onto this device. */
expect fun defaultDownloadsDir(): String

/**
 * Inbound file landing folders.
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
        val isDesktop = p == "desktop" || p.contains("mac") || p.contains("darwin") ||
            p.contains("windows") || p.contains("win") || p.contains("linux")
        val folder = if (isDesktop) "Downloads" else "Download"
        val sep = if (p.contains("win") || root.contains('\\') || (root.length >= 2 && root[1] == ':')) "\\" else "/"
        return "$root$sep$folder$sep$FOLDER_NAME"
    }
}
