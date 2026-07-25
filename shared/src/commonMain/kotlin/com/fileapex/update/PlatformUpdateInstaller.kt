package com.fileapex.update

/**
 * Platform-specific install + relaunch after a release asset has been downloaded to [localFilePath].
 */
expect object PlatformUpdateInstaller {
    /**
     * Absolute path to a writable directory used for downloaded update packages.
     */
    fun updateCacheDirectory(): String

    /**
     * Choose the best GitHub release asset for this platform, or null if none match.
     * Android: `.apk`. macOS desktop: `.dmg` preferred, then `.zip`.
     */
    fun selectAsset(assets: List<GitHubReleaseAsset>): GitHubReleaseAsset?

    /**
     * Invoke the native installer for [localFilePath] (APK / DMG / ZIP).
     * May terminate the current process (desktop) after spawning a replace script.
     */
    fun installAndRelaunch(localFilePath: String, remoteVersion: String)
}
