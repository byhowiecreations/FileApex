package com.fileapex.domain.peer

/**
 * OS / platform classification for transfer routing (LAN vs Drive vs FCM).
 */
object PeerPlatform {
    fun isDesktop(os: String, platform: String = ""): Boolean {
        val slug = os.trim().ifBlank { platform.trim() }.lowercase()
        return slug == "macos" ||
            slug == "windows" ||
            slug == "linux" ||
            slug == "desktop" ||
            slug.startsWith("mac") ||
            slug.startsWith("win")
    }

    fun isAndroid(os: String, platform: String = ""): Boolean {
        if (isDesktop(os, platform)) return false
        val slug = os.trim().ifBlank { platform.trim() }.lowercase()
        return slug.isEmpty() || slug == "android"
    }
}
