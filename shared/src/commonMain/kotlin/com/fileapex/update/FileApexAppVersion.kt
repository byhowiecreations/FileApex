package com.fileapex.update

/**
 * Marketing version string (Android + Desktop UI).
 *
 * [NAME] is generated at build time from `fileapex.version.name` in gradle.properties
 * (see [GeneratedAppVersion]). Android APK [versionName] and jpackage [packageVersion]
 * use the same gradle.properties keys.
 */
object FileApexAppVersion {
    const val NAME: String = GeneratedAppVersion.NAME
    const val CODE: Int = GeneratedAppVersion.CODE
}

/**
 * Platform-resolved running app version (Android: installed package; Desktop: [FileApexAppVersion.NAME]).
 */
expect fun currentAppVersionName(): String

/** Platform-resolved build number (Android: versionCode; Desktop: [FileApexAppVersion.CODE]). */
expect fun currentAppVersionCode(): Int
