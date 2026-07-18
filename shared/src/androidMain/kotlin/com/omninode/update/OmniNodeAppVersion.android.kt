package com.omninode.update

import com.omninode.data.settings.androidAppContextOrNull

actual fun currentAppVersionName(): String {
    val context = androidAppContextOrNull() ?: return OmniNodeAppVersion.NAME
    return runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { OmniNodeAppVersion.NAME }
}
