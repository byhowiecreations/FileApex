package com.fileapex.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The folders pinned in the explorer, in the order they were added. */
class ExplorerFavoritesStore(private val store: SettingsKvStore) {
    private val state = MutableStateFlow(store.getString(KEY, "").split('\n').filter { it.isNotBlank() })
    val favorites: StateFlow<List<String>> = state.asStateFlow()

    fun toggle(absolutePath: String) {
        val current = state.value
        val next = if (absolutePath in current) current - absolutePath else current + absolutePath
        state.value = next
        store.putString(KEY, next.joinToString("\n"))
    }

    fun forgetInMemory() {
        state.value = emptyList()
    }

    private companion object {
        const val KEY = BaseAppSettings.KEY_EXPLORER_FAVORITES
    }
}
