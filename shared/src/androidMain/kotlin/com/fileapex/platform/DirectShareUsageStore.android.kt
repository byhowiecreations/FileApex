package com.fileapex.platform

import android.content.Context
import com.fileapex.data.seal.SealedStrings

/**
 * Persists per-device Direct Share usage so frequently used peers (e.g. MacBook Pro)
 * receive lower [androidx.core.content.pm.ShortcutInfoCompat.Builder.setRank] values.
 */
internal object DirectShareUsageStore {
    private const val PREFS_NAME = "fileapex_direct_share_usage"

    fun shareCount(context: Context, deviceId: String): Int {
        if (deviceId.isBlank()) return 0
        return counts(context).count(deviceId)
    }

    fun recordShare(context: Context, deviceId: String) {
        if (deviceId.isBlank()) return
        counts(context).record(deviceId)
    }

    fun clearShareCount(context: Context, deviceId: String) {
        if (deviceId.isBlank()) return
        counts(context).clear(deviceId)
    }

    private fun counts(context: Context): ShareUsageCounts {
        val slots = PrefsStringSlots(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
        return ShareUsageCounts(SealedStrings(slots, "share-usage"), slots)
    }
}
