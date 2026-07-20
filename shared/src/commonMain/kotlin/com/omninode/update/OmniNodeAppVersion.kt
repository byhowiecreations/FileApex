package com.omninode.update

/**
 * Marketing version string (Android + Desktop UI).
 *
 * Dual versioning (keep in sync each letter bump):
 * - Marketing: [NAME] **must** equal `omninode.version.name` in gradle.properties
 * - Code / jpackage: `omninode.version.code` → Android `versionCode` and
 *   macOS jpackage `packageVersion` `1.0.${code}` (jpackage forbids `0.x.ya` forms)
 * - Shipped DMG/`OmniNode.app` marketing strings are rewritten to [NAME] in
 *   `copyCurrentBuilds`
 *
 * Edit gradle.properties first, then update [NAME] in the same change set.
 */
object OmniNodeAppVersion {
    const val NAME = "0.1.2e"
}

/**
 * Platform-resolved running app version (Android: installed package; Desktop: [OmniNodeAppVersion.NAME]).
 */
expect fun currentAppVersionName(): String
