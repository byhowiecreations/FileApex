package com.fileapex.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class BulletinSettings(private val store: SettingsKvStore) {
    fun deltaCursor(deviceId: String): Long = store.getLong(DELTA_CURSOR_PREFIX + deviceId, 0L)

    fun setDeltaCursor(deviceId: String, cursor: Long) = store.putLong(DELTA_CURSOR_PREFIX + deviceId, cursor)

    fun clearDeltaCursors() = store.keys(DELTA_CURSOR_PREFIX).forEach(store::remove)

    private val seenAt = MutableStateFlow(store.getLong("bulletin_seen_at", 0L))
    val seenAtEpochMs: StateFlow<Long> = seenAt.asStateFlow()

    fun setSeenAt(epochMs: Long) {
        store.putLong("bulletin_seen_at", epochMs)
        seenAt.value = epochMs
    }

    private companion object {
        const val DELTA_CURSOR_PREFIX = "bulletin_delta_cursor_"
    }
}
