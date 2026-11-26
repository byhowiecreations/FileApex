package com.fileapex.data.settings

import com.fileapex.ui.theme.AccentChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The accent the user picked for the light themes; null means the theme's own default. */
class AccentSettings(private val store: SettingsKvStore) {
    private val choiceFlow = MutableStateFlow(AccentChoice.fromStorage(store.getString(KEY, "")))
    val choice: StateFlow<AccentChoice?> = choiceFlow.asStateFlow()

    fun setChoice(value: AccentChoice?) {
        choiceFlow.value = value
        store.putString(KEY, value?.storage ?: "")
    }

    private companion object {
        const val KEY = "accent_choice"
    }
}
