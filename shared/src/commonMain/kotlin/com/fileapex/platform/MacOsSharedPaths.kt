package com.fileapex.platform

/**
 * Shared on-disk locations for the macOS Share Extension and Swift helpers.
 * Uses Application Support (not App Groups) so ad-hoc builds can share the roster DB.
 *
 * Desktop JVM startup resolves paths via [DesktopPlatformPaths] (macOS + Windows).
 */
object MacOsSharedPaths {
    const val BUNDLE_ID = "com.fileapex"
    const val DATABASE_FILE_NAME = "fileapex.db"
}
