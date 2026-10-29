package com.fileapex.domain.diagnostics

import android.content.Context
import com.fileapex.data.seal.SealedText
import com.fileapex.data.settings.androidAppContextOrNull

private const val PREFS = "fileapex_device_details"
private const val KEY = "cache_json"
private const val PURPOSE = "device-details"

internal actual fun readDeviceDetailsCacheRaw(): String {
    val prefs = androidAppContextOrNull()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return ""
    val stored = prefs.getString(KEY, "").orEmpty()
    if (stored.isEmpty()) return ""
    val plain = SealedText.open(PURPOSE, stored) ?: return ""
    if (!stored.startsWith("fe1:")) writeDeviceDetailsCacheRaw(plain)
    return plain
}

internal actual fun writeDeviceDetailsCacheRaw(raw: String) {
    val sealed = if (raw.isEmpty()) "" else SealedText.seal(PURPOSE, raw) ?: return
    androidAppContextOrNull()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY, sealed)?.apply()
}
