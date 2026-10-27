package com.fileapex.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class BulletinSettings(private val store: SettingsKvStore) {
    fun deltaCursor(deviceId: String): Long = store.getLong("bulletin_delta_cursor_$deviceId", 0L)

    fun setDeltaCursor(deviceId: String, cursor: Long) = store.putLong("bulletin_delta_cursor_$deviceId", cursor)

    private val seenAt = MutableStateFlow(store.getLong("bulletin_seen_at", 0L))
    val seenAtEpochMs: StateFlow<Long> = seenAt.asStateFlow()

    fun setSeenAt(epochMs: Long) {
        store.putLong("bulletin_seen_at", epochMs)
        seenAt.value = epochMs
    }
}
