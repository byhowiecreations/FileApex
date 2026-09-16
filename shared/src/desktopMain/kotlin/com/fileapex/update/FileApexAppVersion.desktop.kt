package com.fileapex.update

actual fun currentAppVersionName(): String = FileApexAppVersion.NAME

actual fun currentAppVersionCode(): Int = FileApexAppVersion.CODE
 
actual fun installedAppLastUpdateTimeEpochMs(): Long = 0L
