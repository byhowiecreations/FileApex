package com.fileapex.domain.clipboard

import android.content.Context
import com.fileapex.data.settings.androidAppContextOrNull

actual object ClipboardDeduperPersistence {
    private const val PREFS_NAME = "fileapex_clipboard_deduper"
    private const val KEY_LAST_HASH = "last_pushed_hash"
    private const val KEY_SEEN_HASHES = "seen_hashes_entries"

    actual fun load(): PersistedDeduperState {
        val context = androidAppContextOrNull() ?: return PersistedDeduperState(null, emptyMap())
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastHash = prefs.getString(KEY_LAST_HASH, null)?.trim()?.takeIf { it.isNotEmpty() }
        val rawEntries = prefs.getStringSet(KEY_SEEN_HASHES, emptySet()) ?: emptySet()
        val seen = mutableMapOf<String, Long>()
        for (entry in rawEntries) {
            val parts = entry.split(':', limit = 2)
            if (parts.size == 2) {
                val hash = parts[0].trim()
                val ts = parts[1].toLongOrNull() ?: 0L
                if (hash.isNotEmpty() && ts > 0L) {
                    seen[hash] = ts
                }
            }
        }
        return PersistedDeduperState(lastHash, seen)
    }

    actual fun save(lastHash: String?, seenHashes: Map<String, Long>) {
        val context = androidAppContextOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val entries = seenHashes.map { "${it.key}:${it.value}" }.toSet()
        prefs.edit()
            .putString(KEY_LAST_HASH, lastHash)
            .putStringSet(KEY_SEEN_HASHES, entries)
            .apply()
    }

    actual fun clear() {
        val context = androidAppContextOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
}
