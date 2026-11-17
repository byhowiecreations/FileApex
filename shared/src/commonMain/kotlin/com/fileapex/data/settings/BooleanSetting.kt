package com.fileapex.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One stored on/off choice with its default, read once and kept live. */
internal class BooleanSetting(private val store: SettingsKvStore, private val key: String, private val default: Boolean = false) {
    private val state = MutableStateFlow(store.getBoolean(key, default))
    val flow: StateFlow<Boolean> = state.asStateFlow()

    fun set(value: Boolean) {
        store.putBoolean(key, value)
        state.value = value
    }

    /** The settings store has been cleared; fall back to the default in memory. */
    fun forget() {
        state.value = default
    }
}
