package com.fileapex.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether animations that move or scale things are replaced by plain fades. System follows the operating system. */
enum class ReducedMotionSetting(val storage: String) {
    System("system"),
    On("on"),
    Off("off");

    companion object {
        fun fromStorage(value: String): ReducedMotionSetting = entries.firstOrNull { it.storage == value } ?: System
    }
}

class MotionSettings(private val store: SettingsKvStore) {
    private val reduceMotionFlow = MutableStateFlow(ReducedMotionSetting.fromStorage(store.getString(KEY_REDUCE_MOTION, "")))
    val reduceMotion: StateFlow<ReducedMotionSetting> = reduceMotionFlow.asStateFlow()

    fun setReduceMotion(setting: ReducedMotionSetting) {
        reduceMotionFlow.value = setting
        store.putString(KEY_REDUCE_MOTION, setting.storage)
    }

    private companion object {
        const val KEY_REDUCE_MOTION = "reduce_motion"
    }
}
