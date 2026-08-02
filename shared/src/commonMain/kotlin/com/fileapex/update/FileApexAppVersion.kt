package com.fileapex.update

/**
 * Marketing version string (Android + Desktop UI).
 *
 * [NAME] / [CODE] are generated at build time from committed `version.md`
 * (`name=` / `code=`; see [GeneratedAppVersion]).
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
