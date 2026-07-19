package com.omninode.update

/**
 * Keep [NAME] in sync with `omninode.version.name` in gradle.properties.
 * Desktop reads this constant; Android prefers the installed PackageManager version.
 */
object OmniNodeAppVersion {
    const val NAME = "0.0.4c"
}

/**
 * Platform-resolved running app version (Android: installed package; Desktop: [OmniNodeAppVersion.NAME]).
 */
expect fun currentAppVersionName(): String
