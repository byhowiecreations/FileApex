package com.fileapex.update

import androidx.core.content.pm.PackageInfoCompat
import com.fileapex.data.settings.androidAppContextOrNull
import com.fileapex.util.cancellableCatching

actual fun currentAppVersionName(): String {
    val context = androidAppContextOrNull() ?: return FileApexAppVersion.NAME
    return cancellableCatching("FileApexAppVersion.android: context.packageManager.getPackageInfo") {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { FileApexAppVersion.NAME }
}

actual fun currentAppVersionCode(): Int {
    val context = androidAppContextOrNull() ?: return FileApexAppVersion.CODE
    return cancellableCatching("FileApexAppVersion.android: context.packageManager.getPackageInfo") {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        PackageInfoCompat.getLongVersionCode(info).toInt()
    }.getOrNull() ?: FileApexAppVersion.CODE
}

actual fun installedAppLastUpdateTimeEpochMs(): Long {
    val context = androidAppContextOrNull() ?: return 0L
    return cancellableCatching("FileApexAppVersion.android: context.packageManager.getPackageInfo") {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)
}
