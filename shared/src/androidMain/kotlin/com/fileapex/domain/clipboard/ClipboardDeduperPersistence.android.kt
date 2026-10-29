package com.fileapex.domain.clipboard

import android.content.Context
import com.fileapex.data.seal.SealedText
import com.fileapex.data.settings.androidAppContextOrNull

actual object ClipboardDeduperPersistence {
    private const val PREFS_NAME = "fileapex_clipboard_deduper"
    private const val KEY_STATE = "state"
    private const val PURPOSE = "clipboard-deduper"

    actual fun load(): PersistedDeduperState {
        val context = androidAppContextOrNull() ?: return PersistedDeduperState(null, emptyMap())
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_STATE, "").orEmpty()
        return DeduperStateCodec.decode(SealedText.open(PURPOSE, stored).orEmpty())
    }

    actual fun save(lastHash: String?, seenHashes: Map<String, Long>) {
        val context = androidAppContextOrNull() ?: return
        val sealed = SealedText.seal(PURPOSE, DeduperStateCodec.encode(lastHash, seenHashes)) ?: return
        // Hashes an older version kept as separate entries are dropped here.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().putString(KEY_STATE, sealed).apply()
    }

    actual fun clear() {
        val context = androidAppContextOrNull() ?: return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
