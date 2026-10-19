package com.fileapex.platform

import android.provider.Settings
import com.fileapex.data.settings.androidAppContextOrNull

/** `ANDROID_ID`: stable per device, user profile and app signing key; survives an app reinstall, not a factory reset. */
actual fun localHardwareIdRaw(): String {
    val context = androidAppContextOrNull() ?: return ""
    return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
}
