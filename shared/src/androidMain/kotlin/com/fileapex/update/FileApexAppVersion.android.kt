package com.fileapex.update

import androidx.core.content.pm.PackageInfoCompat
import com.fileapex.data.settings.androidAppContextOrNull

actual fun currentAppVersionName(): String {
    val context = androidAppContextOrNull() ?: return FileApexAppVersion.NAME
    return runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { FileApexAppVersion.NAME }
}

actual fun currentAppVersionCode(): Int {
    val context = androidAppContextOrNull() ?: return FileApexAppVersion.CODE
    return runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        PackageInfoCompat.getLongVersionCode(info).toInt()
    }.getOrNull() ?: FileApexAppVersion.CODE
}
