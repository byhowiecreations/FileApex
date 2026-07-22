package com.omninode.update

import com.omninode.data.settings.androidAppContextOrNull

actual fun currentAppVersionName(): String {
    val context = androidAppContextOrNull() ?: return OmniNodeAppVersion.NAME
    return runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { OmniNodeAppVersion.NAME }
}

actual fun currentAppVersionCode(): Int {
    val context = androidAppContextOrNull() ?: return OmniNodeAppVersion.CODE
    return runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }.getOrNull() ?: OmniNodeAppVersion.CODE
}
