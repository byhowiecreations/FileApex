package com.fileapex.domain.diagnostics

import android.content.Context
import com.fileapex.data.settings.androidAppContextOrNull

private const val PREFS = "fileapex_device_details"
private const val KEY = "cache_json"

internal actual fun readDeviceDetailsCacheRaw(): String =
    androidAppContextOrNull()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY, "").orEmpty()

internal actual fun writeDeviceDetailsCacheRaw(raw: String) {
    androidAppContextOrNull()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY, raw)?.apply()
}
