package com.fileapex.platform

import android.content.Context

/**
 * Persists per-device Direct Share usage so frequently used peers (e.g. MacBook Pro)
 * receive lower [androidx.core.content.pm.ShortcutInfoCompat.Builder.setRank] values.
 */
internal object DirectShareUsageStore {
    private const val PREFS_NAME = "fileapex_direct_share_usage"

    fun shareCount(context: Context, deviceId: String): Int {
        if (deviceId.isBlank()) return 0
        return prefs(context).getInt(prefsKey(deviceId), 0)
    }

    fun recordShare(context: Context, deviceId: String) {
        if (deviceId.isBlank()) return
        val key = prefsKey(deviceId)
        val next = prefs(context).getInt(key, 0) + 1
        prefs(context).edit().putInt(key, next).apply()
    }

    fun clearShareCount(context: Context, deviceId: String) {
        if (deviceId.isBlank()) return
        prefs(context).edit().remove(prefsKey(deviceId)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun prefsKey(deviceId: String): String = "share_count_$deviceId"
}
