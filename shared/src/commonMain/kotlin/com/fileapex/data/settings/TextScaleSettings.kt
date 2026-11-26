package com.fileapex.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's text size as a percentage on top of the system font size. */
class TextScaleSettings(private val store: SettingsKvStore) {
    private val percentFlow = MutableStateFlow(normalize(store.getString(KEY, "").toIntOrNull() ?: DEFAULT))
    val percent: StateFlow<Int> = percentFlow.asStateFlow()

    fun setPercent(value: Int) {
        val normalized = normalize(value)
        percentFlow.value = normalized
        store.putString(KEY, normalized.toString())
    }

    companion object {
        const val MIN = 80
        const val MAX = 130
        const val STEP = 5
        const val DEFAULT = 100
        private const val KEY = "text_scale_percent"

        fun normalize(value: Int): Int = (Math.round(value.coerceIn(MIN, MAX) / STEP.toFloat()) * STEP).coerceIn(MIN, MAX)
    }
}
