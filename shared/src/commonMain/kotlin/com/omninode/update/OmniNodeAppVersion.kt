package com.omninode.update

/**
 * Marketing version string (Android + Desktop UI).
 *
 * [NAME] is generated at build time from `omninode.version.name` in gradle.properties
 * (see [GeneratedAppVersion]). Android APK [versionName] and jpackage [packageVersion]
 * use the same gradle.properties keys.
 */
object OmniNodeAppVersion {
    const val NAME: String = GeneratedAppVersion.NAME
    const val CODE: Int = GeneratedAppVersion.CODE
}

/**
 * Platform-resolved running app version (Android: installed package; Desktop: [OmniNodeAppVersion.NAME]).
 */
expect fun currentAppVersionName(): String

/** Platform-resolved build number (Android: versionCode; Desktop: [OmniNodeAppVersion.CODE]). */
expect fun currentAppVersionCode(): Int
